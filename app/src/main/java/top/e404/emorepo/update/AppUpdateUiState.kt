package top.e404.emorepo.update

data class AppUpdateUiState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val latestVersionName: String? = null,
    val progressPercent: Int? = null,
    val downloadEnabled: Boolean = false,
    val installEnabled: Boolean = false,
    val message: String = "点击检查 GitHub 最新正式版。",
)

enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    LATEST,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR,
}
