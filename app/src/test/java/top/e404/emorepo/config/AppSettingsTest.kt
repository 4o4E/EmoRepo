package top.e404.emorepo.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `accepts a public HTTPS repository without credentials in settings`() {
        val valid = settings().validated()
        assertEquals("https://example.com/owner/repository.git", valid.remoteUrl)
        assertEquals(30, valid.recentMaximumRecords)
    }

    @Test
    fun `rejects credentials and queries embedded in remote URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            settings(remoteUrl = "https://token@example.com/repository.git").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings(remoteUrl = "https://example.com/repository.git?token=secret").validated()
        }
    }

    @Test
    fun `requires effective background interval and identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            settings(backgroundInterval = 10).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings(authorEmail = "invalid").validated()
        }
    }

    private fun settings(
        remoteUrl: String = "https://example.com/owner/repository.git",
        authorEmail: String = "user@example.com",
        backgroundInterval: Int = 30,
    ) = AppSettings(
        setupComplete = true,
        remoteUrl = remoteUrl,
        authorName = "User",
        authorEmail = authorEmail,
        deviceId = "android-test1234",
        backgroundSyncIntervalMinutes = backgroundInterval,
    )
}
