package top.e404.emorepo.ui

import top.e404.emorepo.protocol.index.EmoticonRecord

fun selectPackCover(
    records: List<EmoticonRecord>,
    isAvailable: (EmoticonRecord) -> Boolean,
): EmoticonRecord? {
    return records.firstOrNull { it.icon && isAvailable(it) }
        ?: records.firstOrNull(isAvailable)
}
