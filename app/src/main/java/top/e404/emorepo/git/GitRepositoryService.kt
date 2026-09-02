package top.e404.emorepo.git

import java.io.File
import top.e404.emorepo.config.AppSettings

data class GitSyncResult(
    val committed: Boolean,
    val warnings: List<String>,
)

data class GitMaintenanceResult(
    val optimized: Boolean,
    val before: RepositoryStorageStats,
    val after: RepositoryStorageStats,
    val message: String,
)

enum class GitSyncStage(val displayName: String) {
    PRECHECK("仓库预检"),
    STATUS("检查本地变更"),
    STAGE("暂存"),
    COMMIT("提交"),
    FETCH("拉取远端"),
    REBASE("变基"),
    VALIDATE("协议校验"),
    PUSH("推送"),
}

enum class GitSyncStageOutcome { STARTED, SUCCEEDED, SKIPPED, WARNING, FAILED }

data class GitSyncStageEvent(
    val stage: GitSyncStage,
    val outcome: GitSyncStageOutcome,
    val durationMillis: Long? = null,
    val fields: Map<String, String> = emptyMap(),
    val error: Throwable? = null,
)

fun interface GitSyncObserver {
    fun onEvent(event: GitSyncStageEvent)

    companion object {
        val NONE = GitSyncObserver { }
    }
}

interface GitRepositoryService {
    fun isValidRepository(repositoryDirectory: File): Boolean

    fun cloneRepository(remoteUrl: String, token: String?, repositoryDirectory: File)

    fun sync(
        repositoryDirectory: File,
        settings: AppSettings,
        token: String?,
        observer: GitSyncObserver = GitSyncObserver.NONE,
    ): GitSyncResult

    fun inspectStorage(repositoryDirectory: File): RepositoryStorageStats

    fun optimizeLocalHistory(
        repositoryDirectory: File,
        token: String?,
    ): GitMaintenanceResult
}
