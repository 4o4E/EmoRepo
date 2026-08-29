package top.e404.emorepo.experiment.lsposed

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QqRkeyStoreTest {
    @Test
    fun `按 4 4 1 1 路径读取群聊和私聊 rkey`() {
        val group = field(1, "&rkey=group".toByteArray())
        val private = field(1, "&rkey=private".toByteArray())
        val payload = field(4, field(4, field(1, group) + field(1, private)))

        val result = QqRkeyStore.parseRkeys(payload)

        assertEquals("&rkey=group", result.group)
        assertEquals("&rkey=private", result.private)
    }

    @Test
    fun `损坏或缺项响应会被拒绝`() {
        assertThrows(IllegalStateException::class.java) {
            QqRkeyStore.parseRkeys(field(4, byteArrayOf()))
        }
    }

    private fun field(number: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        writeVarint(output, (number shl 3) or 2)
        writeVarint(output, value.size)
        output.write(value)
        output.toByteArray()
    }

    private fun writeVarint(output: ByteArrayOutputStream, input: Int) {
        var value = input
        while (value >= 0x80) {
            output.write((value and 0x7f) or 0x80)
            value = value ushr 7
        }
        output.write(value)
    }
}
