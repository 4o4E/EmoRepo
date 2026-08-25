package top.e404.emorepo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import top.e404.emorepo.ui.EmoRepoApp
import top.e404.emorepo.ui.EmoRepoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmoRepoTheme {
                EmoRepoApp()
            }
        }
    }
}
