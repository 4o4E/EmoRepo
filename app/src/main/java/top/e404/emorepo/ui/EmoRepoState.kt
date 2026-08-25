package top.e404.emorepo.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.ImportCandidate

@Stable
class EmoRepoState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val repository = EmoticonRepository(File(context.filesDir, "repository"))

    var packs by mutableStateOf<List<EmoticonPack>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val repositoryConfigured: Boolean
        get() = File(context.filesDir, "repository/.git").isDirectory

    fun reload() {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { repository.listPacks() } }
            result.onSuccess { packs = it }
                .onFailure { message = it.message ?: "读取仓库失败" }
            busy = false
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun manage(
        operation: EmoticonRepository.() -> String,
        onComplete: () -> Unit = {},
    ) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { repository.operation() } }
            message = result.fold(onSuccess = { it }, onFailure = { it.message ?: "操作失败" })
            val loaded = withContext(Dispatchers.IO) { runCatching { repository.listPacks() } }
            loaded.onSuccess { packs = it }
                .onFailure { message = it.message ?: "刷新失败" }
            busy = false
            onComplete()
        }
    }

    fun importUris(packName: String, uris: List<Uri>, onComplete: () -> Unit = {}) {
        if (uris.isEmpty()) return
        manage(
            operation = {
                val candidates = uris.map { uri ->
                    val displayName = context.displayName(uri)
                    ImportCandidate(
                        sourceName = displayName,
                        bytes = context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取 $displayName" }
                            input.readBytes()
                        },
                    )
                }
                val result = import(packName, candidates)
                "导入 ${result.succeeded}，重复 ${result.duplicated}，失败 ${result.failed}"
            },
            onComplete = onComplete,
        )
    }
}

@Composable
fun rememberEmoRepoState(): EmoRepoState {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) { EmoRepoState(context, scope) }
}

private fun Context.displayName(uri: Uri): String {
    val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor.use {
        if (it != null && it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index)
        }
    }
    return uri.lastPathSegment ?: "image"
}
