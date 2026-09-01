package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
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
    fun `scroll state preserves offset and clamps position after item removal`() {
        val state = PanelGridScrollState(firstVisiblePosition = 20, topOffset = -18)

        assertEquals(PanelGridScrollState(9, -18), normalizePanelGridScrollState(state, itemCount = 10))
        assertEquals(null, normalizePanelGridScrollState(state, itemCount = 0))
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
