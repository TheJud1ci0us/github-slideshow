package com.ascolta.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ascolta.app.graph

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("discover", "Discover", Icons.Filled.Search),
    Tab("words", "Words", Icons.Filled.Translate),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

object Routes {
    fun podcast(id: Long?) = "podcast/${id ?: -1}"
    fun player(episodeId: Long) = "player/$episodeId"
    fun discover(query: String? = null) = if (query == null) "discover" else "discover?q=${Uri.encode(query)}"
}

@Composable
fun AscoltaRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ""
    val inPlayer = route.startsWith("player")

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!inPlayer) {
                Column {
                    MiniPlayer(onOpen = { id -> nav.navigate(Routes.player(id)) })
                    NavigationBar {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = route.substringBefore('?') == tab.route,
                                onClick = { nav.navigateToTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") {
                LibraryScreen(
                    openPodcast = { nav.navigate(Routes.podcast(it)) },
                    openPlayer = { nav.navigate(Routes.player(it)) },
                    openDiscover = { query -> nav.navigateToTab(Routes.discover(query)) },
                )
            }
            composable(
                "discover?q={q}",
                arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                DiscoverScreen(
                    initialQuery = entry.arguments?.getString("q"),
                    openPodcast = { nav.navigate(Routes.podcast(it)) },
                )
            }
            composable("words") { WordsScreen() }
            composable("settings") { SettingsScreen() }
            composable(
                "podcast/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments!!.getLong("id").takeIf { it >= 0 }
                PodcastScreen(
                    podcastId = id,
                    onBack = { nav.popBackStack() },
                    openPlayer = { nav.navigate(Routes.player(it)) },
                )
            }
            composable(
                "player/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
            ) { entry ->
                PlayerScreen(episodeId = entry.arguments!!.getLong("episodeId"), onBack = { nav.popBackStack() })
            }
        }
    }

    // A link shared from another app (Spotify, Apple Podcasts, a browser…).
    val context = LocalContext.current
    val shared by context.graph.pendingSharedText.collectAsState()
    shared?.let { text ->
        AddLinkDialog(
            initialText = text,
            onDismiss = { context.graph.pendingSharedText.value = null },
            openPodcast = {
                context.graph.pendingSharedText.value = null
                nav.navigate(Routes.podcast(it))
            },
            openSearch = {
                context.graph.pendingSharedText.value = null
                nav.navigateToTab(Routes.discover(it))
            },
        )
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = !route.contains('?')
    }
}
