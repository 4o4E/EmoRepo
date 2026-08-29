package top.e404.emorepo.ui

import android.content.Context
import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.config.SetupInput
import top.e404.emorepo.config.SyncStatus
import top.e404.emorepo.config.validated
import top.e404.emorepo.git.GitRepositoryService
import top.e404.emorepo.git.GitSyncExecutor
import top.e404.emorepo.git.GitSyncScheduler
import top.e404.emorepo.git.JGitRepositoryService
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.ImportCandidate
import top.e404.emorepo.repository.ImportLimits
import top.e404.emorepo.repository.ManagementBatchResult
import top.e404.emorepo.repository.ManagementItemResult
import top.e404.emorepo.repository.ManagementStatus
import top.e404.emorepo.repository.RecentUsageRepository
import top.e404.emorepo.repository.RepositoryLocks
import top.e404.emorepo.repository.readImportBytes
import top.e404.emorepo.security.KeystoreTokenStore

@Stable
class EmoRepoState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val repositoryDirectory = File(context.filesDir, "repository")
    private val settingsStore = SettingsStore(context)
    private val tokenStore = KeystoreTokenStore(context)
    private val gitService: GitRepositoryService = JGitRepositoryService()
    private val uiPreferences = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    val repository = EmoticonRepository(repositoryDirectory)

    var packs by mutableStateOf<List<EmoticonPack>>(emptyList())
        private set
    var settings by mutableStateOf(settingsStore.load())
        private set
    var syncStatus by mutableStateOf(settingsStore.loadSyncStatus())
        private set
    var packLayout by mutableStateOf(
        runCatching {
            PackLayout.valueOf(uiPreferences.getString("pack_layout", PackLayout.LIST.name).orEmpty())
        }.getOrDefault(PackLayout.LIST),
    )
        private set
    private var repositoryReady by mutableStateOf(gitService.isValidRepository(repositoryDirectory))
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val repositoryConfigured: Boolean
        get() = repositoryReady

    val setupRequired: Boolean
        get() = !settings.setupComplete || !repositoryConfigured

    fun reload() {
        scope.launch {
            busy = true
            settings = settingsStore.load()
            syncStatus = settingsStore.loadSyncStatus()
            repositoryReady = withContext(Dispatchers.IO) {
                gitService.isValidRepository(repositoryDirectory)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { if (setupRequired) emptyList() else repository.listPacks() }
            }
            result.onSuccess { packs = it }
                .onFailure { message = it.message ?: "读取仓库失败" }
            busy = false
            if (!setupRequired) GitSyncScheduler.requestImmediate(context)
        }
    }

    fun completeSetup(input: SetupInput, token: String) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val valid = input.validated()
                    tokenStore.save(token.takeIf { it.isNotBlank() })
                    val hadRepositoryContent = repositoryDirectory.list().orEmpty().isNotEmpty()
                    try {
                        gitService.cloneRepository(
                            valid.remoteUrl,
                            token.takeIf { it.isNotBlank() },
                            repositoryDirectory,
                        )
                        val loadedPacks = repository.listPacks()
                        val configured = AppSettings(
                            setupComplete = true,
                            remoteUrl = valid.remoteUrl,
                            authorName = valid.authorName,
                            authorEmail = valid.authorEmail,
                            deviceId = valid.deviceId,
                        ).validated()
                        settingsStore.save(configured)
                        configured to loadedPacks
                    } catch (error: Exception) {
                        if (!hadRepositoryContent) repositoryDirectory.deleteRecursively()
                        throw error
                    }
                }
            }
            result.onSuccess { (configured, loadedPacks) ->
                settings = configured
                repositoryReady = true
                GitSyncScheduler.updatePeriodic(context, configured)
                packs = loadedPacks
                message = "仓库克隆完成"
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = error.message ?: "仓库克隆失败"
            }
            busy = false
        }
    }

    fun updateSettings(updated: AppSettings) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RepositoryLocks.forRoot(repositoryDirectory).withLock {
                        val valid = updated.copy(
                            setupComplete = true,
                            remoteUrl = settings.remoteUrl,
                        ).validated()
                        if (valid.deviceId != settings.deviceId) {
                            RecentUsageRepository(
                                repositoryDirectory,
                                settings.deviceId,
                                settings.recentMaximumRecords,
                            ).renameDevice(valid.deviceId)
                        }
                        RecentUsageRepository(
                            repositoryDirectory,
                            valid.deviceId,
                            valid.recentMaximumRecords,
                        ).trimCurrentDevice()
                        settingsStore.save(valid)
                        valid
                    }
                }
            }
            result.onSuccess { valid ->
                val recentFilesMayHaveChanged = valid.deviceId != settings.deviceId ||
                    valid.recentMaximumRecords != settings.recentMaximumRecords
                settings = valid
                GitSyncScheduler.updatePeriodic(context, valid)
                if (recentFilesMayHaveChanged) GitSyncScheduler.requestAfterModification(context)
                message = "设置已保存"
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = error.message ?: "保存设置失败"
            }
            busy = false
        }
    }

    fun updateToken(token: String?) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { tokenStore.save(token) } }
            result.onSuccess {
                message = if (token.isNullOrBlank()) "Token 已清除" else "Token 已更新"
                GitSyncScheduler.requestImmediate(context)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = error.message ?: "更新 Token 失败"
            }
            busy = false
        }
    }

    fun syncNow() {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { GitSyncExecutor(context).run() } }
            syncStatus = settingsStore.loadSyncStatus()
            result.onSuccess { outcome ->
                packs = withContext(Dispatchers.IO) { repository.listPacks() }
                message = if (outcome.warnings.isEmpty()) "同步完成" else {
                    "同步完成，${outcome.warnings.size} 条合并警告"
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = error.message ?: "同步失败"
            }
            busy = false
        }
    }

    fun refreshSyncStatus() {
        syncStatus = settingsStore.loadSyncStatus()
    }

    fun updatePackLayout(layout: PackLayout) {
        packLayout = layout
        uiPreferences.edit { putString("pack_layout", layout.name) }
    }

    fun reorderPacks(names: List<String>) {
        if (names == packs.map { it.name }) return
        val byName = packs.associateBy { it.name }
        if (names.size != packs.size || names.toSet() != byName.keys) {
            message = "表情包排序数据已变化，请重试"
            return
        }
        packs = names.map(byName::getValue)
        manage(
            operation = {
                this.reorderPacks(names)
                "表情包顺序已更新"
            },
        )
    }

    fun preloadPack(packName: String) {
        val pack = packs.firstOrNull { it.name == packName } ?: return
        scope.launch {
            preloadPackPreviews(context, repository, pack)
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RepositoryLocks.forRoot(repositoryDirectory).withLock { repository.operation() }
                }
            }
            message = result.fold(
                onSuccess = {
                    GitSyncScheduler.requestAfterModification(context)
                    it
                },
                onFailure = { it.message ?: "操作失败" },
            )
            val loaded = withContext(Dispatchers.IO) { runCatching { repository.listPacks() } }
            loaded.onSuccess { packs = it }
                .onFailure { message = it.message ?: "刷新失败" }
            busy = false
            onComplete()
        }
    }

    fun deleteEmoticons(packName: String, md5Values: List<String>, onComplete: () -> Unit = {}) {
        manage(
            operation = {
                val result = delete(packName, md5Values)
                val recent = recentUsageRepository()
                result.items.filter { it.status == ManagementStatus.SUCCESS }.forEach { item ->
                    item.record?.let { record -> recent.remove(packName, record.name) }
                }
                "删除 ${result.succeeded}，失败 ${result.failed}"
            },
            onComplete = onComplete,
        )
    }

    fun moveEmoticons(
        sourcePackName: String,
        targetPackName: String,
        md5Values: List<String>,
        onComplete: () -> Unit = {},
    ) {
        manage(
            operation = {
                val sourceNames = getPack(sourcePackName).records.associate { it.md5 to it.name }
                val result = move(sourcePackName, targetPackName, md5Values)
                val recent = recentUsageRepository()
                result.items.filter { it.status == ManagementStatus.SUCCESS }.forEach { item ->
                    val sourceName = sourceNames[item.source]
                    val targetName = item.record?.name
                    if (sourceName != null && targetName != null) {
                        recent.move(sourcePackName, sourceName, targetPackName, targetName)
                    }
                }
                val deduplicated = result.items.count { it.deduplicated }
                "移动 ${result.succeeded}，去重 $deduplicated，失败 ${result.failed}"
            },
            onComplete = onComplete,
        )
    }

    fun importUris(packName: String, uris: List<Uri>, onComplete: () -> Unit = {}) {
        if (uris.isEmpty()) return
        manage(
            operation = {
                require(uris.size <= ImportLimits.MAXIMUM_ITEMS) {
                    "单次最多导入 ${ImportLimits.MAXIMUM_ITEMS} 张图片"
                }
                var remainingBytes = ImportLimits.MAXIMUM_BATCH_BYTES
                val items = uris.map { uri ->
                    val displayName = context.displayName(uri)
                    runCatching {
                        val imported = context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取 $displayName" }
                            input.readImportBytes(remainingBytes)
                        }
                        remainingBytes -= imported.size
                        import(packName, listOf(ImportCandidate(displayName, imported.bytes))).items.single()
                    }.getOrElse { error ->
                        ManagementItemResult(
                            source = displayName,
                            status = ManagementStatus.FAILED,
                            message = error.message,
                        )
                    }
                }
                val result = ManagementBatchResult(items)
                "导入 ${result.succeeded}，重复 ${result.duplicated}，失败 ${result.failed}"
            },
            onComplete = onComplete,
        )
    }

    fun exportImage(packName: String, recordName: String, destination: Uri) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = repository.imageFile(packName, recordName)
                    require(source.isFile) { "表情文件不存在" }
                    context.contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output) { "无法打开导出位置" }
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
            result.onSuccess { message = "已导出 $recordName" }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    message = error.message ?: "导出失败"
                }
            busy = false
        }
    }

    private fun recentUsageRepository(): RecentUsageRepository = RecentUsageRepository(
        repositoryDirectory,
        settings.deviceId,
        settings.recentMaximumRecords,
    )
}

@Composable
fun rememberEmoRepoState(): EmoRepoState {
    return viewModel<EmoRepoStateViewModel>().state
}

class EmoRepoStateViewModel(application: Application) : AndroidViewModel(application) {
    val state = EmoRepoState(application.applicationContext, viewModelScope)
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
