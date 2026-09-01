package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelNavigationTest {
    @Test
    fun `collapsed packs move behind normal packs while preserving group order`() {
        val packs = listOf(
            pack("recent"),
            pack("recently_added"),
            pack("a", collapsed = true),
            pack("b"),
            pack("c", collapsed = true),
            pack("d"),
        )

        assertEquals(
            listOf("recent", "recently_added", "b", "d", "a", "c"),
            orderPanelPacksForBrowsing(packs).map(PanelPack::id),
        )
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
    fun `content positions append collapsed packs only after expansion`() {
        val packs = listOf(pack("recent"), pack("normal"), pack("folded-a", true), pack("folded-b", true))

        assertEquals(listOf(0, 1), visiblePanelPackPositions(packs, collapsedExpanded = false))
        assertEquals(listOf(0, 1, 2, 3), visiblePanelPackPositions(packs, collapsedExpanded = true))
    }

    @Test
    fun `collapsed packs auto expand only when user scroll reaches normal content end`() {
        assertEquals(true, shouldAutoExpandCollapsed(false, true, true, 10, 99, 99))
        assertEquals(false, shouldAutoExpandCollapsed(true, true, true, 10, 99, 99))
        assertEquals(false, shouldAutoExpandCollapsed(false, false, true, 10, 99, 99))
        assertEquals(false, shouldAutoExpandCollapsed(false, true, false, 10, 99, 99))
        assertEquals(false, shouldAutoExpandCollapsed(false, true, true, -10, 99, 99))
        assertEquals(false, shouldAutoExpandCollapsed(false, true, true, 10, 98, 99))
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
