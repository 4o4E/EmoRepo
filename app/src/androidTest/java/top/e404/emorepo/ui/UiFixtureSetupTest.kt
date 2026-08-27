package top.e404.emorepo.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.config.SettingsStore

@RunWith(AndroidJUnit4::class)
class UiFixtureSetupTest {
    @Test
    fun configureExistingUiFixture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(File(context.filesDir, "repository/.git").isDirectory)
        val current = SettingsStore(context).load()
        SettingsStore(context).save(
            AppSettings(
                setupComplete = true,
                remoteUrl = "https://github.com/4o4E/emorepo-integration-test.git",
                authorName = "EmoRepo Test",
                authorEmail = "emorepo-test@example.com",
                deviceId = current.deviceId,
                backgroundSyncIntervalMinutes = 0,
                commitMessage = "test: 验证表情包拖动排序",
            ),
        )
    }
}
