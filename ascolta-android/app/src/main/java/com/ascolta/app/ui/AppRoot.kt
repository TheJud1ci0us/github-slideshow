package com.ascolta.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ascolta.app.R
import com.ascolta.app.graph
import androidx.compose.ui.res.stringResource

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

/** Home · Discover · Words · Profile (progress and settings live under Profile). */
private val TABS = listOf(
    Tab("library", R.string.tab_home, Icons.Outlined.Home),
    Tab("discover", R.string.tab_discover, Icons.Outlined.Explore),
    Tab("words", R.string.tab_words, Icons.Outlined.Style),
    Tab("profile", R.string.tab_profile, Icons.Outlined.Person),
)

/** Which tab a screen belongs to, for the highlighted item in the bar. */
private fun tabOf(route: String): String = when {
    route.startsWith("settings") || route == "progress" || route == "personalize" || route == "profile" -> "profile"
    route == "review" -> "words"
    else -> route.substringBefore('?')
}

/** The main page of a tab (not a page opened from it, like a podcast). */
private fun isTabRoot(route: String?): Boolean =
    route == "library" || route == "words" || route == "profile" || route?.startsWith("discover") == true

/**
 * The tab the current screen was opened from: the last tab page on the back stack (a podcast
 * opened from Discover belongs to Discover).
 */
private fun NavHostController.currentTab(): String? =
    currentBackStack.value.lastOrNull { isTabRoot(it.destination.route) }?.destination?.route?.let(::tabOf)

object Routes {
    fun podcast(id: Long?) = "podcast/${id ?: -1}"
    fun player(episodeId: Long) = "player/$episodeId"
    fun discover(query: String? = null) = if (query == null) "discover" else "discover?q=${Uri.encode(query)}"
}

@Composable
private fun context0() = LocalContext.current

@Composable
fun AscoltaRoot() {
    // First launch: choose the languages being learned.
    val onboarded by LocalContext.current.graph.settings.onboarded.flow.collectAsState()
    if (!onboarded) {
        // A Surface sets the light text colour (without it, text defaults to black).
        androidx.compose.material3.Surface(
            Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) { OnboardingScreen(onDone = {}) }
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ""
    val inPlayer = route.startsWith("player") || route == "review"

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!inPlayer) {
                Column(Modifier.navigationBarsPadding()) {
                    MiniPlayer(onOpen = { id -> nav.navigate(Routes.player(id)) })
                    FloatingNavBar(
                        items = TABS.map { NavItem(it.route, stringResource(it.label), it.icon) },
                        current = remember(backStack) { nav.currentTab() } ?: tabOf(route),
                        onSelect = { nav.navigateToTab(it) },
                    )
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
            composable("words") { WordsScreen(openReview = { nav.navigate("review") }) }
            composable("review") { ReviewScreen(onBack = { nav.popBackStack() }) }
            composable("profile") {
                ProfileScreen(
                    openSettings = { section -> nav.navigate("settings/$section") },
                    openProgress = { nav.navigate("progress") },
                    openPersonalize = { nav.navigate("personalize") },
                )
            }
            composable("progress") { ProgressScreen(onBack = { nav.popBackStack() }) }
            composable("settings/{section}") { entry ->
                SettingsScreen(section = entry.arguments?.getString("section") ?: "", onBack = { nav.popBackStack() })
            }
            composable("personalize") {
                PersonalizeScreen(onDone = { nav.popBackStack() })
            }
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

    // First-time guided tours: the app's main parts, then (on the first episode) the player.
    val tourSettings = context0().graph.settings
    val tourSeen by tourSettings.tourSeen.flow.collectAsState()
    val playerTourSeen by tourSettings.playerTourSeen.flow.collectAsState()
    if (!tourSeen && !inPlayer) {
        TourOverlay(Tour.MAIN) { tourSettings.tourSeen.set(true) }
    } else if (tourSeen && !playerTourSeen && route.startsWith("player")) {
        TourOverlay(Tour.PLAYER) { tourSettings.playerTourSeen.set(true) }
    }
    }

    // A link shared from another app (Spotify, Apple Podcasts, a browser…).
    val context = LocalContext.current

    // Badges earned: a celebration in the app, or a notification when it's in the background.
    var celebrate by remember { mutableStateOf<com.ascolta.app.data.Badge?>(null) }
    LaunchedEffect(Unit) {
        context.graph.progress.unlocked.collect { badge ->
            if (com.ascolta.app.AppVisibility.visible.value) celebrate = badge
            else com.ascolta.app.work.Notifications.badge(context, badge)
        }
    }
    celebrate?.let { BadgeUnlockedDialog(it, onDismiss = { celebrate = null }) }

    // Screens visited (route templates only, e.g. "player/{episodeId}", never ids).
    LaunchedEffect(route) {
        if (route.isNotEmpty()) com.ascolta.app.data.Analytics.track(
            "TelemetryDeck.Navigation.pathChanged",
            mapOf("TelemetryDeck.Navigation.destinationPath" to route.substringBefore('?')),
        )
    }

    // People who installed before analytics existed are asked once.
    val consent by context.graph.settings.analyticsConsent.flow.collectAsState()
    if (com.ascolta.app.data.Analytics.available && consent.isEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.analytics_title)) },
            text = { Text(stringResource(R.string.analytics_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { com.ascolta.app.data.Analytics.setConsent(true) }) {
                    Text(stringResource(R.string.analytics_yes))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { com.ascolta.app.data.Analytics.setConsent(false) }) {
                    Text(stringResource(R.string.analytics_no))
                }
            },
        )
    }
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

/**
 * A tab always opens its main page: pages opened inside the current tab (a podcast, a setting)
 * are closed first, so tapping Discover while on a podcast found in Discover goes back to
 * Discover, and switching tabs never brings back an old podcast page. Each tab's main page
 * keeps its place (e.g. Discover's search).
 */
private fun NavHostController.navigateToTab(route: String) {
    val target = tabOf(route)
    // Close the pages opened from the current tab, back to its main page.
    currentBackStack.value.lastOrNull { isTabRoot(it.destination.route) }?.let { root ->
        popBackStack(root.destination.id, inclusive = false)
    }
    if (currentTab() == target && !route.contains('?')) return
    if (target == "library") {
        // Home is the first page: going back to it keeps the other tab's main page for later.
        if (!popBackStack(graph.findStartDestination().id, inclusive = false, saveState = true)) {
            navigate("library") { popUpTo(0); launchSingleTop = true }
        }
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = !route.contains('?')
    }
}
