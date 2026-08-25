package top.e404.emorepo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val iconText: String,
)

private val topDestinations = listOf(
    TopDestination(PackListRoute, "表情列表", "▦"),
    TopDestination(AddEmoticonsRoute, "添加表情", "+"),
    TopDestination(SettingsRoute, "软件设置", "⚙"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmoRepoApp() {
    val state = rememberEmoRepoState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val packRoute = if (destination?.hasRoute<PackRoute>() == true) {
        backStackEntry?.toRoute<PackRoute>()
    } else {
        null
    }

    LaunchedEffect(Unit) { state.reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(packRoute?.packName ?: destination.title()) },
                navigationIcon = {
                    if (packRoute != null) {
                        TextButton(onClick = { navController.popBackStack() }) { Text("返回") }
                    }
                },
            )
        },
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
                        icon = { Text(item.iconText) },
                        label = { Text(item.label) },
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
                        onOpenPack = { navController.navigate(PackRoute(it)) },
                    )
                }
                composable<PackRoute> { entry ->
                    val route = entry.toRoute<PackRoute>()
                    PackManagerScreen(state = state, packName = route.packName)
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

private fun NavDestination?.title(): String = when {
    this?.hasRoute<AddEmoticonsRoute>() == true -> "添加表情"
    this?.hasRoute<SettingsRoute>() == true -> "软件设置"
    else -> "表情仓"
}

private fun NavDestination?.belongsTo(route: Any): Boolean = when (route) {
    PackListRoute -> this?.hasRoute<PackListRoute>() == true || this?.hasRoute<PackRoute>() == true
    AddEmoticonsRoute -> this?.hasRoute<AddEmoticonsRoute>() == true
    SettingsRoute -> this?.hasRoute<SettingsRoute>() == true
    else -> false
}
