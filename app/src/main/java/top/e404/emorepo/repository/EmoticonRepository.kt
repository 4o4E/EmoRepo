package top.e404.emorepo.repository

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
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
        lock.withLock { recoverPackTransaction() }
    }

    fun listPacks(): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        readPackIndex(directories).map { record ->
            readPack(directories.first { it.name == record.name }, record.collapsed)
        }
    }

    fun getPack(name: String): EmoticonPack = lock.withLock {
        val directory = requirePackDirectory(name)
        val record = readPackIndex(packDirectories()).firstOrNull { it.name == name }
            ?: throw ProtocolException("root index.jsonl has no pack: $name")
        readPack(directory, record.collapsed)
    }

    fun initializePackOrder(): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        val index = rootIndexFile()
        if (!index.exists()) {
            writePackIndex(temporaryPackIndex(directories))
        }
        readPackIndex(directories).map { record ->
            readPack(directories.first { it.name == record.name }, record.collapsed)
        }
    }

    fun reorderPacks(names: List<String>): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        val currentNames = directories.map { it.name }
        if (names.size != currentNames.size || names.toSet() != currentNames.toSet()) {
            throw ProtocolException("pack reorder list must contain every pack exactly once")
        }
        val byName = readPackIndex(directories).associateBy { it.name }
        val normalized = names.map(byName::getValue)
        writePackIndex(normalized)
        normalized.map { record ->
            readPack(directories.first { it.name == record.name }, record.collapsed)
        }
    }

    fun updatePackArrangement(records: List<PackIndexRecord>): List<EmoticonPack> = lock.withLock {
        val directories = packDirectories()
        val currentNames = directories.map { it.name }
        if (records.size != currentNames.size || records.map { it.name }.toSet() != currentNames.toSet()) {
            throw ProtocolException("pack arrangement must contain every pack exactly once")
        }
        writePackIndex(records)
        records.map { record ->
            readPack(directories.first { it.name == record.name }, record.collapsed)
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
            EmoticonPack(name, emptyList(), collapsed = false)
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

    fun renamePack(oldName: String, newName: String): EmoticonPack = lock.withLock {
        val source = requirePackDirectory(oldName)
        val target = resolvePackDirectory(newName)
        if (source == target) return@withLock getPack(oldName)
        if (target.exists()) throw ProtocolException("emoticon pack already exists: $newName")
        val directories = packDirectories()
        val currentIndex = readPackIndex(directories)
        val sourceRecord = currentIndex.firstOrNull { it.name == oldName }
            ?: throw ProtocolException("root index.jsonl has no pack: $oldName")
        val transaction = beginPackTransaction(PackTransactionOperation.RENAME, oldName, newName)
        try {
            if (!source.renameTo(target)) throw IOException("cannot rename emoticon pack: $oldName")
            writePackIndex(currentIndex.map { record ->
                if (record.name == oldName) record.copy(name = newName) else record
            })
            RecentUsageRepository(root, "transaction", Int.MAX_VALUE)
                .renamePackageAcrossDevices(oldName, newName)
            readPackIndex(packDirectories())
            completePackTransaction(transaction)
            readPack(target, sourceRecord.collapsed)
        } catch (error: Exception) {
            recoverPackTransaction()
            throw error
        }
    }

    fun deletePack(name: String): EmoticonPack = lock.withLock {
        val source = requirePackDirectory(name)
        val directories = packDirectories()
        val currentIndex = readPackIndex(directories)
        val sourceRecord = currentIndex.firstOrNull { it.name == name }
            ?: throw ProtocolException("root index.jsonl has no pack: $name")
        val deleted = readPack(source, sourceRecord.collapsed)
        val transaction = beginPackTransaction(PackTransactionOperation.DELETE, name, null)
        val stagedPack = File(transaction, TRANSACTION_PACK_DIRECTORY)
        try {
            if (!source.renameTo(stagedPack)) throw IOException("cannot stage deleted emoticon pack: $name")
            writePackIndex(currentIndex.filterNot { it.name == name })
            RecentUsageRepository(root, "transaction", Int.MAX_VALUE).removePackageAcrossDevices(name)
            readPackIndex(packDirectories())
            completePackTransaction(transaction)
            deleted
        } catch (error: Exception) {
            recoverPackTransaction()
            throw error
        }
    }

    fun applyPackEdit(
        packName: String,
        originalMd5Order: List<String>,
        finalMd5Order: List<String>,
        recentDeviceId: String,
        recentMaximumRecords: Int,
    ): EmoticonPack = lock.withLock {
        val directory = requirePackDirectory(packName)
        val records = readRecords(directory)
        if (records.map { it.md5 } != originalMd5Order) {
            throw ProtocolException("emoticon pack changed while editing")
        }
        if (finalMd5Order.size != finalMd5Order.toSet().size || !originalMd5Order.containsAll(finalMd5Order)) {
            throw ProtocolException("final edit order must be a unique subset of original emoticons")
        }
        val byMd5 = records.associateBy { it.md5 }
        val finalRecords = finalMd5Order.map(byMd5::getValue)
        val deletedRecords = records.filterNot { it.md5 in finalMd5Order.toSet() }
        val transaction = beginPackTransaction(PackTransactionOperation.EDIT, packName, null)
        val deletedDirectory = File(transaction, TRANSACTION_DELETED_DIRECTORY).apply {
            if (!mkdirs() && !isDirectory) throw IOException("cannot create edit transaction directory")
        }
        try {
            deletedRecords.forEach { record ->
                val source = File(directory, record.name)
                if (source.exists() && !source.renameTo(File(deletedDirectory, record.name))) {
                    throw IOException("cannot stage deleted emoticon: ${record.name}")
                }
            }
            writeRecords(directory, finalRecords)
            val recent = RecentUsageRepository(root, recentDeviceId, recentMaximumRecords)
            deletedRecords.forEach { record -> recent.remove(packName, record.name) }
            val verified = readRecords(directory)
            if (verified.map { it.md5 } != finalMd5Order) throw ProtocolException("pack edit verification failed")
            val collapsed = readPackIndex(packDirectories()).first { it.name == packName }.collapsed
            completePackTransaction(transaction)
            EmoticonPack(packName, verified, collapsed)
        } catch (error: Exception) {
            recoverPackTransaction()
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

    private fun readPack(directory: File, collapsed: Boolean = false): EmoticonPack =
        EmoticonPack(directory.name, readRecords(directory), collapsed)

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
        val collapsed = readPackIndex(packDirectories()).firstOrNull { it.name == directory.name }?.collapsed
            ?: throw ProtocolException("root index.jsonl has no pack: ${directory.name}")
        writeRecords(directory, records)
        val verified = readRecords(directory)
        return EmoticonPack(directory.name, verified, collapsed)
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

    private fun beginPackTransaction(
        operation: PackTransactionOperation,
        sourceName: String,
        targetName: String?,
    ): File {
        cleanCompletedPackTransaction()
        val transaction = transactionDirectory()
        if (transaction.exists()) recoverPackTransaction()
        val preparing = File(root, TRANSACTION_PREPARING_DIRECTORY)
        if (preparing.exists() && !preparing.deleteRecursively()) {
            throw IOException("cannot clean unfinished transaction preparation")
        }
        if (!preparing.mkdir()) throw IOException("cannot create pack transaction directory")
        val properties = Properties().apply {
            setProperty(TRANSACTION_OPERATION, operation.name)
            setProperty(TRANSACTION_SOURCE, sourceName)
            targetName?.let { setProperty(TRANSACTION_TARGET, it) }
            setProperty(TRANSACTION_ROOT_EXISTS, rootIndexFile().isFile.toString())
        }
        val rootIndex = rootIndexFile()
        if (rootIndex.isFile) rootIndex.copyTo(File(preparing, TRANSACTION_ROOT_INDEX), overwrite = true)
        val recent = File(root, "recent")
        if (recent.isDirectory) {
            recent.copyRecursively(File(preparing, TRANSACTION_RECENT_DIRECTORY), overwrite = true)
        }
        if (operation == PackTransactionOperation.EDIT) {
            val packIndex = File(requirePackDirectory(sourceName), INDEX_FILE_NAME)
            packIndex.copyTo(File(preparing, TRANSACTION_PACK_INDEX), overwrite = true)
        }
        FileOutputStream(File(preparing, TRANSACTION_MANIFEST)).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        if (!preparing.renameTo(transaction)) throw IOException("cannot publish pack transaction")
        return transaction
    }

    private fun completePackTransaction(transaction: File) {
        val completed = completedTransactionDirectory()
        if (completed.exists() && !completed.deleteRecursively()) {
            throw IOException("cannot clean previous completed pack transaction")
        }
        if (!transaction.renameTo(completed)) throw IOException("cannot commit pack transaction")
        // 提交点是上面的同目录原子改名；清理失败时下次启动重试，不能再回滚已提交操作。
        completed.deleteRecursively()
    }

    private fun recoverPackTransaction() {
        val preparing = File(root, TRANSACTION_PREPARING_DIRECTORY)
        if (preparing.exists() && !preparing.deleteRecursively()) {
            throw IOException("cannot clean unfinished transaction preparation")
        }
        cleanCompletedPackTransaction()
        val transaction = transactionDirectory()
        if (!transaction.exists()) return
        val manifest = File(transaction, TRANSACTION_MANIFEST)
        if (!manifest.isFile) throw IOException("pack transaction manifest is missing")
        val properties = Properties().apply {
            FileInputStream(manifest).use(::load)
        }
        val operation = PackTransactionOperation.valueOf(
            requireNotNull(properties.getProperty(TRANSACTION_OPERATION)) { "pack transaction operation is missing" },
        )
        val sourceName = requireNotNull(properties.getProperty(TRANSACTION_SOURCE)) {
            "pack transaction source is missing"
        }
        val source = resolvePackDirectory(sourceName)
        when (operation) {
            PackTransactionOperation.RENAME -> {
                val targetName = requireNotNull(properties.getProperty(TRANSACTION_TARGET)) {
                    "pack transaction target is missing"
                }
                val target = resolvePackDirectory(targetName)
                if (!source.exists() && target.exists() && !target.renameTo(source)) {
                    throw IOException("cannot roll back renamed emoticon pack")
                }
            }
            PackTransactionOperation.DELETE -> {
                val stagedPack = File(transaction, TRANSACTION_PACK_DIRECTORY)
                if (!source.exists() && stagedPack.exists() && !stagedPack.renameTo(source)) {
                    throw IOException("cannot roll back deleted emoticon pack")
                }
            }
            PackTransactionOperation.EDIT -> {
                val deleted = File(transaction, TRANSACTION_DELETED_DIRECTORY)
                deleted.listFiles().orEmpty().forEach { staged ->
                    val target = File(source, staged.name)
                    if (!target.exists() && !staged.renameTo(target)) {
                        throw IOException("cannot restore edited emoticon: ${staged.name}")
                    }
                }
                val backupIndex = File(transaction, TRANSACTION_PACK_INDEX)
                if (backupIndex.isFile) AtomicFileStore.writeBytes(File(source, INDEX_FILE_NAME), backupIndex.readBytes())
            }
        }
        restoreRootIndex(transaction, properties)
        restoreRecentDirectory(transaction)
        if (!transaction.deleteRecursively()) throw IOException("cannot clean recovered pack transaction")
    }

    private fun restoreRootIndex(transaction: File, properties: Properties) {
        val rootIndex = rootIndexFile()
        if (properties.getProperty(TRANSACTION_ROOT_EXISTS).toBoolean()) {
            val backup = File(transaction, TRANSACTION_ROOT_INDEX)
            if (!backup.isFile) throw IOException("pack transaction root index backup is missing")
            AtomicFileStore.writeBytes(rootIndex, backup.readBytes())
        } else if (rootIndex.exists() && !rootIndex.delete()) {
            throw IOException("cannot remove rolled back root index")
        }
    }

    private fun restoreRecentDirectory(transaction: File) {
        val recent = File(root, "recent")
        val backup = File(transaction, TRANSACTION_RECENT_DIRECTORY)
        if (recent.exists() && !recent.deleteRecursively()) throw IOException("cannot restore recent usage directory")
        if (backup.isDirectory && !backup.copyRecursively(recent, overwrite = true)) {
            throw IOException("cannot copy recent usage backup")
        }
    }

    private fun transactionDirectory(): File = File(root, TRANSACTION_DIRECTORY)

    private fun completedTransactionDirectory(): File = File(root, TRANSACTION_COMPLETED_DIRECTORY)

    private fun cleanCompletedPackTransaction() {
        val completed = completedTransactionDirectory()
        if (completed.exists() && !completed.deleteRecursively()) {
            throw IOException("cannot clean completed pack transaction")
        }
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
        const val TRANSACTION_DIRECTORY = ".emorepo-pack-transaction"
        const val TRANSACTION_PREPARING_DIRECTORY = ".emorepo-pack-transaction.preparing"
        const val TRANSACTION_COMPLETED_DIRECTORY = ".emorepo-pack-transaction.completed"
        const val TRANSACTION_MANIFEST = "manifest.properties"
        const val TRANSACTION_OPERATION = "operation"
        const val TRANSACTION_SOURCE = "source"
        const val TRANSACTION_TARGET = "target"
        const val TRANSACTION_ROOT_EXISTS = "rootExists"
        const val TRANSACTION_ROOT_INDEX = "root-index.backup"
        const val TRANSACTION_PACK_INDEX = "pack-index.backup"
        const val TRANSACTION_RECENT_DIRECTORY = "recent-backup"
        const val TRANSACTION_PACK_DIRECTORY = "pack"
        const val TRANSACTION_DELETED_DIRECTORY = "deleted"
    }

    private enum class PackTransactionOperation { RENAME, DELETE, EDIT }
}
