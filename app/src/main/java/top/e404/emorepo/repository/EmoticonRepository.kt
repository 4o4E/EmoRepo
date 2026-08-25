package top.e404.emorepo.repository

import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec

class EmoticonRepository(
    rootDirectory: File,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = rootDirectory.canonicalFile
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
        require(root.isDirectory) { "repository root is not a directory" }
    }

    fun listPacks(): List<EmoticonPack> = lock.withLock {
        root.listFiles()
            .orEmpty()
            .filter { directory ->
                directory.isDirectory &&
                    directory.name != "recent" &&
                    directory.name != ".git" &&
                    !directory.name.startsWith(".")
            }
            .sortedBy { it.name }
            .map(::readPack)
    }

    fun getPack(name: String): EmoticonPack = lock.withLock {
        readPack(requirePackDirectory(name))
    }

    fun imageFile(packName: String, recordName: String): File = lock.withLock {
        val directory = requirePackDirectory(packName)
        ProtocolNames.requireSafeSegment(recordName, "emoticon name")
        val file = File(directory, recordName).canonicalFile
        if (file.parentFile != directory) {
            throw ProtocolException("emoticon path escapes pack")
        }
        file
    }

    fun createPack(name: String): EmoticonPack = lock.withLock {
        val directory = resolvePackDirectory(name)
        if (directory.exists()) {
            throw ProtocolException("emoticon pack already exists: $name")
        }
        if (!directory.mkdir()) {
            throw IOException("cannot create emoticon pack: $name")
        }
        try {
            writeRecords(directory, emptyList())
            EmoticonPack(name, emptyList())
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun import(packName: String, candidates: List<ImportCandidate>): ManagementBatchResult = lock.withLock {
        val directory = requirePackDirectory(packName)
        ManagementBatchResult(candidates.map { candidate -> importOne(directory, candidate) })
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
        ManagementBatchResult(md5Values.map { md5 -> moveOne(sourceDirectory, targetDirectory, md5) })
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
        val updated = md5Order.mapIndexed { index, md5 ->
            byMd5.getValue(md5).copy(order = (index + 1L) * ORDER_STEP)
        }
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
                order = nextOrder(records),
            )
            val imageFile = File(directory, record.name)
            if (imageFile.exists() && !AtomicFileStore.readBytes(imageFile).contentEquals(image.bytes)) {
                throw ProtocolException("same image path contains different bytes: ${record.name}")
            }
            val createdImage = !imageFile.exists()
            if (createdImage) AtomicFileStore.writeBytes(imageFile, image.bytes)
            try {
                writeAndVerify(directory, records + record)
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
            val targetRecord = sourceRecord.copy(
                icon = false,
                order = nextOrder(targetRecords),
            )
            val targetFile = File(targetDirectory, targetRecord.name)
            val sourceBytes = AtomicFileStore.readBytes(sourceFile)
            if (targetFile.exists() && !AtomicFileStore.readBytes(targetFile).contentEquals(sourceBytes)) {
                throw ProtocolException("target image path contains different bytes")
            }
            val createdTargetFile = !targetFile.exists()
            if (createdTargetFile) AtomicFileStore.writeBytes(targetFile, sourceBytes)
            try {
                writeAndVerify(targetDirectory, targetRecords + targetRecord)
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
        return IndexJsonlCodec.decode(AtomicFileStore.readText(index))
    }

    private fun writeRecords(directory: File, records: List<EmoticonRecord>) {
        AtomicFileStore.writeText(File(directory, INDEX_FILE_NAME), IndexJsonlCodec.encode(records))
    }

    private fun writeAndVerify(directory: File, records: List<EmoticonRecord>): EmoticonPack {
        writeRecords(directory, records)
        val verified = readRecords(directory)
        return EmoticonPack(directory.name, verified)
    }

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

    private fun nextOrder(records: List<EmoticonRecord>): Long {
        val maximum = records.maxOfOrNull { it.order } ?: 0L
        if (maximum > Long.MAX_VALUE - ORDER_STEP) {
            throw ProtocolException("order space exhausted; normalize the pack first")
        }
        return maximum + ORDER_STEP
    }

    private companion object {
        const val INDEX_FILE_NAME = "index.jsonl"
        const val ORDER_STEP = 1024L
    }
}
