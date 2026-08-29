package top.e404.emorepo.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException

class BoundedImportReaderTest {
    @Test
    fun `reads exactly the remaining batch boundary`() {
        val source = byteArrayOf(1, 2, 3)

        val result = ByteArrayInputStream(source).readImportBytes(3)

        assertEquals(3L, result.size)
        assertArrayEquals(source, result.bytes)
    }

    @Test
    fun `rejects input beyond the remaining batch boundary`() {
        assertThrows(ProtocolException::class.java) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readImportBytes(3)
        }
    }
}
