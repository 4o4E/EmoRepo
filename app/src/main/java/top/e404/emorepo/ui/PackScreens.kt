package top.e404.emorepo.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository

private enum class PackLayout { LIST, GRID }

@Composable
fun PackListScreen(
    state: EmoRepoState,
    onOpenPack: (String) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    }
    var layoutName by rememberSaveable {
        mutableStateOf(preferences.getString("pack_layout", PackLayout.LIST.name) ?: PackLayout.LIST.name)
    }
    val layout = runCatching { PackLayout.valueOf(layoutName) }.getOrDefault(PackLayout.LIST)
    var createPackDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!state.repositoryConfigured) {
            UnconfiguredRepositoryCard()
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutButton("列表", layout == PackLayout.LIST) {
                    layoutName = PackLayout.LIST.name
                    preferences.edit { putString("pack_layout", PackLayout.LIST.name) }
                }
                LayoutButton("平铺", layout == PackLayout.GRID) {
                    layoutName = PackLayout.GRID.name
                    preferences.edit { putString("pack_layout", PackLayout.GRID.name) }
                }
            }
            Button(onClick = { createPackDialog = true }) { Text("新建") }
        }
        Spacer(Modifier.height(12.dp))

        if (state.packs.isEmpty()) {
            Text("暂无表情包")
        } else if (layout == PackLayout.LIST) {
            PackListLayout(state.packs, state.repository, onOpenPack)
        } else {
            PackGridLayout(state.packs, state.repository, onOpenPack)
        }
    }

    if (createPackDialog) {
        CreatePackDialog(
            onDismiss = { createPackDialog = false },
            onCreate = { name ->
                createPackDialog = false
                state.manage(operation = { createPack(name); "已创建表情包 $name" })
            },
        )
    }
}

@Composable
private fun LayoutButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun PackListLayout(
    packs: List<EmoticonPack>,
    repository: EmoticonRepository,
    onOpenPack: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(packs, key = { it.name }) { pack ->
            Card(onClick = { onOpenPack(pack.name) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PackCover(pack, repository, Modifier.size(72.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        Text("${pack.records.size} 个", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PackGridLayout(
    packs: List<EmoticonPack>,
    repository: EmoticonRepository,
    onOpenPack: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(152.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(packs, key = { it.name }) { pack ->
            Card(onClick = { onOpenPack(pack.name) }) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    PackCover(pack, repository, Modifier.fillMaxWidth().height(120.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text("${pack.records.size} 个", style = MaterialTheme.typography.bodySmall)
                }
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
        FilteredThumbnail(
            file = repository.imageFile(pack.name, cover.name),
            md5 = cover.md5,
            targetSizePx = 384,
            contentDescription = "${pack.name} 封面",
            modifier = modifier,
        )
    }
}

@Composable
fun AddEmoticonsScreen(state: EmoRepoState) {
    var selectedPack by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedPack?.let { state.importUris(it, uris) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!state.repositoryConfigured) {
            UnconfiguredRepositoryCard()
            return@Column
        }
        Text("选择目标表情包", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.packs, key = { it.name }) { pack ->
                Card(
                    onClick = { selectedPack = pack.name },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPack == pack.name) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PackCover(pack, state.repository, Modifier.size(56.dp))
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Button(
            onClick = { launcher.launch(arrayOf("image/*")) },
            enabled = selectedPack != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (selectedPack == null) "先选择表情包" else "选择图片") }
    }
}

@Composable
fun SettingsScreen(state: EmoRepoState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("仓库", style = MaterialTheme.typography.titleMedium)
                Text(if (state.repositoryConfigured) "已就绪" else "尚未配置")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("设置项", style = MaterialTheme.typography.titleMedium)
                Text("同步间隔、使用记录、设备 ID、Git 作者和凭据将在对应功能接入后启用。")
            }
        }
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
