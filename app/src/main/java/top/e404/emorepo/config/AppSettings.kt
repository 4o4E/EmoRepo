package top.e404.emorepo.config

import java.net.URI
import top.e404.emorepo.repository.RecentUsageRepository

data class AppSettings(
    val setupComplete: Boolean,
    val remoteUrl: String,
    val authorName: String,
    val authorEmail: String,
    val deviceId: String,
    val recentMaximumRecords: Int = DEFAULT_RECENT_MAXIMUM_RECORDS,
    val recentSyncDelayMinutes: Int = DEFAULT_RECENT_SYNC_DELAY_MINUTES,
    val backgroundSyncIntervalMinutes: Int = DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES,
    val commitMessage: String = DEFAULT_COMMIT_MESSAGE,
    val qqPanelColumns: Int = DEFAULT_QQ_PANEL_COLUMNS,
)

data class SetupInput(
    val remoteUrl: String,
    val authorName: String,
    val authorEmail: String,
    val deviceId: String,
)

fun SetupInput.validated(): SetupInput {
    val normalized = copy(
        remoteUrl = remoteUrl.trim(),
        authorName = authorName.trim(),
        authorEmail = authorEmail.trim(),
        deviceId = deviceId.trim(),
    )
    validateHttpsRemote(normalized.remoteUrl)
    require(normalized.authorName.isNotEmpty()) { "Git 作者名不能为空" }
    require(EMAIL_PATTERN.matches(normalized.authorEmail)) { "Git 作者邮箱格式不正确" }
    RecentUsageRepository.validateDeviceId(normalized.deviceId)
    return normalized
}

fun AppSettings.validated(): AppSettings {
    val setup = SetupInput(remoteUrl, authorName, authorEmail, deviceId).validated()
    require(recentMaximumRecords >= 0) { "最近使用数量不能小于 0" }
    require(recentSyncDelayMinutes >= 0) { "使用记录同步延迟不能小于 0" }
    require(
        backgroundSyncIntervalMinutes == 0 ||
            backgroundSyncIntervalMinutes >= MINIMUM_BACKGROUND_SYNC_INTERVAL_MINUTES,
    ) { "后台同步间隔必须为 0 或至少 15 分钟" }
    require(commitMessage.isNotBlank()) { "提交信息不能为空" }
    require(qqPanelColumns in MINIMUM_QQ_PANEL_COLUMNS..MAXIMUM_QQ_PANEL_COLUMNS) {
        "QQ 面板每行表情数量必须为 3 到 8"
    }
    return copy(
        remoteUrl = setup.remoteUrl,
        authorName = setup.authorName,
        authorEmail = setup.authorEmail,
        deviceId = setup.deviceId,
        commitMessage = commitMessage.trim(),
    )
}

fun validateHttpsRemote(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "远端地址必须是有效的 HTTPS URL"
    }
    require(uri.userInfo == null) { "远端地址不能包含用户名或密码" }
    require(uri.rawQuery == null) { "远端地址不能包含查询参数" }
    require(uri.rawFragment == null) { "远端地址不能包含片段" }
}

const val DEFAULT_RECENT_MAXIMUM_RECORDS = RecentUsageRepository.DEFAULT_MAXIMUM_RECORDS
const val DEFAULT_RECENT_SYNC_DELAY_MINUTES = 30
const val DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES = 30
const val MINIMUM_BACKGROUND_SYNC_INTERVAL_MINUTES = 15
const val DEFAULT_COMMIT_MESSAGE = "Update local emoticons"
const val DEFAULT_QQ_PANEL_COLUMNS = 4
const val MINIMUM_QQ_PANEL_COLUMNS = 3
const val MAXIMUM_QQ_PANEL_COLUMNS = 8

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+$")
