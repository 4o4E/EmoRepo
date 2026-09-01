package top.e404.emorepo.ipc

import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.repository.EmoticonPack

internal data class VirtualPanelItem(
    val packId: String,
    val record: EmoticonRecord,
)

/** 最近添加只由现有协议字段推导，不创建新的持久化文件。 */
internal fun selectRecentlyAddedItems(
    packs: List<EmoticonPack>,
    recentlyUsed: List<VirtualPanelItem>,
    maximum: Int = RECENTLY_ADDED_MAXIMUM,
    exists: (VirtualPanelItem) -> Boolean = { true },
): List<VirtualPanelItem> {
    require(maximum >= 0) { "最近添加数量不能小于 0" }
    if (maximum == 0) return emptyList()
    val used = recentlyUsed.mapTo(hashSetOf()) { item -> item.packId to item.record.name }
    return packs
        .flatMap { pack -> pack.records.map { record -> VirtualPanelItem(pack.name, record) } }
        .filterNot { item -> item.packId to item.record.name in used }
        .filter(exists)
        .sortedWith { left, right ->
            right.record.time.compareTo(left.record.time)
                .takeIf { it != 0 }
                ?: compareUnicodeCodePoints(left.packId, right.packId)
                    .takeIf { it != 0 }
                ?: compareUnicodeCodePoints(left.record.name, right.record.name)
        }
        .take(maximum)
}

private fun compareUnicodeCodePoints(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCodePoint = Character.codePointAt(left, leftIndex)
        val rightCodePoint = Character.codePointAt(right, rightIndex)
        if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
        leftIndex += Character.charCount(leftCodePoint)
        rightIndex += Character.charCount(rightCodePoint)
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

internal const val RECENTLY_ADDED_MAXIMUM = 20
