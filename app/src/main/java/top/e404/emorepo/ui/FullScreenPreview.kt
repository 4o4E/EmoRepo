package top.e404.emorepo.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import top.e404.emorepo.R
import top.e404.emorepo.protocol.index.EmoticonRecord

@Composable
fun FullScreenPreview(
    state: EmoRepoState,
    packName: String,
    records: List<EmoticonRecord>,
    initialPage: Int,
    onDismiss: () -> Unit,
) {
    if (records.isEmpty()) return
    val context = LocalContext.current
    var actionRecord by remember { mutableStateOf<EmoticonRecord?>(null) }
    var exportRecord by remember { mutableStateOf<EmoticonRecord?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { destination ->
        val record = exportRecord
        exportRecord = null
        if (destination != null && record != null) {
            state.exportImage(packName, record.name, destination)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(records.indices),
            pageCount = records::size,
        )
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                val record = records[page]
                val zoomableState = rememberZoomableState()
                EmoticonPreview(
                    file = state.repository.imageFile(packName, record.name),
                    md5 = record.md5,
                    ext = record.ext,
                    targetSizePx = 2048,
                    contentDescription = record.name,
                    modifier = Modifier.fillMaxSize().padding(12.dp).zoomable(
                        state = zoomableState,
                        onLongClick = { actionRecord = record },
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭预览",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${records.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    actionRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { actionRecord = null },
            title = { Text("表情操作") },
            text = { Text(record.name) },
            confirmButton = {
                TextButton(onClick = {
                    actionRecord = null
                    exportRecord = record
                    exportLauncher.launch(record.name)
                }) { Text("导出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { actionRecord = null }) { Text("取消") }
                    TextButton(onClick = {
                        actionRecord = null
                        shareImage(
                            context = context,
                            file = state.repository.imageFile(packName, record.name),
                            mimeType = imageMimeType(record.ext),
                        )
                    }) { Text("转发") }
                }
            },
        )
    }
}

private fun shareImage(context: android.content.Context, file: java.io.File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "转发表情"))
}

internal fun imageMimeType(extension: String): String = when (extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "image/*"
}
