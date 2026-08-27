package top.e404.emorepo.repository

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import kotlin.concurrent.withLock
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.recent.RecentCsvCodec
import top.e404.emorepo.protocol.recent.RecentUsageRecord

class RecentUsageRepository(
    rootDirectory: File,
    val deviceId: String,
    val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) {
    private val root = rootDirectory.canonicalFile
    private val recentDirectory = File(root, RECENT_DIRECTORY_NAME)
    private val lock = RepositoryLocks.forRoot(root)

    init {
        validateDeviceId(deviceId)
        require(maximumRecords >= 0) { "maximumRecords must not be negative" }
        root.mkdirs()
        require(root.isDirectory) { "repository root is not a directory" }
    }

    fun readCurrentDevice(): List<RecentUsageRecord> = lock.withLock {
        readDeviceFile(deviceFile(deviceId))
    }

    fun readMerged(): List<RecentUsageRecord> = lock.withLock {
        if (!recentDirectory.exists()) return@withLock emptyList()
        val records = recentDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .sortedBy { it.name }
            .flatMap { file ->
                validateDeviceId(file.nameWithoutExtension)
                readDeviceFile(file)
            }
        RecentCsvCodec.merge(records)
    }

    fun trimCurrentDevice() = lock.withLock {
        val file = deviceFile(deviceId)
        if (!file.exists()) return@withLock
        val current = readDeviceFile(file)
        val trimmed = current.take(maximumRecords)
        if (trimmed.size != current.size) writeDeviceFile(file, trimmed)
    }

    fun recordUse(packageName: String, name: String, time: Long) = lock.withLock {
        if (maximumRecords == 0) return@withLock
        val current = readDeviceFile(deviceFile(deviceId))
        val updated = RecentCsvCodec.merge(
            current + RecentUsageRecord(packageName, name, time),
        ).take(maximumRecords)
        writeDeviceFile(deviceFile(deviceId), updated)
    }

    fun remove(packageName: String, name: String) = lock.withLock {
        val file = deviceFile(deviceId)
        if (!file.exists()) return@withLock
        val current = readDeviceFile(file)
        val updated = current.filterNot { it.packageName == packageName && it.name == name }
        if (updated.size != current.size) writeDeviceFile(file, updated)
    }

    fun move(
        sourcePackageName: String,
        sourceName: String,
        targetPackageName: String,
        targetName: String,
    ) = lock.withLock {
        val file = deviceFile(deviceId)
        if (!file.exists()) return@withLock
        val current = readDeviceFile(file)
        val source = current.firstOrNull {
            it.packageName == sourcePackageName && it.name == sourceName
        } ?: return@withLock
        val updated = current
            .filterNot { it.packageName == sourcePackageName && it.name == sourceName }
            .plus(RecentUsageRecord(targetPackageName, targetName, source.time))
        writeDeviceFile(file, applyMaintenanceLimit(RecentCsvCodec.merge(updated)))
    }

    fun renameDevice(newDeviceId: String): RecentUsageRepository = lock.withLock {
        validateDeviceId(newDeviceId)
        if (newDeviceId == deviceId) return@withLock this
        val oldFile = deviceFile(deviceId)
        val newFile = deviceFile(newDeviceId)
        val hadOldFile = oldFile.exists()
        val hadNewFile = newFile.exists()
        if (hadOldFile || hadNewFile) {
            val merged = RecentCsvCodec.merge(
                readDeviceFile(oldFile) + readDeviceFile(newFile),
            )
            writeDeviceFile(newFile, applyMaintenanceLimit(merged))
            if (hadOldFile && !oldFile.delete()) {
                throw IOException("cannot delete old recent device file: ${oldFile.name}")
            }
        }
        RecentUsageRepository(root, newDeviceId, maximumRecords)
    }

    private fun readDeviceFile(file: File): List<RecentUsageRecord> {
        AtomicFileStore.recover(file)
        if (!file.exists()) return emptyList()
        return RecentCsvCodec.decode(AtomicFileStore.readText(file))
    }

    private fun writeDeviceFile(file: File, records: Collection<RecentUsageRecord>) {
        recentDirectory.mkdirs()
        if (!recentDirectory.isDirectory) {
            throw IOException("cannot create recent directory")
        }
        AtomicFileStore.writeText(file, RecentCsvCodec.encode(records))
        RecentCsvCodec.decode(AtomicFileStore.readText(file))
    }

    private fun applyMaintenanceLimit(records: List<RecentUsageRecord>): List<RecentUsageRecord> =
        if (maximumRecords == 0) records else records.take(maximumRecords)

    private fun deviceFile(id: String): File = File(recentDirectory, "$id.csv")

    companion object {
        const val DEFAULT_MAXIMUM_RECORDS = 30
        private const val RECENT_DIRECTORY_NAME = "recent"
        private val deviceIdPattern = Regex("[A-Za-z0-9_-]{1,48}")
        fun generateDeviceId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(4).also(random::nextBytes)
            return "android-" + bytes.joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }

        fun validateDeviceId(value: String) {
            if (!deviceIdPattern.matches(value)) {
                throw ProtocolException("device ID must match [A-Za-z0-9_-]{1,48}")
            }
        }
    }
}
