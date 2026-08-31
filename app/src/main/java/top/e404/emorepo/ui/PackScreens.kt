package top.e404.emorepo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.e404.emorepo.R
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.protocol.pack.PackIndexRecord
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class PackLayout { LIST, GRID }

private const val COLLAPSED_HEADER_KEY = "\u0000collapsed-packs-header"
private const val CREATE_PACK_KEY = "\u0000create-pack"

@Composable
fun PackListScreen(
    state: EmoRepoState,
    onOpenSettings: () -> Unit,
    onOpenPack: (String) -> Unit,
) {
    var draftArrangement by remember { mutableStateOf<List<PackIndexRecord>?>(null) }
    var collapsedExpanded by rememberSaveable { mutableStateOf(false) }
    var menuPack by remember { mutableStateOf<EmoticonPack?>(null) }
    var renamePack by remember { mutableStateOf<EmoticonPack?>(null) }
    var deletePack by remember { mutableStateOf<EmoticonPack?>(null) }
    var createPackDialog by rememberSaveable { mutableStateOf(false) }
    val displayedPacks = draftArrangement?.mapNotNull { record ->
        state.packs.firstOrNull { it.name == record.name }?.copy(collapsed = record.collapsed)
    }
        ?.takeIf { it.size == state.packs.size }
        ?: state.packs
    BackHandler(enabled = draftArrangement != null) { draftArrangement = null }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (!state.repositoryConfigured) {
            UnconfiguredRepositoryCard()
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu),
                    contentDescription = "打开软件设置",
                )
            }
            Text(
                if (draftArrangement == null) {
                    "共 ${state.packs.size} 个表情包，${state.packs.sumOf { it.records.size }} 张表情"
                } else {
                    "排序模式：拖动表情包调整顺序"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (draftArrangement == null) {
                PackLayoutSelector(
                    selected = state.packLayout,
                    onSelect = state::updatePackLayout,
                )
            } else {
                TextButton(onClick = { draftArrangement = null }) { Text("取消") }
                Button(
                    onClick = {
                        val records = draftArrangement.orEmpty()
                        if (records.isNotEmpty()) {
                            state.updatePackArrangement(records) { draftArrangement = null }
                        }
                    },
                    enabled = !state.busy,
                ) { Text("完成") }
            }
        }
        Spacer(Modifier.height(4.dp))

        PackCollection(
                packs = displayedPacks,
                repository = state.repository,
                layout = state.packLayout,
                onPackClick = onOpenPack,
                reorderable = draftArrangement != null,
                editing = draftArrangement != null,
                showCreateItem = draftArrangement == null,
                onCreateItem = { createPackDialog = true },
                collapsedExpanded = collapsedExpanded,
                onCollapsedExpandedChange = { collapsedExpanded = it },
                onDragStarted = {
                    if (draftArrangement == null) {
                        draftArrangement = state.packs.map { PackIndexRecord(it.name, it.collapsed) }
                    }
                },
                onMove = { fromName, toName ->
                    val current = draftArrangement
                        ?: state.packs.map { PackIndexRecord(it.name, it.collapsed) }
                    draftArrangement = movePackItemByName(current, fromName, toName)
                },
                onToggleCollapsed = { name ->
                    val current = draftArrangement
                        ?: state.packs.map { PackIndexRecord(it.name, it.collapsed) }
                    draftArrangement = current.map { record ->
                        if (record.name == name) record.copy(collapsed = !record.collapsed) else record
                    }
                },
                onPackLongClick = { name -> menuPack = state.packs.firstOrNull { it.name == name } },
                modifier = Modifier.weight(1f),
        )
    }

    menuPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { menuPack = null },
            title = { Text(pack.name) },
            text = {
                Column {
                    TextButton(onClick = { menuPack = null; renamePack = pack }) { Text("重命名") }
                    TextButton(onClick = { menuPack = null; deletePack = pack }) { Text("删除") }
                    TextButton(
                        onClick = {
                            menuPack = null
                            draftArrangement = state.packs.map { PackIndexRecord(it.name, it.collapsed) }
                        },
                    ) { Text("进入编辑") }
                }
            },
            confirmButton = {},
        )
    }
    renamePack?.let { pack ->
        PackNameDialog(
            title = "重命名表情包",
            initialName = pack.name,
            confirmLabel = "重命名",
            onDismiss = { renamePack = null },
            onConfirm = { newName ->
                renamePack = null
                state.renamePack(pack.name, newName)
            },
        )
    }
    deletePack?.let { pack ->
        AlertDialog(
            onDismissRequest = { deletePack = null },
            title = { Text("删除表情包？") },
            text = { Text("将删除“${pack.name}”及其中 ${pack.records.size} 张表情，此操作会同步到 Git 仓库。") },
            confirmButton = {
                TextButton(onClick = { deletePack = null; state.deletePack(pack.name) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletePack = null }) { Text("取消") } },
        )
    }
    if (createPackDialog) {
        PackNameDialog(
            title = "新建表情包",
            confirmLabel = "创建",
            onDismiss = { createPackDialog = false },
            onConfirm = { name ->
                createPackDialog = false
                state.manage(
                    operationName = "create_pack",
                    operation = { createPack(name); "已创建表情包 $name" },
                )
            },
        )
    }
}

@Composable
fun PackLayoutSelector(
    selected: PackLayout,
    onSelect: (PackLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        Triple(PackLayout.LIST, R.drawable.ic_view_list, "列表布局"),
        Triple(PackLayout.GRID, R.drawable.ic_view_grid, "平铺布局"),
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(88.dp)) {
        options.forEachIndexed { index, (layout, icon, description) ->
            SegmentedButton(
                selected = selected == layout,
                onClick = { onSelect(layout) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.width(44.dp),
                contentPadding = PaddingValues(0.dp),
                icon = {},
                label = {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = description,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun PackCollection(
    packs: List<EmoticonPack>,
    repository: EmoticonRepository,
    layout: PackLayout,
    onPackClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedPack: String? = null,
    reorderable: Boolean = false,
    editing: Boolean = false,
    showCreateItem: Boolean = false,
    onCreateItem: () -> Unit = {},
    collapsedExpanded: Boolean = false,
    onCollapsedExpandedChange: (Boolean) -> Unit = {},
    onDragStarted: () -> Unit = {},
    onMove: (String, String) -> Unit = { _, _ -> },
    onToggleCollapsed: (String) -> Unit = {},
    onPackLongClick: (String) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val normalPacks = packs.filterNot { it.collapsed }
    val collapsedPacks = packs.filter { it.collapsed }

    fun dragStarted() {
        onDragStarted()
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun dragStopped() {
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }

    if (layout == PackLayout.LIST) {
        val listState = rememberLazyListState()
        if (!reorderable) {
            LazyColumn(
                modifier = modifier,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showCreateItem) {
                    item(key = CREATE_PACK_KEY) { CreatePackListCard(onCreateItem) }
                }
                items(normalPacks, key = { it.name }) { pack ->
                    PackListCard(
                        pack = pack,
                        repository = repository,
                        selected = selectedPack == pack.name,
                        onLongClick = { onPackLongClick(pack.name) },
                        onClick = { onPackClick(pack.name) },
                    )
                }
                if (collapsedPacks.isNotEmpty()) {
                    item(key = COLLAPSED_HEADER_KEY) {
                        CollapsedPacksHeader(
                            count = collapsedPacks.size,
                            expanded = collapsedExpanded,
                            onClick = { onCollapsedExpandedChange(!collapsedExpanded) },
                        )
                    }
                    if (collapsedExpanded) {
                        items(collapsedPacks, key = { it.name }) { pack ->
                            PackListCard(
                                pack = pack,
                                repository = repository,
                                selected = selectedPack == pack.name,
                                onLongClick = { onPackLongClick(pack.name) },
                                onClick = { onPackClick(pack.name) },
                            )
                        }
                    }
                }
            }
        } else {
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                val fromName = from.key as? String
                val toName = to.key as? String
                if (fromName != null && toName != null) onMove(fromName, toName)
            }
            LazyColumn(
                modifier = modifier,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val leadingPacks = if (editing) packs else normalPacks
                items(leadingPacks, key = { it.name }) { pack ->
                    ReorderableItem(reorderState, key = pack.name) { isDragging ->
                        val dragModifier = if (editing) {
                            Modifier.draggableHandle(
                                onDragStarted = { dragStarted() },
                                onDragStopped = { dragStopped() },
                            )
                        } else {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = { dragStarted() },
                                onDragStopped = { dragStopped() },
                            )
                        }
                        PackListCard(
                            pack = pack,
                            repository = repository,
                            selected = false,
                            isDragging = isDragging,
                            editing = editing,
                            modifier = dragModifier,
                            onClick = { if (!editing) onPackClick(pack.name) },
                            onToggleCollapsed = { onToggleCollapsed(pack.name) },
                        )
                    }
                }
                if (!editing && collapsedPacks.isNotEmpty()) {
                    item(key = COLLAPSED_HEADER_KEY) {
                        CollapsedPacksHeader(
                            count = collapsedPacks.size,
                            expanded = collapsedExpanded,
                            onClick = { onCollapsedExpandedChange(!collapsedExpanded) },
                        )
                    }
                    if (collapsedExpanded) {
                        items(collapsedPacks, key = { it.name }) { pack ->
                            ReorderableItem(reorderState, key = pack.name) { isDragging ->
                                PackListCard(
                                    pack = pack,
                                    repository = repository,
                                    selected = false,
                                    isDragging = isDragging,
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = { dragStarted() },
                                        onDragStopped = { dragStopped() },
                                    ),
                                    onClick = { onPackClick(pack.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        val gridState = rememberLazyGridState()
        if (!reorderable) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = modifier,
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showCreateItem) {
                    item(key = CREATE_PACK_KEY) { CreatePackGridCard(onCreateItem) }
                }
                items(normalPacks, key = { it.name }) { pack ->
                    PackGridCard(
                        pack = pack,
                        repository = repository,
                        selected = selectedPack == pack.name,
                        onLongClick = { onPackLongClick(pack.name) },
                        onClick = { onPackClick(pack.name) },
                    )
                }
                if (collapsedPacks.isNotEmpty()) {
                    item(
                        key = COLLAPSED_HEADER_KEY,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        CollapsedPacksHeader(
                            count = collapsedPacks.size,
                            expanded = collapsedExpanded,
                            onClick = { onCollapsedExpandedChange(!collapsedExpanded) },
                        )
                    }
                    if (collapsedExpanded) {
                        items(collapsedPacks, key = { it.name }) { pack ->
                            PackGridCard(
                                pack = pack,
                                repository = repository,
                                selected = selectedPack == pack.name,
                                onLongClick = { onPackLongClick(pack.name) },
                                onClick = { onPackClick(pack.name) },
                            )
                        }
                    }
                }
            }
        } else {
            val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
                val fromName = from.key as? String
                val toName = to.key as? String
                if (fromName != null && toName != null) onMove(fromName, toName)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = modifier,
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val leadingPacks = if (editing) packs else normalPacks
                items(leadingPacks, key = { it.name }) { pack ->
                    ReorderableItem(reorderState, key = pack.name) { isDragging ->
                        val dragModifier = if (editing) {
                            Modifier.draggableHandle(
                                onDragStarted = { dragStarted() },
                                onDragStopped = { dragStopped() },
                            )
                        } else {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = { dragStarted() },
                                onDragStopped = { dragStopped() },
                            )
                        }
                        PackGridCard(
                            pack = pack,
                            repository = repository,
                            selected = false,
                            isDragging = isDragging,
                            editing = editing,
                            modifier = dragModifier,
                            onClick = { if (!editing) onPackClick(pack.name) },
                            onToggleCollapsed = { onToggleCollapsed(pack.name) },
                        )
                    }
                }
                if (!editing && collapsedPacks.isNotEmpty()) {
                    item(
                        key = COLLAPSED_HEADER_KEY,
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        CollapsedPacksHeader(
                            count = collapsedPacks.size,
                            expanded = collapsedExpanded,
                            onClick = { onCollapsedExpandedChange(!collapsedExpanded) },
                        )
                    }
                    if (collapsedExpanded) {
                        items(collapsedPacks, key = { it.name }) { pack ->
                            ReorderableItem(reorderState, key = pack.name) { isDragging ->
                                PackGridCard(
                                    pack = pack,
                                    repository = repository,
                                    selected = false,
                                    isDragging = isDragging,
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = { dragStarted() },
                                        onDragStopped = { dragStopped() },
                                    ),
                                    onClick = { onPackClick(pack.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackListCard(
    pack: EmoticonPack,
    repository: EmoticonRepository,
    selected: Boolean,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    editing: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "pack-list-elevation")
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackCover(pack, repository, Modifier.size(64.dp))
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${pack.records.size} 个", style = MaterialTheme.typography.bodySmall)
                if (editing) {
                    TextButton(
                        onClick = onToggleCollapsed,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text(if (pack.collapsed) "取消折叠" else "折叠") }
                }
            }
        }
    }
}

@Composable
private fun PackGridCard(
    pack: EmoticonPack,
    repository: EmoticonRepository,
    selected: Boolean,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    editing: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "pack-grid-elevation")
    Card(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                PackCover(pack, repository, Modifier.fillMaxWidth().aspectRatio(1f))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.72f),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pack.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${pack.records.size} 个",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (editing) {
                TextButton(
                    onClick = onToggleCollapsed,
                    modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        if (pack.collapsed) "展开" else "折叠",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatePackListCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋", style = MaterialTheme.typography.headlineMedium)
            Text("新建表情包", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CreatePackGridCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("＋", style = MaterialTheme.typography.displaySmall)
        }
    }
}

@Composable
private fun CollapsedPacksHeader(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已折叠 $count 个表情包", modifier = Modifier.weight(1f))
            Text(if (expanded) "收起 ▲" else "展开 ▼")
        }
    }
}

@Composable
private fun PackCover(
    pack: EmoticonPack,
    repository: EmoticonRepository,
    modifier: Modifier,
) {
    val cover = remember(pack) {
        selectPackCover(pack.records) { repository.imageFile(pack.name, it.name).isFile }
    }
    if (cover == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("空") }
    } else {
        EmoticonPreview(
            file = repository.imageFile(pack.name, cover.name),
            md5 = cover.md5,
            ext = cover.ext,
            targetSizePx = 384,
            contentDescription = "${pack.name} 封面",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun UnconfiguredRepositoryCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("尚未配置 Git 仓库", style = MaterialTheme.typography.titleMedium)
            Text("请先在软件设置中配置并克隆远端仓库。")
        }
    }
}

@Composable
private fun PackNameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("表情包名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun <T> movePackItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    require(fromIndex in items.indices) { "fromIndex 超出表情包列表范围" }
    require(toIndex in items.indices) { "toIndex 超出表情包列表范围" }
    if (fromIndex == toIndex) return items
    return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

internal fun movePackItemByName(
    items: List<PackIndexRecord>,
    fromName: String,
    toName: String,
): List<PackIndexRecord> {
    val fromIndex = items.indexOfFirst { it.name == fromName }
    val toIndex = items.indexOfFirst { it.name == toName }
    if (fromIndex < 0 || toIndex < 0) return items
    return movePackItem(items, fromIndex, toIndex)
}
