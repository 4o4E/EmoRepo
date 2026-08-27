package top.e404.emorepo.protocol.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException

class RootIndexJsonlCodecTest {
    @Test
    fun roundTripUsesCanonicalFieldsAndFinalNewline() {
        val records = listOf(PackOrderRecord("Kipfel", 1024), PackOrderRecord("何意味", 2048))

        val encoded = RootIndexJsonlCodec.encode(records)

        assertEquals(
            "{\"name\":\"Kipfel\",\"order\":1024}\n{\"name\":\"何意味\",\"order\":2048}\n",
            encoded,
        )
        assertEquals(records, RootIndexJsonlCodec.decode(encoded))
    }

    @Test
    fun rejectsDuplicatePackName() {
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.encode(
                listOf(PackOrderRecord("same", 1024), PackOrderRecord("same", 2048)),
            )
        }
    }

    @Test
    fun rejectsUnknownFieldAndReservedName() {
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.decode("{\"name\":\"pack\",\"order\":1024,\"extra\":1}\n")
        }
        assertThrows(ProtocolException::class.java) {
            RootIndexJsonlCodec.encode(listOf(PackOrderRecord("recent", 1024)))
        }
    }

    @Test
    fun sameOrderRemainsReadableForStableRuntimeTieBreak() {
        val records = listOf(PackOrderRecord("b", 1024), PackOrderRecord("a", 1024))

        assertEquals(records, RootIndexJsonlCodec.decode(RootIndexJsonlCodec.encode(records)))
    }
}
