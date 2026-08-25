package top.e404.emorepo.protocol.recent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException

class RecentCsvCodecTest {
    @Test
    fun mergeKeepsNewestAndUsesStableOrder() {
        val records = listOf(
            RecentUsageRecord("cats", "a.png", 10),
            RecentUsageRecord("cats", "a.png", 20),
            RecentUsageRecord("dogs", "b.gif", 20),
        )

        assertEquals(
            listOf(
                RecentUsageRecord("cats", "a.png", 20),
                RecentUsageRecord("dogs", "b.gif", 20),
            ),
            RecentCsvCodec.merge(records),
        )
    }

    @Test
    fun roundTripQuotesCommas() {
        val records = listOf(RecentUsageRecord("cats,small", "a,b.png", 20))
        val encoded = RecentCsvCodec.encode(records)

        assertEquals("package,name,time\n\"cats,small\",\"a,b.png\",20\n", encoded)
        assertEquals(records, RecentCsvCodec.decode(encoded))
    }

    @Test
    fun crlfInputIsAccepted() {
        val content = "package,name,time\r\ncats,a.png,20\r\n"

        assertEquals(
            listOf(RecentUsageRecord("cats", "a.png", 20)),
            RecentCsvCodec.decode(content),
        )
    }

    @Test
    fun invalidHeaderIsRejected() {
        assertThrows(ProtocolException::class.java) {
            RecentCsvCodec.decode("name,package,time\na.png,cats,20\n")
        }
    }

    @Test
    fun invalidTimeIsRejected() {
        assertThrows(ProtocolException::class.java) {
            RecentCsvCodec.decode("package,name,time\ncats,a.png,1.5\n")
        }
    }

    @Test
    fun pathEscapeIsRejected() {
        assertThrows(ProtocolException::class.java) {
            RecentCsvCodec.decode("package,name,time\n../cats,a.png,20\n")
        }
    }
}
