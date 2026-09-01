package top.e404.emorepo.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.repository.EmoticonPack

class VirtualPanelItemsTest {
    @Test
    fun `recently added includes collapsed packs and excludes recently used items`() {
        val used = record("used", 999)
        val packs = listOf(
            EmoticonPack("normal", listOf(used, record("older", 20), record("same-b", Long.MAX_VALUE - 1))),
            EmoticonPack(
                "collapsed",
                listOf(record("newest", Long.MAX_VALUE), record("same-a", Long.MAX_VALUE - 1)),
                collapsed = true,
            ),
        )

        val selected = selectRecentlyAddedItems(
            packs,
            recentlyUsed = listOf(VirtualPanelItem("normal", used)),
            maximum = 3,
        )

        assertEquals(
            listOf("collapsed/newest.png", "collapsed/same-a.png", "normal/same-b.png"),
            selected.map { "${it.packId}/${it.record.name}" },
        )
        assertFalse(selected.any { it.record.name == "used.png" })
    }

    @Test
    fun `recently added uses Unicode code point order for equal timestamps`() {
        val selected = selectRecentlyAddedItems(
            listOf(
                EmoticonPack("\uD800\uDC00", listOf(record("same", 1))),
                EmoticonPack("\uE000", listOf(record("same", 1))),
            ),
            recentlyUsed = emptyList(),
        )

        assertEquals(listOf("\uE000", "\uD800\uDC00"), selected.map { it.packId })
    }

    @Test
    fun `recently added defaults to twenty and validates maximum`() {
        val pack = EmoticonPack(
            "pack",
            (0 until 25).map { index -> record(index.toString(), index.toLong()) },
        )

        val selected = selectRecentlyAddedItems(listOf(pack), emptyList())

        assertEquals(20, selected.size)
        assertEquals("24.png", selected.first().record.name)
        assertEquals("5.png", selected.last().record.name)
        assertThrows(IllegalArgumentException::class.java) {
            selectRecentlyAddedItems(listOf(pack), emptyList(), maximum = -1)
        }
    }

    @Test
    fun `recently added filters missing files before applying limit`() {
        val pack = EmoticonPack(
            "pack",
            (0 until 22).map { index -> record(index.toString(), index.toLong()) },
        )

        val selected = selectRecentlyAddedItems(listOf(pack), emptyList()) { item ->
            item.record.name != "21.png"
        }

        assertEquals(20, selected.size)
        assertEquals("20.png", selected.first().record.name)
        assertEquals("1.png", selected.last().record.name)
    }

    private fun record(id: String, time: Long) = EmoticonRecord(
        name = "$id.png",
        md5 = id.padEnd(32, '0').take(32),
        ext = "png",
        time = time,
    )
}
