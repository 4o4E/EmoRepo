package top.e404.emorepo.protocol.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException

class RootIndexJsonlCodecTest {
    @Test
    fun roundTripUsesCanonicalFieldsAndFinalNewline() {
        val records = listOf(PackIndexRecord("Kipfel"), PackIndexRecord("何意味", collapsed = true))

        val encoded = RootIndexJsonlCodec.encode(records)

        assertEquals(
            "{\"name\":\"Kipfel\"}\n{\"name\":\"何意味\",\"collapsed\":true}\n",
            encoded,
        )
        assertEquals(records, RootIndexJsonlCodec.decode(encoded))
    }

    @Test
    fun rejectsDuplicatePackName() {
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.encode(
                listOf(PackIndexRecord("same"), PackIndexRecord("same")),
            )
        }
    }

    @Test
    fun rejectsUnknownFieldAndReservedName() {
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.decode("{\"name\":\"pack\",\"extra\":1}\n")
        }
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.encode(listOf(PackIndexRecord("recent")))
        }
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.decode("{\"name\":\"pack\",\"collapsed\":false}\n")
        }
    }

    @Test
    fun lineOrderRoundTripsWithoutSecondarySorting() {
        val records = listOf(PackIndexRecord("b"), PackIndexRecord("a"))

        assertEquals(records, RootIndexJsonlCodec.decode(RootIndexJsonlCodec.encode(records)))
    }
}
