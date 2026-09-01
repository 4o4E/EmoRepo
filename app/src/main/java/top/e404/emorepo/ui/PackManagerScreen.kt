package top.e404.emorepo.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.ui.selection.selectContinuousRange
import top.e404.emorepo.ui.selection.toggleSelection

private const val ADD_EMOTICON_KEY = "\u0000add-emoticon"

@Composable
fun PackManagerScreen(state: EmoRepoState, packName: String, onBack: () -> Unit) {
    val pack = state.packs.firstOrNull { it.name == packName }
    if (pack == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("表情包不存在") }
        return
    }

    var editing by rememberSaveable(packName) { mutableStateOf(false) }
    var originalOrder by remember(packName) { mutableStateOf(emptyList<String>()) }
    var draftOrder by remember(packName) { mutableStateOf(emptyList<String>()) }
    var selectedNames by rememberSaveable(packName) { mutableStateOf(emptyList<String>()) }
    var deleteDialog by rememberSaveable(packName) { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var previewPage by remember { mutableStateOf<Int?>(null) }
    val selected = selectedNames.toSet()
    val recordsByMd5 = pack.records.associateBy { it.md5 }
    val displayedRecords = if (editing) draftOrder.mapNotNull(recordsByMd5::get) else pack.records
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pendingImport = PendingImport(packName, uris)
    }

    fun enterEditing() {
        originalOrder = pack.records.map { it.md5 }
        draftOrder = originalOrder
        selectedNames = emptyList()
        editing = true
    }

    fun cancelEditing() {
        editing = false
        originalOrder = emptyList()
        draftOrder = emptyList()
        selectedNames = emptyList()
    }

    BackHandler(enabled = editing, onBack = ::cancelEditing)

    PackManager(
        pack = pack,
        state = state,
        records = displayedRecords,
        selected = selected,
        editing = editing,
        onBack = { if (editing) cancelEditing() else onBack() },
        onEdit = ::enterEditing,
        onComplete = {
            state.applyPackEdit(
                packName = packName,
                originalMd5Order = originalOrder,
                finalMd5Order = draftOrder,
                onSuccess = ::cancelEditing,
            )
        },
        onSelectionChange = { selectedNames = it.toList() },
        onPreview = { record -> previewPage = pack.records.indexOfFirst { it.md5 == record.md5 } },
        onImport = { importLauncher.launch(arrayOf("image/*")) },
        onDelete = { deleteDialog = true },
        onMoveToFront = {
            draftOrder = moveSelectedToFront(draftOrder, selected)
            selectedNames = emptyList()
        },
    )

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("从编辑草稿删除 ${selected.size} 个表情？") },
            text = { Text("点击右上角“完成”后才会删除原文件；返回将放弃本次删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    draftOrder = draftOrder.filterNot { it in selected }
                    selectedNames = emptyList()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
        )
    }
    pendingImport?.let { pending ->
        ImportConfirmationDialog(
            pending = pending,
            onConfirm = {
                pendingImport = null
                state.importUris(pending.packName, pending.uris)
            },
            onDismiss = { pendingImport = null },
        )
    }
    previewPage?.let { page ->
        FullScreenPreview(
            state = state,
            packName = packName,
            records = pack.records,
            initialPage = page,
            onDismiss = { previewPage = null },
        )
    }
}

@Composable
private fun PackManager(
    pack: EmoticonPack,
    state: EmoRepoState,
    records: List<EmoticonRecord>,
    selected: Set<String>,
    editing: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onPreview: (EmoticonRecord) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFront: () -> Unit,
) {
    val orderedIds = remember(records) { records.map { it.md5 } }
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
        val direct = records.indexOfFirst { record -> bounds[record.md5]?.contains(position) == true }
        if (direct >= 0) return direct
        return records.indices
            .filter { bounds.containsKey(records[it].md5) }
            .minByOrNull { index ->
                val center = bounds.getValue(records[index].md5).center
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
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "共 ${pack.records.size} 个表情",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = if (editing) onComplete else onEdit,
                enabled = !state.busy,
            ) {
                Text(if (editing) "完成" else "编辑")
            }
        }
        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selected.size}", modifier = Modifier.weight(1f))
                TextButton(onClick = onMoveToFront, enabled = selected.isNotEmpty()) { Text("移动到开头") }
                TextButton(onClick = onDelete, enabled = selected.isNotEmpty()) { Text("删除") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!editing) {
                item(key = ADD_EMOTICON_KEY) {
                    Card(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("＋", style = MaterialTheme.typography.displaySmall)
                        }
                    }
                }
            }
            itemsIndexed(records, key = { _, record -> record.md5 }) { index, record ->
                val isSelected = record.md5 in selected
                Card(
                    onClick = {
                        if (editing) onSelectionChange(toggleSelection(selected, record.md5))
                        else onPreview(record)
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
                        } else Modifier,
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
                        if (editing) {
                            DragSelectionCheckbox(
                                checked = isSelected,
                                onToggle = { onSelectionChange(toggleSelection(selected, record.md5)) },
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

internal fun moveSelectedToFront(order: List<String>, selected: Set<String>): List<String> =
    order.filter(selected::contains) + order.filterNot(selected::contains)

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
                    onDragStart = { local -> coordinates?.localToRoot(local)?.let(currentDragStart) },
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
