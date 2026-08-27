package top.e404.emorepo.ui.selection

fun toggleSelection(existingSelection: Set<String>, id: String): Set<String> =
    if (id in existingSelection) existingSelection - id else existingSelection + id
