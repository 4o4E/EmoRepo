package top.e404.emorepo.git

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.diagnostics.DiagnosticLogger

class GitSyncWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val modificationStore = if (inputData.getBoolean(MODIFICATION_INPUT_KEY, false)) {
                ModificationSyncStore(applicationContext)
            } else {
                null
            }
            val generation = modificationStore?.let { awaitModificationGeneration(it) }
            if (modificationStore != null && generation == null) return@withContext Result.success()
            GitSyncExecutor(applicationContext).run(runAttemptCount)
            if (generation != null) modificationStore?.markSynced(generation)
            GitMaintenanceScheduler.requestAutomaticIfNeeded(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retry = runAttemptCount < MAXIMUM_RETRIES
            DiagnosticLogger.warn(
                component = "git_sync_worker",
                event = if (retry) "retry_scheduled" else "retry_exhausted",
                fields = mapOf("attempt" to runAttemptCount, "willRetry" to retry),
                message = error.message,
            )
            if (retry) Result.retry() else Result.failure()
        }
    }

    private suspend fun awaitModificationGeneration(store: ModificationSyncStore): Long? {
        while (true) {
            val state = store.load()
            if (!state.hasPendingChanges) return null
            val remainingMillis = state.remainingDelayMillis(System.currentTimeMillis())
            if (remainingMillis == 0L) return state.generation
            delay(remainingMillis)
        }
    }

    companion object {
        private const val MAXIMUM_RETRIES = 5
        internal const val MODIFICATION_INPUT_KEY = "modification_triggered"
    }
}

object GitSyncScheduler {
    fun requestImmediate(context: Context) {
        DiagnosticLogger.info("git_sync_scheduler", "immediate_requested")
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest(),
        )
    }

    fun requestAfterModification(context: Context) {
        DiagnosticLogger.info(
            "git_sync_scheduler",
            "modification_requested",
            fields = mapOf("delaySeconds" to ModificationSyncState.DEBOUNCE_MILLIS / 1_000L),
        )
        val appContext = context.applicationContext
        ModificationSyncStore(appContext).recordModification(System.currentTimeMillis())
        modificationScheduler.execute {
            runCatching {
                val manager = WorkManager.getInstance(appContext)
                // 调度查询和入队串行；不能替换一个正在进入或执行 JGit 的 Worker。
                val work = manager.getWorkInfosForUniqueWork(MODIFICATION_WORK_NAME).get()
                if (work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
                    return@runCatching
                }
                val state = ModificationSyncStore(appContext).load()
                if (!state.hasPendingChanges) return@runCatching
                manager.enqueueUniqueWork(
                    MODIFICATION_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    oneTimeRequest(
                        initialDelayMillis = state.remainingDelayMillis(System.currentTimeMillis()),
                        modificationTriggered = true,
                    ),
                ).result.get()
            }.onFailure { error ->
                DiagnosticLogger.error(
                    component = "git_sync_scheduler",
                    event = "modification_schedule_failed",
                    message = error.message,
                    error = error,
                )
            }
        }
    }

    fun requestRecentUsage(context: Context, delayMinutes: Int) {
        require(delayMinutes >= 0) { "使用记录同步延迟不能小于 0" }
        DiagnosticLogger.info(
            "git_sync_scheduler",
            "recent_usage_requested",
            fields = mapOf("delayMinutes" to delayMinutes),
        )
        val request = oneTimeRequest(initialDelayMinutes = delayMinutes.toLong())
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECENT_USAGE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun updatePeriodic(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.setupComplete || settings.backgroundSyncIntervalMinutes == 0) {
            DiagnosticLogger.info("git_sync_scheduler", "periodic_disabled")
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(
            settings.backgroundSyncIntervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        DiagnosticLogger.info(
            "git_sync_scheduler",
            "periodic_updated",
            fields = mapOf("intervalMinutes" to settings.backgroundSyncIntervalMinutes),
        )
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun oneTimeRequest(
        initialDelayMillis: Long? = null,
        initialDelayMinutes: Long? = null,
        modificationTriggered: Boolean = false,
    ) =
        OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply {
                if (initialDelayMillis != null) {
                    setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                }
                if (initialDelayMinutes != null) {
                    setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                }
                if (modificationTriggered) {
                    setInputData(workDataOf(GitSyncWorker.MODIFICATION_INPUT_KEY to true))
                }
            }
            .build()

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val IMMEDIATE_WORK_NAME = "emorepo-git-sync-immediate"
    private const val MODIFICATION_WORK_NAME = "emorepo-git-sync-modification"
    private const val RECENT_USAGE_WORK_NAME = "emorepo-git-sync-recent-usage"
    private const val PERIODIC_WORK_NAME = "emorepo-git-sync-periodic"
    private val modificationScheduler = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-Modification-Scheduler").apply { isDaemon = true }
    }
    private const val BACKOFF_SECONDS = 30L
}
