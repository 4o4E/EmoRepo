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

enum class MaintenancePhase {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCESS,
    SKIPPED,
    ERROR,
}

data class RepositoryMaintenanceStatus(
    val phase: MaintenancePhase = MaintenancePhase.IDLE,
    val lastRunTime: Long? = null,
    val beforeBytes: Long? = null,
    val afterBytes: Long? = null,
    val message: String? = null,
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
            qqPanelColumns = preferences.getInt(KEY_QQ_PANEL_COLUMNS, DEFAULT_QQ_PANEL_COLUMNS),
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
            putInt(KEY_QQ_PANEL_COLUMNS, valid.qqPanelColumns)
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

    fun loadMaintenanceStatus(): RepositoryMaintenanceStatus = RepositoryMaintenanceStatus(
        phase = runCatching {
            MaintenancePhase.valueOf(
                preferences.getString(KEY_MAINTENANCE_PHASE, MaintenancePhase.IDLE.name).orEmpty(),
            )
        }.getOrDefault(MaintenancePhase.IDLE),
        lastRunTime = preferences.getLong(KEY_MAINTENANCE_TIME, 0L).takeIf { it > 0L },
        beforeBytes = preferences.getLong(KEY_MAINTENANCE_BEFORE_BYTES, -1L).takeIf { it >= 0L },
        afterBytes = preferences.getLong(KEY_MAINTENANCE_AFTER_BYTES, -1L).takeIf { it >= 0L },
        message = preferences.getString(KEY_MAINTENANCE_MESSAGE, null),
    )

    fun saveMaintenanceStatus(status: RepositoryMaintenanceStatus) {
        preferences.edit(commit = true) {
            putString(KEY_MAINTENANCE_PHASE, status.phase.name)
            if (status.lastRunTime == null) remove(KEY_MAINTENANCE_TIME)
            else putLong(KEY_MAINTENANCE_TIME, status.lastRunTime)
            if (status.beforeBytes == null) remove(KEY_MAINTENANCE_BEFORE_BYTES)
            else putLong(KEY_MAINTENANCE_BEFORE_BYTES, status.beforeBytes)
            if (status.afterBytes == null) remove(KEY_MAINTENANCE_AFTER_BYTES)
            else putLong(KEY_MAINTENANCE_AFTER_BYTES, status.afterBytes)
            if (status.message == null) remove(KEY_MAINTENANCE_MESSAGE)
            else putString(KEY_MAINTENANCE_MESSAGE, status.message)
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
        private const val KEY_QQ_PANEL_COLUMNS = "qq_panel_columns"
        private const val KEY_SYNC_PHASE = "sync_phase"
        private const val KEY_LAST_SUCCESS_TIME = "last_success_time"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_MAINTENANCE_PHASE = "maintenance_phase"
        private const val KEY_MAINTENANCE_TIME = "maintenance_time"
        private const val KEY_MAINTENANCE_BEFORE_BYTES = "maintenance_before_bytes"
        private const val KEY_MAINTENANCE_AFTER_BYTES = "maintenance_after_bytes"
        private const val KEY_MAINTENANCE_MESSAGE = "maintenance_message"
    }
}
