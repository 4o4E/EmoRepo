package top.e404.emorepo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.e404.emorepo.R
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.ui.selection.selectContinuousRange
import top.e404.emorepo.ui.selection.toggleSelection

@Composable
fun PackManagerScreen(state: EmoRepoState, packName: String, onBack: () -> Unit) {
    val pack = state.packs.firstOrNull { it.name == packName }
    if (pack == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("表情包不存在") }
        return
    }

    var selectedNames by rememberSaveable(packName) { mutableStateOf(emptyList<String>()) }
    val selected = selectedNames.toSet()
    var selectionMode by rememberSaveable(packName) { mutableStateOf(false) }
    var deleteDialog by rememberSaveable(packName) { mutableStateOf(false) }
    var moveDialog by rememberSaveable(packName) { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    val orderedRecords = remember(pack.records) {
        pack.records.sortedWith(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
    }
    var previewPage by remember { mutableStateOf<Int?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pendingImport = PendingImport(packName, uris)
    }

    PackManager(
        pack = pack,
        state = state,
        selected = selected,
        selectionMode = selectionMode,
        onBack = onBack,
        onSelectionChange = { selectedNames = it.toList() },
        onEnterSelection = { selectionMode = true },
        onExitSelection = {
            selectionMode = false
            selectedNames = emptyList()
        },
        onPreview = { record -> previewPage = orderedRecords.indexOfFirst { it.md5 == record.md5 } },
        onImport = { importLauncher.launch(arrayOf("image/*")) },
        onDelete = { deleteDialog = true },
        onMove = { moveDialog = true },
        onSetIcon = {
            val md5 = selected.singleOrNull() ?: return@PackManager
            state.manage(
                operation = { setIcon(packName, md5); "已设置封面" },
                onComplete = { selectedNames = emptyList() },
            )
        },
        onClearIcon = {
            state.manage(
                operation = { setIcon(packName, null); "已清空封面" },
                onComplete = { selectedNames = emptyList() },
            )
        },
        onMoveOrder = { offset ->
            val md5 = selected.singleOrNull() ?: return@PackManager
            state.manage(
                operation = {
                    val order = pack.records.sortedBy { it.order }.map { it.md5 }.toMutableList()
                    val oldIndex = order.indexOf(md5)
                    val newIndex = (oldIndex + offset).coerceIn(0, order.lastIndex)
                    if (oldIndex != newIndex) {
                        order.removeAt(oldIndex)
                        order.add(newIndex, md5)
                        reorder(packName, order)
                    }
                    "顺序已更新"
                },
                onComplete = { selectedNames = emptyList() },
            )
        },
    )

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("删除 ${selected.size} 个表情？") },
            text = { Text("图片和索引记录都会从仓库删除，后续 Git 同步会上传本次删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    state.deleteEmoticons(
                        packName = packName,
                        md5Values = selected.toList(),
                        onComplete = { selectedNames = emptyList() },
                    )
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
        )
    }
    if (moveDialog) {
        MoveDialog(
            targets = state.packs.filterNot { it.name == packName },
            count = selected.size,
            onDismiss = { moveDialog = false },
            onMove = { target ->
                moveDialog = false
                state.moveEmoticons(
                    sourcePackName = packName,
                    targetPackName = target,
                    md5Values = selected.toList(),
                    onComplete = { selectedNames = emptyList() },
                )
            },
        )
    }
    pendingImport?.let { pending ->
        ImportConfirmationDialog(
            pending = pending,
            onConfirm = {
                pendingImport = null
                state.importUris(pending.packName, pending.uris) { selectedNames = emptyList() }
            },
            onDismiss = { pendingImport = null },
        )
    }
    previewPage?.let { page ->
        FullScreenPreview(
            state = state,
            packName = packName,
            records = orderedRecords,
            initialPage = page,
            onDismiss = { previewPage = null },
        )
    }
}

@Composable
private fun PackManager(
    pack: EmoticonPack,
    state: EmoRepoState,
    selected: Set<String>,
    selectionMode: Boolean,
    onBack: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onPreview: (EmoticonRecord) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onSetIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onMoveOrder: (Int) -> Unit,
) {
    val ordered = remember(pack.records) {
        pack.records.sortedWith(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
    }
    val orderedIds = remember(ordered) { ordered.map { it.md5 } }
    val bounds = remember(pack.name) { mutableStateMapOf<String, Rect>() }
    var dragStart by remember(pack.name) { mutableStateOf<Int?>(null) }
    var dragBase by remember(pack.name) { mutableStateOf(emptySet<String>()) }

    fun selectRange(endIndex: Int) {
        val startIndex = dragStart ?: return
        onSelectionChange(
            selectContinuousRange(
                orderedIds = orderedIds,
                existingSelection = dragBase,
                startIndex = startIndex,
                endIndex = endIndex,
            ),
        )
    }

    fun indexAt(position: Offset): Int? {
        val direct = ordered.indexOfFirst { record -> bounds[record.md5]?.contains(position) == true }
        if (direct >= 0) return direct
        return ordered.indices
            .filter { bounds.containsKey(ordered[it].md5) }
            .minByOrNull { index ->
                val center = bounds.getValue(ordered[index].md5).center
                val dx = center.x - position.x
                val dy = center.y - position.y
                dx * dx + dy * dy
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                pack.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onImport) { Text("导入") }
            IconButton(onClick = if (selectionMode) onExitSelection else onEnterSelection) {
                Icon(
                    painter = painterResource(
                        if (selectionMode) R.drawable.ic_close else R.drawable.ic_select_multiple,
                    ),
                    contentDescription = if (selectionMode) "退出多选" else "进入多选",
                )
            }
            if (selectionMode) {
                Text("已选 ${selected.size}")
            }
            if (selectionMode && selected.isNotEmpty()) {
                TextButton(onClick = onMove) { Text("移动") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
            if (selectionMode && selected.size == 1) {
                TextButton(onClick = onSetIcon) { Text("设为封面") }
                TextButton(onClick = { onMoveOrder(-1) }) { Text("前移") }
                TextButton(onClick = { onMoveOrder(1) }) { Text("后移") }
            }
            if (pack.records.any { it.icon }) {
                TextButton(onClick = onClearIcon) { Text("清空封面") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(ordered, key = { _, record -> record.md5 }) { index, record ->
                val isSelected = record.md5 in selected
                Card(
                    onClick = {
                        if (selectionMode) {
                            onSelectionChange(toggleSelection(selected, record.md5))
                        } else {
                            onPreview(record)
                        }
                    },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        bounds[record.md5] = coordinates.boundsInRoot()
                    }.then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.medium,
                            )
                        } else {
                            Modifier
                        },
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        EmoticonPreview(
                            file = state.repository.imageFile(pack.name, record.name),
                            md5 = record.md5,
                            ext = record.ext,
                            targetSizePx = 256,
                            contentDescription = record.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (selectionMode) {
                            DragSelectionCheckbox(
                                checked = isSelected,
                                onToggle = {
                                    onSelectionChange(toggleSelection(selected, record.md5))
                                },
                                onDragStart = { rootPosition ->
                                    dragStart = index
                                    dragBase = selected
                                    selectRange(indexAt(rootPosition) ?: index)
                                },
                                onDrag = { rootPosition -> indexAt(rootPosition)?.let(::selectRange) },
                                onDragEnd = {
                                    dragStart = null
                                    dragBase = emptySet()
                                },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragSelectionCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        coordinates?.localToRoot(local)?.let(currentDragStart)
                    },
                    onDrag = { change, _ ->
                        coordinates?.localToRoot(change.position)?.let(currentDrag)
                        change.consume()
                    },
                    onDragEnd = currentDragEnd,
                    onDragCancel = currentDragEnd,
                )
            }
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MoveDialog(
    targets: List<EmoticonPack>,
    count: Int,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动 $count 个表情") },
        text = {
            if (targets.isEmpty()) {
                Text("没有其他表情包")
            } else {
                Column {
                    targets.forEach { pack ->
                        TextButton(onClick = { onMove(pack.name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(pack.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
