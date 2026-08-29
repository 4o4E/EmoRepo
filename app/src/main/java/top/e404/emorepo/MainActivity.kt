package top.e404.emorepo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import top.e404.emorepo.ui.EmoRepoApp
import top.e404.emorepo.ui.EmoRepoTheme

class MainActivity : ComponentActivity() {
    private var settingsRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            EmoRepoTheme {
                EmoRepoApp(settingsRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == MainActivityActions.OPEN_SETTINGS) settingsRequest += 1
    }
}
