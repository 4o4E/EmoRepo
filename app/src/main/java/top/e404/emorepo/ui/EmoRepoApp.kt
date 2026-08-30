package top.e404.emorepo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data object PackListRoute

@Serializable
data class PackRoute(val packName: String)

@Serializable
data object SettingsRoute

@Composable
fun EmoRepoApp() {
    val state = rememberEmoRepoState()
    val navController = rememberNavController()

    LaunchedEffect(Unit) { state.reload() }
    if (state.setupRequired) {
        Surface(Modifier.fillMaxSize()) { OnboardingScreen(state) }
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavHost(
                navController = navController,
                startDestination = PackListRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<PackListRoute> {
                    PackListScreen(
                        state = state,
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenPack = { packName ->
                            state.preloadPack(packName)
                            navController.navigate(PackRoute(packName))
                        },
                    )
                }
                composable<PackRoute> { entry ->
                    val route = entry.toRoute<PackRoute>()
                    PackManagerScreen(
                        state = state,
                        packName = route.packName,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(state, onBack = { navController.popBackStack() })
                }
            }

            if (state.busy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.message?.let { text ->
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
                    onClick = state::dismissMessage,
                ) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
