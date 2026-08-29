package top.e404.emorepo.repository

import java.io.ByteArrayOutputStream
import java.io.InputStream
import top.e404.emorepo.protocol.ProtocolException

object ImportLimits {
    const val MAXIMUM_ITEMS = 50
    const val MAXIMUM_FILE_BYTES = 64L * 1024L * 1024L
    const val MAXIMUM_BATCH_BYTES = 256L * 1024L * 1024L
}

data class BoundedImportBytes(
    val bytes: ByteArray,
    val size: Long,
)

/** 有界读取单个导入源，避免 readBytes 对未知长度输入无限扩容。 */
fun InputStream.readImportBytes(maximumRemainingBatchBytes: Long): BoundedImportBytes {
    require(maximumRemainingBatchBytes >= 0L) { "批次剩余大小不能小于 0" }
    val maximum = minOf(ImportLimits.MAXIMUM_FILE_BYTES, maximumRemainingBatchBytes)
    val output = ByteArrayOutputStream(minOf(maximum, DEFAULT_INITIAL_CAPACITY).toInt())
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maximum) {
            val boundary = if (maximumRemainingBatchBytes < ImportLimits.MAXIMUM_FILE_BYTES) {
                "批次原图总大小超过 256 MiB"
            } else {
                "单张原图超过 64 MiB"
            }
            throw ProtocolException(boundary)
        }
        output.write(buffer, 0, read)
    }
    return BoundedImportBytes(output.toByteArray(), total)
}

private const val READ_BUFFER_BYTES = 64 * 1024
private const val DEFAULT_INITIAL_CAPACITY = 1024L * 1024L
