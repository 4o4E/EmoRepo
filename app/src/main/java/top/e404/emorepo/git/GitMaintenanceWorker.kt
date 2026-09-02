package top.e404.emorepo.git

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.e404.emorepo.config.MaintenancePhase
import top.e404.emorepo.config.RepositoryMaintenanceStatus
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.diagnostics.DiagnosticLogger
import top.e404.emorepo.diagnostics.DiagnosticSanitizer
import top.e404.emorepo.security.KeystoreTokenStore

class GitMaintenanceWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = SettingsStore(applicationContext)
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        store.saveMaintenanceStatus(
            store.loadMaintenanceStatus().copy(
                phase = MaintenancePhase.RUNNING,
                message = "正在同步并优化 Android 本地 Git 历史",
            ),
        )
        DiagnosticLogger.info(
            "git_maintenance",
            "maintenance_started",
            fields = mapOf("manual" to manual, "attempt" to runAttemptCount),
        )
        try {
            GitSyncExecutor(applicationContext).run(runAttemptCount)
            val root = File(applicationContext.filesDir, "repository")
            val token = KeystoreTokenStore(applicationContext).read()
            val outcome = JGitRepositoryService().optimizeLocalHistory(root, token)
            val now = System.currentTimeMillis()
            store.saveMaintenanceStatus(
                RepositoryMaintenanceStatus(
                    phase = if (outcome.optimized) MaintenancePhase.SUCCESS else MaintenancePhase.SKIPPED,
                    lastRunTime = now,
                    beforeBytes = outcome.before.totalBytes,
                    afterBytes = outcome.after.totalBytes,
                    message = outcome.message,
                ),
            )
            DiagnosticLogger.info(
                "git_maintenance",
                if (outcome.optimized) "maintenance_succeeded" else "maintenance_skipped",
                fields = mapOf(
                    "beforeBytes" to outcome.before.totalBytes,
                    "afterBytes" to outcome.after.totalBytes,
                    "gitBytes" to outcome.after.gitBytes,
                    "shallow" to outcome.after.shallow,
                ),
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retry = runAttemptCount < MAXIMUM_RETRIES
            val message = DiagnosticSanitizer.mostSpecificMessage(error)
            store.saveMaintenanceStatus(
                store.loadMaintenanceStatus().copy(
                    phase = if (retry) MaintenancePhase.QUEUED else MaintenancePhase.ERROR,
                    lastRunTime = System.currentTimeMillis(),
                    message = if (retry) "优化失败，等待后台重试：$message" else "优化失败：$message",
                ),
            )
            DiagnosticLogger.warn(
                "git_maintenance",
                if (retry) "maintenance_retry_scheduled" else "maintenance_failed",
                fields = mapOf("attempt" to runAttemptCount, "willRetry" to retry),
                message = message,
                error = error,
            )
            if (retry) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val INPUT_MANUAL = "manual"
        private const val MAXIMUM_RETRIES = 3
    }
}

object GitMaintenanceScheduler {
    fun requestAutomaticIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val root = File(appContext.filesDir, "repository")
        val stats = runCatching { inspectRepositoryStorage(root) }
            .onFailure { error ->
                DiagnosticLogger.warn("git_maintenance", "storage_inspection_failed", error = error)
            }
            .getOrNull() ?: return
        if (!stats.needsAutomaticMaintenance) return
        SettingsStore(appContext).saveMaintenanceStatus(
            RepositoryMaintenanceStatus(
                phase = MaintenancePhase.QUEUED,
                beforeBytes = stats.totalBytes,
                message = "已安排充电时自动优化 Android 本地 Git 历史",
            ),
        )
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            AUTOMATIC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request(manual = false),
        )
    }

    fun requestManual(context: Context) {
        val appContext = context.applicationContext
        SettingsStore(appContext).saveMaintenanceStatus(
            SettingsStore(appContext).loadMaintenanceStatus().copy(
                phase = MaintenancePhase.QUEUED,
                message = "已安排后台同步和仓库优化",
            ),
        )
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request(manual = true),
        )
    }

    private fun request(manual: Boolean) =
        OneTimeWorkRequestBuilder<GitMaintenanceWorker>()
            .setInputData(Data.Builder().putBoolean(GitMaintenanceWorker.INPUT_MANUAL, manual).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .apply {
                        if (!manual) {
                            setRequiresCharging(true)
                            setRequiresBatteryNotLow(true)
                        }
                    }
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    private const val AUTOMATIC_WORK_NAME = "emorepo-git-maintenance-automatic"
    private const val MANUAL_WORK_NAME = "emorepo-git-maintenance-manual"
    private const val BACKOFF_SECONDS = 30L
}
