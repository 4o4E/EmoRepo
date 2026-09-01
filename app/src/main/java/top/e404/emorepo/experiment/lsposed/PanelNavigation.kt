package top.e404.emorepo.experiment.lsposed

/** QQ 浏览顺序只重排展示，不改写根索引。 */
internal fun orderPanelPacksForBrowsing(items: List<PanelPack>): List<PanelPack> {
    val recent = items.filter { it.id == top.e404.emorepo.ipc.EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID }
    val real = items.filterNot { it.id == top.e404.emorepo.ipc.EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID }
    return recent + real.filterNot(PanelPack::collapsed) + real.filter(PanelPack::collapsed)
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

internal fun panelHorizontalLoopTarget(
    startPosition: Int,
    packCount: Int,
    distanceX: Float,
    distanceY: Float,
    thresholdPixels: Float,
): Int? {
    if (packCount <= 1 || kotlin.math.abs(distanceX) < thresholdPixels) return null
    if (kotlin.math.abs(distanceX) <= kotlin.math.abs(distanceY)) return null
    return when {
        startPosition == 0 && distanceX > 0f -> packCount - 1
        startPosition == packCount - 1 && distanceX < 0f -> 0
        else -> null
    }
}
