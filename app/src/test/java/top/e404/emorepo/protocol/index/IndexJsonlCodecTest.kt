package top.e404.emorepo.protocol.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException

class IndexJsonlCodecTest {
    private val first = EmoticonRecord(
        name = "cat.png",
        md5 = "11111111111111111111111111111111",
        ext = "png",
        time = 100,
        icon = true,
    )

    @Test
    fun roundTripUsesCanonicalFieldOrderAndFinalNewline() {
        val encoded = IndexJsonlCodec.encode(listOf(first))

        assertEquals(
            "{\"name\":\"cat.png\",\"md5\":\"11111111111111111111111111111111\",\"ext\":\"png\",\"time\":100,\"icon\":true}\n",
            encoded,
        )
        assertEquals(listOf(first), IndexJsonlCodec.decode(encoded))
    }

    @Test
    fun optionalIconIsOmitted() {
        val encoded = IndexJsonlCodec.encode(listOf(first.copy(icon = false)))

        assertFalse(encoded.contains("\"icon\""))
    }

    @Test
    fun blankLinesAreIgnored() {
        val encoded = IndexJsonlCodec.encode(listOf(first))

        assertEquals(listOf(first), IndexJsonlCodec.decode("\n$encoded\n"))
    }

    @Test
    fun unknownFieldRejectsWholeDocument() {
        val content = """{"name":"cat.png","md5":"11111111111111111111111111111111","ext":"png","time":100,"extra":1}"""

        assertThrows(ProtocolException::class.java) { IndexJsonlCodec.decode(content) }
    }

    @Test
    fun duplicateMd5IsRejected() {
        val second = first.copy(name = "other.png", icon = false)

        val error = assertThrows(ProtocolException::class.java) {
            IndexJsonlCodec.encode(listOf(first, second))
        }
        assertTrue(error.message.orEmpty().contains("duplicate md5"))
    }

    @Test
    fun multipleIconsAreRejected() {
        val second = first.copy(
            name = "other.png",
            md5 = "22222222222222222222222222222222",
        )

        assertThrows(ProtocolException::class.java) {
            IndexJsonlCodec.encode(listOf(first, second))
        }
    }

    @Test
    fun fractionalIntegerIsRejected() {
        val content = """{"name":"cat.png","md5":"11111111111111111111111111111111","ext":"png","time":1.5}"""

        assertThrows(ProtocolException::class.java) { IndexJsonlCodec.decode(content) }
    }

    @Test
    fun pathEscapeIsRejected() {
        val record = first.copy(name = "../cat.png")

        assertThrows(ProtocolException::class.java) { IndexJsonlCodec.encode(listOf(record)) }
    }
}
