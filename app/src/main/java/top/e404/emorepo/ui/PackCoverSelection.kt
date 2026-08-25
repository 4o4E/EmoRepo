package top.e404.emorepo.ui

import top.e404.emorepo.protocol.index.EmoticonRecord

fun selectPackCover(
    records: List<EmoticonRecord>,
    isAvailable: (EmoticonRecord) -> Boolean,
): EmoticonRecord? {
    val ordered = records.sortedWith(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
    return ordered.firstOrNull { it.icon && isAvailable(it) }
        ?: ordered.firstOrNull(isAvailable)
}
