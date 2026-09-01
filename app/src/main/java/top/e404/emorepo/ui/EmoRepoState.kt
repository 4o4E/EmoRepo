package top.e404.emorepo.ui

import android.content.Context
import android.app.Application
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.protocol.pack.PackIndexRecord
import top.e404.emorepo.config.SetupInput
import top.e404.emorepo.config.SyncStatus
import top.e404.emorepo.config.validated
import top.e404.emorepo.diagnostics.DiagnosticExporter
import top.e404.emorepo.diagnostics.DiagnosticLogger
import top.e404.emorepo.git.GitRepositoryService
import top.e404.emorepo.git.GitSyncExecutor
import top.e404.emorepo.git.GitSyncScheduler
import top.e404.emorepo.git.JGitRepositoryService
import top.e404.emorepo.ipc.EmoRepoIpcContract
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
    private val repositoryChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshRepositoryContent()
        }
    }
    private var initialReloadRequested = false

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

    init {
        context.contentResolver.registerContentObserver(
            EmoRepoIpcContract.REVISION_URI,
            false,
            repositoryChangeObserver,
        )
    }

    fun onForeground() {
        if (!initialReloadRequested) {
            initialReloadRequested = true
            reload()
        } else {
            refreshRepositoryContent()
        }
    }

    fun reload() {
        scope.launch {
            busy = true
            DiagnosticLogger.info("ui_state", "reload_started")
            settings = settingsStore.load()
            syncStatus = settingsStore.loadSyncStatus()
            repositoryReady = withContext(Dispatchers.IO) {
                gitService.isValidRepository(repositoryDirectory)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { if (setupRequired) emptyList() else repository.listPacks() }
            }
            result.onSuccess {
                packs = it
                DiagnosticLogger.info("ui_state", "reload_succeeded", fields = mapOf("packCount" to it.size))
            }.onFailure { error ->
                DiagnosticLogger.error("ui_state", "reload_failed", "读取仓库失败", error = error)
                message = error.message ?: "读取仓库失败"
            }
            busy = false
            if (!setupRequired) GitSyncScheduler.requestImmediate(context)
        }
    }

    private fun refreshRepositoryContent() {
        if (setupRequired) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.listPacks() } }
            result.onSuccess {
                packs = it
                DiagnosticLogger.info(
                    "ui_state",
                    "repository_change_applied",
                    fields = mapOf("packCount" to it.size),
                )
            }.onFailure { error ->
                DiagnosticLogger.error(
                    "ui_state",
                    "repository_change_refresh_failed",
                    "QQ 导入后刷新仓库失败",
                    error = error,
                )
            }
        }
    }

    fun close() {
        context.contentResolver.unregisterContentObserver(repositoryChangeObserver)
    }

    fun completeSetup(input: SetupInput, token: String) {
        scope.launch {
            busy = true
            val started = System.nanoTime()
            DiagnosticLogger.info("setup", "clone_started")
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
                DiagnosticLogger.info(
                    "setup",
                    "clone_succeeded",
                    fields = mapOf(
                        "packCount" to loadedPacks.size,
                        "durationMillis" to (System.nanoTime() - started) / 1_000_000L,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                DiagnosticLogger.error(
                    component = "setup",
                    event = "clone_failed",
                    message = "仓库克隆失败",
                    fields = mapOf("durationMillis" to (System.nanoTime() - started) / 1_000_000L),
                    error = error,
                    secrets = listOf(token),
                )
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
                DiagnosticLogger.info("settings", "settings_saved")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                DiagnosticLogger.error("settings", "settings_save_failed", error = error)
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
                DiagnosticLogger.info(
                    "settings",
                    if (token.isNullOrBlank()) "token_cleared" else "token_updated",
                )
                GitSyncScheduler.requestImmediate(context)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                DiagnosticLogger.error(
                    component = "settings",
                    event = "token_update_failed",
                    error = error,
                    secrets = listOfNotNull(token),
                )
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
        val byName = packs.associateBy { it.name }
        if (names.size != packs.size || names.toSet() != byName.keys) {
            message = "表情包排序数据已变化，请重试"
            return
        }
        updatePackArrangement(names.map { name ->
            val pack = byName.getValue(name)
            PackIndexRecord(pack.name, pack.collapsed)
        })
    }

    fun updatePackArrangement(records: List<PackIndexRecord>, onSuccess: () -> Unit = {}) {
        val current = packs.map { PackIndexRecord(it.name, it.collapsed) }
        if (records == current) {
            onSuccess()
            return
        }
        val byName = packs.associateBy { it.name }
        if (records.size != packs.size || records.map { it.name }.toSet() != byName.keys) {
            message = "表情包编辑数据已变化，请重试"
            return
        }
        packs = records.map { record -> byName.getValue(record.name).copy(collapsed = record.collapsed) }
        manage(
            operationName = "update_pack_arrangement",
            operation = {
                this.updatePackArrangement(records)
                "表情包顺序和折叠状态已更新"
            },
            onSuccess = onSuccess,
        )
    }

    fun togglePackCollapsed(name: String) {
        val current = packs.map { PackIndexRecord(it.name, it.collapsed) }
        if (current.none { it.name == name }) {
            message = "表情包不存在，请刷新后重试"
            return
        }
        updatePackArrangement(
            current.map { record ->
                if (record.name == name) record.copy(collapsed = !record.collapsed) else record
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

    fun dismissMessageAfter(delayMillis: Long) {
        require(delayMillis >= 0L) { "提示停留时间不能小于 0" }
        val expected = message ?: return
        scope.launch {
            delay(delayMillis)
            if (message == expected) message = null
        }
    }

    fun manage(
        operationName: String = "repository_mutation",
        operation: EmoticonRepository.() -> String,
        onSuccess: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        scope.launch {
            busy = true
            val started = System.nanoTime()
            DiagnosticLogger.info("repository", "operation_started", fields = mapOf("operation" to operationName))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RepositoryLocks.forRoot(repositoryDirectory).withLock { repository.operation() }
                }
            }
            message = result.fold(
                onSuccess = {
                    DiagnosticLogger.info(
                        "repository",
                        "operation_succeeded",
                        fields = mapOf(
                            "operation" to operationName,
                            "durationMillis" to (System.nanoTime() - started) / 1_000_000L,
                        ),
                    )
                    GitSyncScheduler.requestAfterModification(context)
                    it
                },
                onFailure = { error ->
                    DiagnosticLogger.error(
                        component = "repository",
                        event = "operation_failed",
                        message = "仓库操作失败",
                        fields = mapOf(
                            "operation" to operationName,
                            "durationMillis" to (System.nanoTime() - started) / 1_000_000L,
                        ),
                        error = error,
                    )
                    error.message ?: "操作失败"
                },
            )
            val loaded = withContext(Dispatchers.IO) { runCatching { repository.listPacks() } }
            loaded.onSuccess { packs = it }
                .onFailure { message = it.message ?: "刷新失败" }
            busy = false
            if (result.isSuccess) onSuccess()
            onComplete()
        }
    }

    fun deleteEmoticons(packName: String, md5Values: List<String>, onComplete: () -> Unit = {}) {
        manage(
            operationName = "delete_emoticons",
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

    fun renamePack(oldName: String, newName: String, onComplete: () -> Unit = {}) {
        manage(
            operationName = "rename_pack",
            operation = {
                renamePack(oldName, newName)
                "已重命名表情包：$oldName → $newName"
            },
            onComplete = onComplete,
        )
    }

    fun deletePack(name: String, onComplete: () -> Unit = {}) {
        manage(
            operationName = "delete_pack",
            operation = {
                val deleted = deletePack(name)
                "已删除表情包 ${deleted.name}，共 ${deleted.records.size} 张表情"
            },
            onComplete = onComplete,
        )
    }

    fun applyPackEdit(
        packName: String,
        originalMd5Order: List<String>,
        finalMd5Order: List<String>,
        onSuccess: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        manage(
            operationName = "apply_pack_edit",
            operation = {
                val edited = applyPackEdit(
                    packName = packName,
                    originalMd5Order = originalMd5Order,
                    finalMd5Order = finalMd5Order,
                    recentDeviceId = settings.deviceId,
                    recentMaximumRecords = settings.recentMaximumRecords,
                )
                "已保存 ${edited.records.size} 张表情"
            },
            onSuccess = onSuccess,
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
            operationName = "move_emoticons",
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
            operationName = "import_emoticons",
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

    fun exportDiagnostics(destination: Uri) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { DiagnosticExporter.export(context, destination) }
            }
            result.onSuccess { message = "诊断日志已导出" }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    DiagnosticLogger.error(
                        component = "ui",
                        event = "diagnostic_export_failed",
                        message = "设置页导出诊断日志失败",
                        error = error,
                    )
                    message = error.message ?: "导出诊断日志失败"
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

    override fun onCleared() {
        state.close()
        super.onCleared()
    }
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
