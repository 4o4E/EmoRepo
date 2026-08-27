package top.e404.emorepo.ui

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

data class PendingImport(
    val packName: String,
    val uris: List<Uri>,
)

@Composable
fun ImportConfirmationDialog(
    pending: PendingImport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入 ${pending.uris.size} 张图片？") },
        text = { Text("目标表情包：${pending.packName}\n确认后才会写入仓库并触发同步。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
