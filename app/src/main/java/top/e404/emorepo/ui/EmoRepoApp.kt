package top.e404.emorepo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import top.e404.emorepo.R

@Serializable
data object PackListRoute

@Serializable
data class PackRoute(val packName: String)

@Serializable
data object AddEmoticonsRoute

@Serializable
data object SettingsRoute

private data class TopDestination(
    val route: Any,
    val label: String,
    val iconText: String? = null,
    val iconResource: Int? = null,
)

private val topDestinations = listOf(
    TopDestination(PackListRoute, "表情列表", iconText = "▦"),
    TopDestination(AddEmoticonsRoute, "添加表情", iconText = "+"),
    TopDestination(SettingsRoute, "软件设置", iconResource = R.drawable.ic_settings),
)

@Composable
fun EmoRepoApp() {
    val state = rememberEmoRepoState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(Unit) { state.reload() }

    if (state.setupRequired) {
        Surface(Modifier.fillMaxSize()) { OnboardingScreen(state) }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                topDestinations.forEach { item ->
                    NavigationBarItem(
                        selected = destination.belongsTo(item.route),
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (item.iconResource != null) {
                                Icon(
                                    painter = painterResource(item.iconResource),
                                    contentDescription = null,
                                )
                            } else {
                                Text(item.iconText.orEmpty())
                            }
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = PackListRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<PackListRoute> {
                    PackListScreen(
                        state = state,
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
                composable<AddEmoticonsRoute> {
                    AddEmoticonsScreen(state)
                }
                composable<SettingsRoute> {
                    SettingsScreen(state)
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

private fun NavDestination?.belongsTo(route: Any): Boolean = when (route) {
    PackListRoute -> this?.hasRoute<PackListRoute>() == true || this?.hasRoute<PackRoute>() == true
    AddEmoticonsRoute -> this?.hasRoute<AddEmoticonsRoute>() == true
    SettingsRoute -> this?.hasRoute<SettingsRoute>() == true
    else -> false
}
