package com.ascolta.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Person
import com.ascolta.app.data.PodcastEntity
import com.ascolta.app.ui.CategoryStep
import com.ascolta.app.ui.FloatingNavBar
import com.ascolta.app.ui.LibraryScreen
import com.ascolta.app.ui.NavItem
import com.ascolta.app.ui.OnboardingScreen
import com.ascolta.app.ui.ProfileScreen
import com.ascolta.app.ui.SettingsScreen
import com.ascolta.app.ui.theme.AscoltaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pictures of the main screens (build/outputs/roborazzi), to check the design without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AscoltaTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun onboardingLanguages() = shot("1-onboarding-languages") { OnboardingScreen(onDone = {}) }

    @Test fun onboardingCategories() = shot("2-onboarding-categories") {
        CategoryStep(mutableStateListOf(1489, 1487))
    }

    @Test fun profile() = shot("3-profile") { ProfileScreen({}, {}, {}) }

    @Test fun settingsPage() = shot("4-settings-display") { SettingsScreen("display", onBack = {}) }

    @Test fun home() {
        val app = ApplicationProvider.getApplicationContext<AscoltaApp>()
        runBlocking {
            val dao = app.graph.db.podcastDao()
            listOf("Il Post", "Storie di Geopolitica", "Italiano Automatico", "Daily Italian", "Morning").forEachIndexed { i, t ->
                dao.insert(PodcastEntity(feedUrl = "https://example.com/$i", title = t, author = null, description = null,
                    imageUrl = null, language = "it", subscribedAt = 0, libraryLanguage = "it", favourite = i == 2))
            }
        }
        shot("5-home") {
            Column {
                LibraryScreen(openPodcast = {}, openPlayer = {}, openDiscover = {})
            }
        }
    }

    @Test fun navBar() = shot("6-nav-bar") {
        FloatingNavBar(
            listOf(
                NavItem("library", "Home", androidx.compose.material.icons.Icons.Outlined.Home),
                NavItem("discover", "Discover", androidx.compose.material.icons.Icons.Outlined.Explore),
                NavItem("words", "Words", androidx.compose.material.icons.Icons.Outlined.Style),
                NavItem("profile", "Profile", androidx.compose.material.icons.Icons.Outlined.Person),
            ),
            current = "profile", onSelect = {},
        )
    }
}
