package top.e404.emorepo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import top.e404.emorepo.config.SyncPhase

@Composable
fun SettingsScreen(state: EmoRepoState, onBack: () -> Unit) {
    val current = state.settings
    var authorName by remember(current) { mutableStateOf(current.authorName) }
    var authorEmail by remember(current) { mutableStateOf(current.authorEmail) }
    var deviceId by remember(current) { mutableStateOf(current.deviceId) }
    var maximumRecords by remember(current) { mutableStateOf(current.recentMaximumRecords.toString()) }
    var recentDelay by remember(current) { mutableStateOf(current.recentSyncDelayMinutes.toString()) }
    var backgroundInterval by remember(current) {
        mutableStateOf(current.backgroundSyncIntervalMinutes.toString())
    }
    var commitMessage by remember(current) { mutableStateOf(current.commitMessage) }
    var qqPanelColumns by remember(current) { mutableStateOf(current.qqPanelColumns) }
    var newToken by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { state.refreshSyncStatus() }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Text("软件设置", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            SettingsCard("仓库") {
                Text(current.remoteUrl)
                Text(syncStatusText(state), style = MaterialTheme.typography.bodyMedium)
                state.syncStatus.lastSuccessTime?.let { time ->
                    Text("最近成功：${DateFormat.getDateTimeInstance().format(Date(time))}")
                }
                state.syncStatus.lastError?.let { Text("最近错误：$it", color = MaterialTheme.colorScheme.error) }
                Button(onClick = state::syncNow, enabled = !state.busy) { Text("立即同步") }
            }
        }
        item {
            SettingsCard("Git 身份") {
                OutlinedTextField(
                    value = authorName,
                    onValueChange = { authorName = it },
                    label = { Text("作者名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authorEmail,
                    onValueChange = { authorEmail = it },
                    label = { Text("作者邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsCard("同步") {
                NumberField(backgroundInterval, { backgroundInterval = it }, "后台轮询间隔（分钟，0 为关闭）")
                NumberField(recentDelay, { recentDelay = it }, "使用记录同步延迟（分钟）")
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("提交信息") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsCard("使用记录") {
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("设备 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(maximumRecords, { maximumRecords = it }, "本设备最大记录数")
            }
        }
        item {
            SettingsCard("QQ 面板") {
                Text("每行 $qqPanelColumns 个表情")
                Slider(
                    value = qqPanelColumns.toFloat(),
                    onValueChange = { qqPanelColumns = it.roundToInt() },
                    valueRange = 3f..8f,
                    steps = 4,
                )
                Text("增加列数会缩小单元格，并在同一屏显示更多表情。")
            }
        }
        item {
            Button(
                onClick = {
                    state.updateSettings(
                        current.copy(
                            authorName = authorName,
                            authorEmail = authorEmail,
                            deviceId = deviceId,
                            recentMaximumRecords = maximumRecords.toIntOrNull() ?: -1,
                            recentSyncDelayMinutes = recentDelay.toIntOrNull() ?: -1,
                            backgroundSyncIntervalMinutes = backgroundInterval.toIntOrNull() ?: -1,
                            commitMessage = commitMessage,
                            qqPanelColumns = qqPanelColumns,
                        ),
                    )
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存设置") }
        }
        item {
            SettingsCard("凭据") {
                Text("现有 Token 不会回显。输入新 Token 后替换；公开仓库可清除。")
                OutlinedTextField(
                    value = newToken,
                    onValueChange = { newToken = it },
                    label = { Text("新 Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { state.updateToken(newToken); newToken = "" },
                        enabled = newToken.isNotBlank() && !state.busy,
                    ) { Text("更新 Token") }
                    OutlinedButton(onClick = { state.updateToken(null) }, enabled = !state.busy) {
                        Text("清除 Token")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all(Char::isDigit)) onValueChange(input) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun syncStatusText(state: EmoRepoState): String = when (state.syncStatus.phase) {
    SyncPhase.IDLE -> "尚未同步"
    SyncPhase.RUNNING -> "正在同步"
    SyncPhase.SUCCESS -> "同步正常"
    SyncPhase.ERROR -> "同步失败"
}
