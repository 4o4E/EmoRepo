package top.e404.emorepo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class PackLayout { LIST, GRID }

@Composable
fun PackListScreen(
    state: EmoRepoState,
    onOpenPack: (String) -> Unit,
) {
    var draftOrder by remember { mutableStateOf<List<String>?>(null) }
    val displayedPacks = draftOrder?.mapNotNull { name -> state.packs.firstOrNull { it.name == name } }
        ?.takeIf { it.size == state.packs.size }
        ?: state.packs
    BackHandler(enabled = draftOrder != null) { draftOrder = null }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (!state.repositoryConfigured) {
            UnconfiguredRepositoryCard()
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (draftOrder == null) {
                    "共 ${state.packs.size} 个表情包，${state.packs.sumOf { it.records.size }} 张表情"
                } else {
                    "排序模式：拖动表情包调整顺序"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (draftOrder == null) {
                PackLayoutSelector(
                    selected = state.packLayout,
                    onSelect = state::updatePackLayout,
                )
            } else {
                TextButton(onClick = { draftOrder = null }) { Text("取消") }
                Button(
                    onClick = {
                        val names = draftOrder.orEmpty()
                        if (names.isNotEmpty()) state.reorderPacks(names)
                        draftOrder = null
                    },
                ) { Text("完成") }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (state.packs.isEmpty()) {
            Text("暂无表情包")
        } else {
            PackCollection(
                packs = displayedPacks,
                repository = state.repository,
                layout = state.packLayout,
                onPackClick = onOpenPack,
                reorderable = true,
                editing = draftOrder != null,
                onDragStarted = {
                    if (draftOrder == null) draftOrder = state.packs.map { it.name }
                },
                onMove = { from, to ->
                    val current = draftOrder ?: state.packs.map { it.name }
                    draftOrder = movePackItem(current, from, to)
                },
                modifier = Modifier.weight(1f),
            )
        }
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
    onDragStarted: () -> Unit = {},
    onMove: (Int, Int) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current

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
                items(packs, key = { it.name }) { pack ->
                    PackListCard(pack, repository, selectedPack == pack.name) {
                        onPackClick(pack.name)
                    }
                }
            }
        } else {
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                onMove(from.index, to.index)
            }
            LazyColumn(
                modifier = modifier,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(packs, key = { it.name }) { pack ->
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
                            modifier = dragModifier,
                            onClick = { if (!editing) onPackClick(pack.name) },
                        )
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
                items(packs, key = { it.name }) { pack ->
                    PackGridCard(pack, repository, selectedPack == pack.name) {
                        onPackClick(pack.name)
                    }
                }
            }
        } else {
            val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
                onMove(from.index, to.index)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = modifier,
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(packs, key = { it.name }) { pack ->
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
                            modifier = dragModifier,
                            onClick = { if (!editing) onPackClick(pack.name) },
                        )
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
    onClick: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "pack-list-elevation")
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
    onClick: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "pack-grid-elevation")
    Card(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
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
fun AddEmoticonsScreen(state: EmoRepoState) {
    var selectedPack by rememberSaveable { mutableStateOf<String?>(null) }
    var createPackDialog by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = selectedPack
        if (target != null && uris.isNotEmpty()) pendingImport = PendingImport(target, uris)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!state.repositoryConfigured) {
            UnconfiguredRepositoryCard()
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "选择目标表情包",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { createPackDialog = true }) { Text("新建表情包") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${state.packs.size} 个表情包",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            PackLayoutSelector(
                selected = state.packLayout,
                onSelect = state::updatePackLayout,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (state.packs.isEmpty()) {
            Text("暂无表情包", modifier = Modifier.weight(1f))
        } else {
            PackCollection(
                packs = state.packs,
                repository = state.repository,
                layout = state.packLayout,
                onPackClick = { selectedPack = it },
                selectedPack = selectedPack,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { launcher.launch(arrayOf("image/*")) },
            enabled = selectedPack != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (selectedPack == null) "先选择表情包" else "选择图片") }
    }

    if (createPackDialog) {
        CreatePackDialog(
            onDismiss = { createPackDialog = false },
            onCreate = { name ->
                createPackDialog = false
                state.manage(
                    operation = { createPack(name); "已创建表情包 $name" },
                    onComplete = {
                        if (state.packs.any { it.name == name }) selectedPack = name
                    },
                )
            },
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
private fun CreatePackDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建表情包") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("表情包名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("创建") }
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
