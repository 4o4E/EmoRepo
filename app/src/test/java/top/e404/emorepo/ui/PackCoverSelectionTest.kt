package top.e404.emorepo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.e404.emorepo.protocol.index.EmoticonRecord

class PackCoverSelectionTest {
    private val first = record("a", order = 1024)
    private val explicit = record("b", order = 2048, icon = true)

    @Test
    fun `uses available explicit cover before order`() {
        assertEquals(explicit, selectPackCover(listOf(first, explicit)) { true })
    }

    @Test
    fun `falls back to first available record when explicit cover is missing`() {
        assertEquals(first, selectPackCover(listOf(first, explicit)) { it != explicit })
    }

    @Test
    fun `returns null for empty or entirely missing pack`() {
        assertNull(selectPackCover(emptyList()) { true })
        assertNull(selectPackCover(listOf(first, explicit)) { false })
    }

    private fun record(md5: String, order: Long, icon: Boolean = false) = EmoticonRecord(
        name = "$md5.png",
        md5 = md5.padEnd(32, '0'),
        ext = "png",
        time = 1,
        icon = icon,
        order = order,
    )
}
