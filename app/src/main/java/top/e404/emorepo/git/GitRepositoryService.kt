package top.e404.emorepo.git

import java.io.File
import top.e404.emorepo.config.AppSettings

data class GitSyncResult(
    val committed: Boolean,
    val warnings: List<String>,
)

interface GitRepositoryService {
    fun isValidRepository(repositoryDirectory: File): Boolean

    fun cloneRepository(remoteUrl: String, token: String?, repositoryDirectory: File)

    fun sync(repositoryDirectory: File, settings: AppSettings, token: String?): GitSyncResult
}
