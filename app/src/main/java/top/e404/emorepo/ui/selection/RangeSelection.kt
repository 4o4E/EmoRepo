package top.e404.emorepo.ui.selection

fun selectContinuousRange(
    orderedIds: List<String>,
    existingSelection: Set<String>,
    startIndex: Int,
    endIndex: Int,
): Set<String> {
    require(startIndex in orderedIds.indices) { "startIndex is outside orderedIds" }
    require(endIndex in orderedIds.indices) { "endIndex is outside orderedIds" }
    val range = if (startIndex <= endIndex) startIndex..endIndex else endIndex..startIndex
    return existingSelection + range.map(orderedIds::get)
}
