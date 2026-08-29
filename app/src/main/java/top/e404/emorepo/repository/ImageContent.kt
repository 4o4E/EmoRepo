package top.e404.emorepo.repository

import java.security.MessageDigest
import top.e404.emorepo.protocol.ProtocolException

internal data class ImageContent(
    val bytes: ByteArray,
    val md5: String,
    val extension: String,
)

internal object ImageContentInspector {
    fun inspect(bytes: ByteArray): ImageContent {
        val extension = when {
            bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
            bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
            bytes.startsWith(0xff, 0xd8, 0xff) -> "jpg"
            bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
                bytes.hasAsciiAt(8, "WEBP") -> "webp"
            else -> throw ProtocolException("unsupported image content")
        }
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val md5 = digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return ImageContent(bytes, md5, extension)
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { index ->
            this[index].toInt() and 0xff == expected[index]
        }

    private fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean =
        size >= offset + value.length && value.indices.all { index ->
            this[offset + index].toInt() and 0xff == value[index].code
        }
}
