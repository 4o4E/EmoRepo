package top.e404.emorepo.repository

import java.io.File
import java.io.IOException
import kotlin.concurrent.withLock
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackIndexRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec

class EmoticonRepository(
    rootDirectory: File,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = rootDirectory.canonicalFile
    private val lock = RepositoryLocks.forRoot(root)

    init {
        root.mkdirs()
        require(root.isDirectory) { "repository root is not a directory" }
    }

    fun listPacks(): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        readPackIndex(directories).map { record ->
            readPack(directories.first { it.name == record.name })
        }
    }

    fun getPack(name: String): EmoticonPack = lock.withLock {
        val directory = requirePackDirectory(name)
        requirePackIndexed(name)
        readPack(directory)
    }

    fun initializePackOrder(): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        val index = rootIndexFile()
        if (!index.exists()) {
            writePackIndex(temporaryPackIndex(directories))
        }
        readPackIndex(directories).map { record -> readPack(directories.first { it.name == record.name }) }
    }

    fun reorderPacks(names: List<String>): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        val currentNames = directories.map { it.name }
        if (names.size != currentNames.size || names.toSet() != currentNames.toSet()) {
            throw ProtocolException("pack reorder list must contain every pack exactly once")
        }
        val normalized = names.map(::PackIndexRecord)
        writePackIndex(normalized)
        normalized.map { record ->
            readPack(directories.first { it.name == record.name })
        }
    }

    fun imageFile(packName: String, recordName: String): File {
        val directory = requirePackDirectory(packName)
        ProtocolNames.requireSafeSegment(recordName, "emoticon name")
        val file = File(directory, recordName).canonicalFile
        if (file.parentFile != directory) {
            throw ProtocolException("emoticon path escapes pack")
        }
        return file
    }

    fun createPack(name: String): EmoticonPack = lock.withLock {
        val directories = packDirectories()
        val currentIndex = readPackIndex(directories)
        val rootIndex = rootIndexFile()
        val previousRootContent = rootIndex.takeIf { it.exists() }?.let(AtomicFileStore::readText)
        val directory = resolvePackDirectory(name)
        if (directory.exists()) {
            throw ProtocolException("emoticon pack already exists: $name")
        }
        if (!directory.mkdir()) {
            throw IOException("cannot create emoticon pack: $name")
        }
        try {
            writeRecords(directory, emptyList())
            writePackIndex(currentIndex + PackIndexRecord(name))
            EmoticonPack(name, emptyList())
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (previousRootContent == null) {
                rootIndex.delete()
            } else {
                runCatching { AtomicFileStore.writeText(rootIndex, previousRootContent) }
            }
            throw error
        }
    }

    fun import(packName: String, candidates: List<ImportCandidate>): ManagementBatchResult = lock.withLock {
        val directory = requirePackDirectory(packName)
        ManagementBatchResult(
            candidates.asReversed().map { candidate -> importOne(directory, candidate) }.asReversed(),
        )
    }

    fun delete(packName: String, md5Values: List<String>): ManagementBatchResult = lock.withLock {
        val directory = requirePackDirectory(packName)
        ManagementBatchResult(md5Values.map { md5 -> deleteOne(directory, md5) })
    }

    fun move(
        sourcePackName: String,
        targetPackName: String,
        md5Values: List<String>,
    ): ManagementBatchResult = lock.withLock {
        if (sourcePackName == targetPackName) {
            return@withLock ManagementBatchResult(
                md5Values.map { md5 ->
                    ManagementItemResult(md5, ManagementStatus.FAILED, message = "source and target pack are the same")
                },
            )
        }
        val sourceDirectory = requirePackDirectory(sourcePackName)
        val targetDirectory = requirePackDirectory(targetPackName)
        ManagementBatchResult(
            md5Values.asReversed().map { md5 -> moveOne(sourceDirectory, targetDirectory, md5) }.asReversed(),
        )
    }

    fun setIcon(packName: String, md5: String?): EmoticonPack = lock.withLock {
        val directory = requirePackDirectory(packName)
        val records = readRecords(directory)
        if (md5 != null && records.none { it.md5 == md5 }) {
            throw ProtocolException("emoticon does not exist: $md5")
        }
        val updated = records.map { record -> record.copy(icon = md5 != null && record.md5 == md5) }
        writeAndVerify(directory, updated)
    }

    fun reorder(packName: String, md5Order: List<String>): EmoticonPack = lock.withLock {
        val directory = requirePackDirectory(packName)
        val records = readRecords(directory)
        if (md5Order.size != records.size || md5Order.toSet() != records.map { it.md5 }.toSet()) {
            throw ProtocolException("reorder list must contain every emoticon exactly once")
        }
        val byMd5 = records.associateBy { it.md5 }
        val updated = md5Order.map(byMd5::getValue)
        writeAndVerify(directory, updated)
    }

    private fun importOne(directory: File, candidate: ImportCandidate): ManagementItemResult = try {
        val image = ImageContentInspector.inspect(candidate.bytes)
        val records = readRecords(directory)
        val existing = records.firstOrNull { it.md5 == image.md5 }
        if (existing != null) {
            val existingFile = File(directory, existing.name)
            if (!existingFile.exists()) {
                AtomicFileStore.writeBytes(existingFile, image.bytes)
            } else if (!AtomicFileStore.readBytes(existingFile).contentEquals(image.bytes)) {
                throw ProtocolException("same md5 corresponds to different image bytes")
            }
            ManagementItemResult(candidate.sourceName, ManagementStatus.DUPLICATE, existing)
        } else {
            val record = EmoticonRecord(
                name = "${image.md5}.${image.extension}",
                md5 = image.md5,
                ext = image.extension,
                time = currentTimeMillis(),
            )
            val imageFile = File(directory, record.name)
            if (imageFile.exists() && !AtomicFileStore.readBytes(imageFile).contentEquals(image.bytes)) {
                throw ProtocolException("same image path contains different bytes: ${record.name}")
            }
            val createdImage = !imageFile.exists()
            if (createdImage) AtomicFileStore.writeBytes(imageFile, image.bytes)
            try {
                writeAndVerify(directory, listOf(record) + records)
            } catch (error: Exception) {
                if (createdImage) imageFile.delete()
                throw error
            }
            ManagementItemResult(candidate.sourceName, ManagementStatus.SUCCESS, record)
        }
    } catch (error: Exception) {
        ManagementItemResult(candidate.sourceName, ManagementStatus.FAILED, message = error.message)
    }

    private fun deleteOne(directory: File, md5: String): ManagementItemResult = try {
        val records = readRecords(directory)
        val record = records.firstOrNull { it.md5 == md5 }
            ?: return ManagementItemResult(md5, ManagementStatus.FAILED, message = "emoticon does not exist")
        val updated = records.filterNot { it.md5 == md5 }
        writeAndVerify(directory, updated)
        val imageFile = File(directory, record.name)
        if (imageFile.exists() && !imageFile.delete()) {
            writeAndVerify(directory, records)
            throw IOException("cannot delete image: ${record.name}")
        }
        ManagementItemResult(md5, ManagementStatus.SUCCESS, record)
    } catch (error: Exception) {
        ManagementItemResult(md5, ManagementStatus.FAILED, message = error.message)
    }

    private fun moveOne(sourceDirectory: File, targetDirectory: File, md5: String): ManagementItemResult = try {
        val sourceRecords = readRecords(sourceDirectory)
        val sourceRecord = sourceRecords.firstOrNull { it.md5 == md5 }
            ?: return ManagementItemResult(md5, ManagementStatus.FAILED, message = "emoticon does not exist")
        val targetRecords = readRecords(targetDirectory)
        val targetExisting = targetRecords.firstOrNull { it.md5 == md5 }
        val sourceFile = File(sourceDirectory, sourceRecord.name)
        if (!sourceFile.isFile) {
            throw IOException("source image does not exist: ${sourceRecord.name}")
        }

        if (targetExisting != null) {
            val targetFile = File(targetDirectory, targetExisting.name)
            if (!targetFile.isFile || !AtomicFileStore.readBytes(targetFile).contentEquals(AtomicFileStore.readBytes(sourceFile))) {
                throw ProtocolException("same md5 corresponds to different image bytes")
            }
            writeAndVerify(sourceDirectory, sourceRecords.filterNot { it.md5 == md5 })
            if (!sourceFile.delete()) {
                writeAndVerify(sourceDirectory, sourceRecords)
                throw IOException("cannot delete deduplicated source image")
            }
            ManagementItemResult(md5, ManagementStatus.SUCCESS, targetExisting, deduplicated = true)
        } else {
            val targetRecord = sourceRecord.copy(icon = false)
            val targetFile = File(targetDirectory, targetRecord.name)
            val sourceBytes = AtomicFileStore.readBytes(sourceFile)
            if (targetFile.exists() && !AtomicFileStore.readBytes(targetFile).contentEquals(sourceBytes)) {
                throw ProtocolException("target image path contains different bytes")
            }
            val createdTargetFile = !targetFile.exists()
            if (createdTargetFile) AtomicFileStore.writeBytes(targetFile, sourceBytes)
            try {
                writeAndVerify(targetDirectory, listOf(targetRecord) + targetRecords)
                writeAndVerify(sourceDirectory, sourceRecords.filterNot { it.md5 == md5 })
                if (!sourceFile.delete()) {
                    throw IOException("cannot delete moved source image")
                }
            } catch (error: Exception) {
                runCatching { writeAndVerify(sourceDirectory, sourceRecords) }
                runCatching { writeAndVerify(targetDirectory, targetRecords) }
                if (createdTargetFile) targetFile.delete()
                throw error
            }
            ManagementItemResult(md5, ManagementStatus.SUCCESS, targetRecord)
        }
    } catch (error: Exception) {
        ManagementItemResult(md5, ManagementStatus.FAILED, message = error.message)
    }

    private fun readPack(directory: File): EmoticonPack =
        EmoticonPack(directory.name, readRecords(directory))

    private fun readRecords(directory: File): List<EmoticonRecord> {
        val index = File(directory, INDEX_FILE_NAME)
        AtomicFileStore.recover(index)
        if (!index.exists()) {
            throw ProtocolException("emoticon pack has no $INDEX_FILE_NAME: ${directory.name}")
        }
        LegacyOrderMigration.migratePack(index)
        return IndexJsonlCodec.decode(AtomicFileStore.readText(index))
    }

    private fun writeRecords(directory: File, records: List<EmoticonRecord>) {
        AtomicFileStore.writeText(File(directory, INDEX_FILE_NAME), IndexJsonlCodec.encode(records))
    }

    private fun writeAndVerify(directory: File, records: List<EmoticonRecord>): EmoticonPack {
        requirePackIndexed(directory.name)
        writeRecords(directory, records)
        val verified = readRecords(directory)
        return EmoticonPack(directory.name, verified)
    }

    private fun packDirectories(): List<File> = root.listFiles()
        .orEmpty()
        .filter { directory ->
            directory.isDirectory &&
                directory.name != "recent" &&
                directory.name != ".git" &&
                !directory.name.startsWith(".")
        }
        .sortedBy { it.name }

    private fun readPackIndex(directories: List<File>): List<PackIndexRecord> {
        val index = rootIndexFile()
        AtomicFileStore.recover(index)
        if (!index.exists()) return temporaryPackIndex(directories)
        LegacyOrderMigration.migrateRoot(index)
        val records = RootIndexJsonlCodec.decode(AtomicFileStore.readText(index))
        val directoryNames = directories.map { it.name }.toSet()
        val recordNames = records.map { it.name }.toSet()
        if (recordNames != directoryNames) {
            val missing = (directoryNames - recordNames).sorted()
            val extra = (recordNames - directoryNames).sorted()
            throw ProtocolException(
                "root index.jsonl does not match pack directories; missing=$missing, extra=$extra",
            )
        }
        return records
    }

    private fun temporaryPackIndex(directories: List<File>): List<PackIndexRecord> =
        directories.sortedBy { it.name }.map { directory -> PackIndexRecord(directory.name) }

    private fun writePackIndex(records: List<PackIndexRecord>) {
        AtomicFileStore.writeText(rootIndexFile(), RootIndexJsonlCodec.encode(records))
        RootIndexJsonlCodec.decode(AtomicFileStore.readText(rootIndexFile()))
    }

    private fun requirePackIndexed(name: String) {
        if (readPackIndex(packDirectories()).none { it.name == name }) {
            throw ProtocolException("root index.jsonl has no pack: $name")
        }
    }

    private fun rootIndexFile(): File = File(root, INDEX_FILE_NAME)

    private fun resolvePackDirectory(name: String): File {
        ProtocolNames.requireSafeSegment(name, "pack name")
        if (name == "recent" || name == ".git" || name.startsWith(".")) {
            throw ProtocolException("reserved emoticon pack name: $name")
        }
        val directory = File(root, name).canonicalFile
        if (directory.parentFile != root) {
            throw ProtocolException("pack path escapes repository")
        }
        return directory
    }

    private fun requirePackDirectory(name: String): File {
        val directory = resolvePackDirectory(name)
        if (!directory.isDirectory) {
            throw ProtocolException("emoticon pack does not exist: $name")
        }
        return directory
    }

    private companion object {
        const val INDEX_FILE_NAME = "index.jsonl"
    }
}
