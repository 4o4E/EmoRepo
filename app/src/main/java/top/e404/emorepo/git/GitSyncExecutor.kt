package top.e404.emorepo.git

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import java.util.UUID
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.config.SyncPhase
import top.e404.emorepo.config.SyncStatus
import top.e404.emorepo.diagnostics.DiagnosticLogLevel
import top.e404.emorepo.diagnostics.DiagnosticLogger
import top.e404.emorepo.diagnostics.DiagnosticSanitizer
import top.e404.emorepo.security.KeystoreTokenStore

class GitSyncExecutor(context: Context) {
    private val applicationContext = context.applicationContext
    private val settingsStore = SettingsStore(applicationContext)
    private val tokenStore = KeystoreTokenStore(applicationContext)
    private val gitService: GitRepositoryService = JGitRepositoryService()
    private val repositoryDirectory = File(applicationContext.filesDir, "repository")

    fun run(attempt: Int = 0): GitSyncResult {
        val runId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        val settings = settingsStore.load()
        require(settings.setupComplete && File(repositoryDirectory, ".git").isDirectory) {
            "仓库尚未完成配置"
        }
        val previous = settingsStore.loadSyncStatus()
        settingsStore.saveSyncStatus(previous.copy(phase = SyncPhase.RUNNING, lastError = null))
        val token = tokenStore.read()
        var currentStage = GitSyncStage.PRECHECK
        val observer = GitSyncObserver { event ->
            currentStage = event.stage
            val level = when (event.outcome) {
                GitSyncStageOutcome.WARNING -> DiagnosticLogLevel.WARN
                GitSyncStageOutcome.FAILED -> DiagnosticLogLevel.ERROR
                else -> DiagnosticLogLevel.INFO
            }
            val fields = buildMap<String, Any?> {
                put("runId", runId)
                put("stage", event.stage.name)
                put("outcome", event.outcome.name)
                event.durationMillis?.let { put("durationMillis", it) }
                putAll(event.fields)
            }
            when (level) {
                DiagnosticLogLevel.ERROR -> DiagnosticLogger.error(
                    component = "git_sync",
                    event = "stage_failed",
                    message = event.stage.displayName,
                    fields = fields,
                    error = event.error,
                    secrets = listOfNotNull(token),
                )
                DiagnosticLogLevel.WARN -> DiagnosticLogger.warn(
                    component = "git_sync",
                    event = "stage_warning",
                    message = event.stage.displayName,
                    fields = fields,
                    error = event.error,
                    secrets = listOfNotNull(token),
                )
                else -> DiagnosticLogger.info(
                    component = "git_sync",
                    event = "stage",
                    message = event.stage.displayName,
                    fields = fields,
                )
            }
        }
        DiagnosticLogger.info(
            component = "git_sync",
            event = "sync_started",
            fields = mapOf("runId" to runId, "attempt" to attempt),
        )
        return try {
            gitService.sync(repositoryDirectory, settings, token, observer).also { result ->
                settingsStore.saveSyncStatus(
                    SyncStatus(
                        phase = SyncPhase.SUCCESS,
                        lastSuccessTime = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
                DiagnosticLogger.info(
                    component = "git_sync",
                    event = "sync_succeeded",
                    fields = mapOf(
                        "runId" to runId,
                        "attempt" to attempt,
                        "committed" to result.committed,
                        "warningCount" to result.warnings.size,
                        "durationMillis" to (System.nanoTime() - started) / 1_000_000L,
                    ),
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = "${currentStage.displayName}失败：" +
                DiagnosticSanitizer.mostSpecificMessage(error, listOfNotNull(token))
            settingsStore.saveSyncStatus(
                previous.copy(phase = SyncPhase.ERROR, lastError = message),
            )
            DiagnosticLogger.error(
                component = "git_sync",
                event = "sync_failed",
                message = message,
                fields = mapOf(
                    "runId" to runId,
                    "attempt" to attempt,
                    "stage" to currentStage.name,
                    "durationMillis" to (System.nanoTime() - started) / 1_000_000L,
                ),
                error = error,
                secrets = listOfNotNull(token),
            )
            throw GitSyncException(message, error)
        }
    }
}

class GitSyncException(message: String, cause: Throwable) : Exception(message, cause)
