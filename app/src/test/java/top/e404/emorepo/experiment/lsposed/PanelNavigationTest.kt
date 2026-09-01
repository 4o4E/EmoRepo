package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelNavigationTest {
    @Test
    fun `collapsed packs move behind normal packs while preserving group order`() {
        val packs = listOf(
            pack("recent"),
            pack("a", collapsed = true),
            pack("b"),
            pack("c", collapsed = true),
            pack("d"),
        )

        assertEquals(
            listOf("recent", "b", "d", "a", "c"),
            orderPanelPacksForBrowsing(packs).map(PanelPack::id),
        )
    }

    @Test
    fun `next pack loops from last to first`() {
        assertEquals(2, nextPanelPackIndex(1, 4))
        assertEquals(0, nextPanelPackIndex(3, 4))
        assertEquals(0, nextPanelPackIndex(0, 1))
    }

    @Test
    fun `collapsed tabs expand inline before settings`() {
        val packs = listOf(pack("recent"), pack("normal"), pack("folded-a", true), pack("folded-b", true))

        assertEquals(
            listOf(
                PanelTabEntry.Pack(0),
                PanelTabEntry.Pack(1),
                PanelTabEntry.Collapsed,
                PanelTabEntry.Settings,
            ),
            panelTabEntries(packs, collapsedExpanded = false),
        )
        assertEquals(
            listOf(
                PanelTabEntry.Pack(0),
                PanelTabEntry.Pack(1),
                PanelTabEntry.Collapsed,
                PanelTabEntry.Pack(2),
                PanelTabEntry.Pack(3),
                PanelTabEntry.Settings,
            ),
            panelTabEntries(packs, collapsedExpanded = true),
        )
    }

    @Test
    fun `pull only triggers once after extra vertical distance at bottom`() {
        val detector = EndOfPackPullDetector(72f)
        detector.onDown(atEnd = false, x = 100f, y = 300f)
        assertFalse(detector.onMove(atEnd = false, x = 100f, y = 180f))
        assertFalse(detector.onMove(atEnd = true, x = 100f, y = 170f))
        assertFalse(detector.onMove(atEnd = true, x = 100f, y = 110f))
        assertTrue(detector.onMove(atEnd = true, x = 100f, y = 90f))
        assertFalse(detector.onMove(atEnd = true, x = 100f, y = 0f))
    }

    @Test
    fun `horizontal motion does not trigger next pack`() {
        val detector = EndOfPackPullDetector(72f)
        detector.onDown(atEnd = true, x = 100f, y = 300f)

        assertFalse(detector.onMove(atEnd = true, x = 220f, y = 220f))
    }

    private fun pack(id: String, collapsed: Boolean = false) = PanelPack(
        id = id,
        displayName = id,
        coverItemId = null,
        coverPackId = null,
        itemCount = 1,
        writable = true,
        collapsed = collapsed,
    )
}
