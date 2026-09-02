package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportTargetsTest {
    @Test
    fun `collapsed import targets move behind writable normal targets stably`() {
        val targets = importTargetPacks(
            listOf(
                pack("fold-a", collapsed = true),
                pack("normal-a"),
                pack("readonly", writable = false),
                pack("fold-b", collapsed = true),
                pack("normal-b"),
            ),
        )

        assertEquals(
            listOf("normal-a", "normal-b", "fold-a", "fold-b"),
            targets.map(PanelPack::id),
        )
    }

    private fun pack(
        id: String,
        writable: Boolean = true,
        collapsed: Boolean = false,
    ) = PanelPack(
        id = id,
        displayName = id,
        coverItemId = null,
        coverPackId = null,
        itemCount = 0,
        writable = writable,
        collapsed = collapsed,
    )
}
