package top.e404.emorepo.experiment.lsposed

import top.e404.emorepo.ipc.EmoRepoIpcContract

/** QQ 浏览顺序只重排展示，不改写根索引。 */
internal fun orderPanelPacksForBrowsing(items: List<PanelPack>): List<PanelPack> {
    val byId = items.associateBy(PanelPack::id)
    val virtual = listOfNotNull(
        byId[EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID],
        byId[EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID],
    )
    val real = items.filterNot { it.id in VIRTUAL_PACK_IDS }
    return virtual + real.filterNot(PanelPack::collapsed) + real.filter(PanelPack::collapsed)
}

internal fun visiblePanelPackPositions(items: List<PanelPack>, collapsedExpanded: Boolean): List<Int> =
    items.indices.filter { position -> !items[position].collapsed || collapsedExpanded }

internal fun shouldRepositionPackTab(
    targetPosition: Int,
    firstCompletelyVisible: Int,
    lastCompletelyVisible: Int,
): Boolean = firstCompletelyVisible < 0 ||
    lastCompletelyVisible < firstCompletelyVisible ||
    targetPosition !in firstCompletelyVisible..lastCompletelyVisible

internal fun shouldAutoExpandCollapsed(
    collapsedExpanded: Boolean,
    hasCollapsedPacks: Boolean,
    userScrollActive: Boolean,
    verticalDelta: Int,
    lastVisiblePosition: Int,
    lastContentPosition: Int,
): Boolean = !collapsedExpanded &&
    hasCollapsedPacks &&
    userScrollActive &&
    verticalDelta > 0 &&
    lastContentPosition >= 0 &&
    lastVisiblePosition >= lastContentPosition

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

private val VIRTUAL_PACK_IDS = setOf(
    EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID,
    EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID,
)
