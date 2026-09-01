package top.e404.emorepo.experiment.lsposed

import kotlin.math.abs

/** QQ 浏览顺序只重排展示，不改写根索引。 */
internal fun orderPanelPacksForBrowsing(items: List<PanelPack>): List<PanelPack> {
    val recent = items.filter { it.id == top.e404.emorepo.ipc.EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID }
    val real = items.filterNot { it.id == top.e404.emorepo.ipc.EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID }
    return recent + real.filterNot(PanelPack::collapsed) + real.filter(PanelPack::collapsed)
}

internal fun nextPanelPackIndex(current: Int, count: Int): Int {
    if (count <= 1 || current !in 0 until count) return current
    return (current + 1) % count
}

internal sealed interface PanelTabEntry {
    data class Pack(val packPosition: Int) : PanelTabEntry
    data object Collapsed : PanelTabEntry
    data object Settings : PanelTabEntry
}

internal fun panelTabEntries(items: List<PanelPack>, collapsedExpanded: Boolean): List<PanelTabEntry> {
    val normal = items.indices.filter { !items[it].collapsed }.map(PanelTabEntry::Pack)
    val collapsed = items.indices.filter { items[it].collapsed }.map(PanelTabEntry::Pack)
    return buildList {
        addAll(normal)
        if (collapsed.isNotEmpty()) {
            add(PanelTabEntry.Collapsed)
            if (collapsedExpanded) addAll(collapsed)
        }
        add(PanelTabEntry.Settings)
    }
}

/** 只有已经到达底部后的额外纵向上拉才触发切包。 */
internal class EndOfPackPullDetector(private val thresholdPixels: Float) {
    private var startX: Float? = null
    private var startY: Float? = null
    private var triggered = false

    init {
        require(thresholdPixels > 0f) { "切包上拉阈值必须大于 0" }
    }

    fun onDown(atEnd: Boolean, x: Float, y: Float) {
        reset()
        if (atEnd) arm(x, y)
    }

    fun onMove(atEnd: Boolean, x: Float, y: Float): Boolean {
        if (triggered) return false
        if (!atEnd) {
            startX = null
            startY = null
            return false
        }
        val originY = startY
        val originX = startX
        if (originY == null || originX == null) {
            arm(x, y)
            return false
        }
        val upwardDistance = originY - y
        val horizontalDistance = abs(x - originX)
        if (upwardDistance < 0f) {
            arm(x, y)
            return false
        }
        if (upwardDistance >= thresholdPixels && upwardDistance > horizontalDistance) {
            triggered = true
            return true
        }
        return false
    }

    fun reset() {
        startX = null
        startY = null
        triggered = false
    }

    private fun arm(x: Float, y: Float) {
        startX = x
        startY = y
    }
}
