package top.e404.emorepo.git

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.e404.emorepo.config.SettingsStore

@RunWith(AndroidJUnit4::class)
class GitHubProxyIntegrationTest {
    @Test
    fun cloneCommitPushAndCloneAgainThroughDeviceProxy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tokenFile = File(context.filesDir, TOKEN_FILE)
        val token = tokenFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        require(token.isNotEmpty()) { "缺少一次性 GitHub 测试 Token" }
        val root = File(context.cacheDir, "github-proxy-integration").canonicalFile
        if (root.exists()) root.deleteRecursively()
        assertTrue(root.mkdirs())
        try {
            val service = JGitRepositoryService()
            val working = File(root, "working")
            service.cloneRepository(REMOTE_URL, token, working)
            val markerContent = "Android proxy integration passed at ${System.currentTimeMillis()}\n"
            File(working, MARKER_FILE).writeText(markerContent)
            val currentSettings = SettingsStore(context).load()
            val result = service.sync(
                working,
                currentSettings.copy(
                    setupComplete = true,
                    remoteUrl = REMOTE_URL,
                    commitMessage = "test: 验证 Android 代理推送",
                ),
                token,
            )
            assertTrue(result.committed)

            val verification = File(root, "verification")
            service.cloneRepository(REMOTE_URL, token, verification)
            assertEquals(markerContent, File(verification, MARKER_FILE).readText())
        } finally {
            root.deleteRecursively()
            tokenFile.delete()
        }
    }

    private companion object {
        const val REMOTE_URL = "https://github.com/4o4E/emorepo-integration-test.git"
        const val MARKER_FILE = "emorepo-android-proxy-test.txt"
        const val TOKEN_FILE = ".github-integration-token"
    }
}
