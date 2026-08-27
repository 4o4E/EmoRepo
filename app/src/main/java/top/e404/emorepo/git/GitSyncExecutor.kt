package top.e404.emorepo.git

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.config.SyncPhase
import top.e404.emorepo.config.SyncStatus
import top.e404.emorepo.security.KeystoreTokenStore

class GitSyncExecutor(context: Context) {
    private val applicationContext = context.applicationContext
    private val settingsStore = SettingsStore(applicationContext)
    private val tokenStore = KeystoreTokenStore(applicationContext)
    private val gitService: GitRepositoryService = JGitRepositoryService()
    private val repositoryDirectory = File(applicationContext.filesDir, "repository")

    fun run(): GitSyncResult {
        val settings = settingsStore.load()
        require(settings.setupComplete && File(repositoryDirectory, ".git").isDirectory) {
            "仓库尚未完成配置"
        }
        val previous = settingsStore.loadSyncStatus()
        settingsStore.saveSyncStatus(previous.copy(phase = SyncPhase.RUNNING, lastError = null))
        val token = tokenStore.read()
        return try {
            gitService.sync(repositoryDirectory, settings, token).also {
                settingsStore.saveSyncStatus(
                    SyncStatus(
                        phase = SyncPhase.SUCCESS,
                        lastSuccessTime = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = sanitizeError(error, token)
            settingsStore.saveSyncStatus(
                previous.copy(phase = SyncPhase.ERROR, lastError = message),
            )
            throw GitSyncException(message, error)
        }
    }

    private fun sanitizeError(error: Exception, token: String?): String {
        var message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        if (!token.isNullOrBlank()) message = message.replace(token, "[已隐藏]")
        return message
    }
}

class GitSyncException(message: String, cause: Throwable) : Exception(message, cause)
