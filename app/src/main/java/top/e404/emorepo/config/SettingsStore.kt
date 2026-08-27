package top.e404.emorepo.config

import android.content.Context
import androidx.core.content.edit
import top.e404.emorepo.repository.RecentUsageRepository

enum class SyncPhase {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR,
}

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSuccessTime: Long? = null,
    val lastError: String? = null,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?: RecentUsageRepository.generateDeviceId().also { generated ->
                preferences.edit(commit = true) { putString(KEY_DEVICE_ID, generated) }
            }
        return AppSettings(
            setupComplete = preferences.getBoolean(KEY_SETUP_COMPLETE, false),
            remoteUrl = preferences.getString(KEY_REMOTE_URL, "").orEmpty(),
            authorName = preferences.getString(KEY_AUTHOR_NAME, "").orEmpty(),
            authorEmail = preferences.getString(KEY_AUTHOR_EMAIL, "").orEmpty(),
            deviceId = deviceId,
            recentMaximumRecords = preferences.getInt(
                KEY_RECENT_MAXIMUM_RECORDS,
                DEFAULT_RECENT_MAXIMUM_RECORDS,
            ),
            recentSyncDelayMinutes = preferences.getInt(
                KEY_RECENT_SYNC_DELAY_MINUTES,
                DEFAULT_RECENT_SYNC_DELAY_MINUTES,
            ),
            backgroundSyncIntervalMinutes = preferences.getInt(
                KEY_BACKGROUND_SYNC_INTERVAL_MINUTES,
                DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES,
            ),
            commitMessage = preferences.getString(KEY_COMMIT_MESSAGE, DEFAULT_COMMIT_MESSAGE)
                ?: DEFAULT_COMMIT_MESSAGE,
        )
    }

    fun save(settings: AppSettings) {
        val valid = settings.validated()
        preferences.edit(commit = true) {
            putBoolean(KEY_SETUP_COMPLETE, valid.setupComplete)
            putString(KEY_REMOTE_URL, valid.remoteUrl)
            putString(KEY_AUTHOR_NAME, valid.authorName)
            putString(KEY_AUTHOR_EMAIL, valid.authorEmail)
            putString(KEY_DEVICE_ID, valid.deviceId)
            putInt(KEY_RECENT_MAXIMUM_RECORDS, valid.recentMaximumRecords)
            putInt(KEY_RECENT_SYNC_DELAY_MINUTES, valid.recentSyncDelayMinutes)
            putInt(KEY_BACKGROUND_SYNC_INTERVAL_MINUTES, valid.backgroundSyncIntervalMinutes)
            putString(KEY_COMMIT_MESSAGE, valid.commitMessage)
        }
    }

    fun loadSyncStatus(): SyncStatus = SyncStatus(
        phase = runCatching {
            SyncPhase.valueOf(preferences.getString(KEY_SYNC_PHASE, SyncPhase.IDLE.name).orEmpty())
        }.getOrDefault(SyncPhase.IDLE),
        lastSuccessTime = preferences.getLong(KEY_LAST_SUCCESS_TIME, 0L).takeIf { it > 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

    fun saveSyncStatus(status: SyncStatus) {
        preferences.edit(commit = true) {
            putString(KEY_SYNC_PHASE, status.phase.name)
            if (status.lastSuccessTime == null) remove(KEY_LAST_SUCCESS_TIME)
            else putLong(KEY_LAST_SUCCESS_TIME, status.lastSuccessTime)
            if (status.lastError == null) remove(KEY_LAST_ERROR)
            else putString(KEY_LAST_ERROR, status.lastError)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "emorepo_settings"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_AUTHOR_NAME = "author_name"
        private const val KEY_AUTHOR_EMAIL = "author_email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_RECENT_MAXIMUM_RECORDS = "recent_maximum_records"
        private const val KEY_RECENT_SYNC_DELAY_MINUTES = "recent_sync_delay_minutes"
        private const val KEY_BACKGROUND_SYNC_INTERVAL_MINUTES = "background_sync_interval_minutes"
        private const val KEY_COMMIT_MESSAGE = "commit_message"
        private const val KEY_SYNC_PHASE = "sync_phase"
        private const val KEY_LAST_SUCCESS_TIME = "last_success_time"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
