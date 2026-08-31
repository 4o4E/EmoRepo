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
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.diagnostics.DiagnosticLogger

class GitSyncWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            GitSyncExecutor(applicationContext).run(runAttemptCount)
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

    companion object {
        private const val MAXIMUM_RETRIES = 5
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
            fields = mapOf("delaySeconds" to MODIFICATION_DEBOUNCE_SECONDS),
        )
        val request = oneTimeRequest(initialDelaySeconds = MODIFICATION_DEBOUNCE_SECONDS)
        WorkManager.getInstance(context).enqueueUniqueWork(
            MODIFICATION_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
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
        initialDelaySeconds: Long? = null,
        initialDelayMinutes: Long? = null,
    ) =
        OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply {
                if (initialDelaySeconds != null) {
                    setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                }
                if (initialDelayMinutes != null) {
                    setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
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
    private const val MODIFICATION_DEBOUNCE_SECONDS = 3L
    private const val BACKOFF_SECONDS = 30L
}
