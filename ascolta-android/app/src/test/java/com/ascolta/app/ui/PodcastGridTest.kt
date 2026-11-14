package com.ascolta.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.ascolta.app.data.PodcastEntity
import com.ascolta.app.data.PodcastWithStats
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class PodcastGridTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun row(from: Int) = (from..from + 2).map { i -> PodcastWithStats(
        PodcastEntity(id = i.toLong(), feedUrl = "https://example.test/$i", title = "Show $i", author = null,
            description = null, imageUrl = "https://example.test/$i.jpg", language = "fr", subscribedAt = 0), 12, 0, i,
    ) }
    @Before fun images() {
        val colors = listOf(0xFF12646A, 0xFFB65D35, 0xFF344D84, 0xFF718A45, 0xFF9A495B, 0xFF574881)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(RuntimeEnvironment.getApplication()).components {
            add(Interceptor { chain ->
                val id = Regex("/(\\d+)\\.jpg").find(chain.request.data.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val bitmap = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(colors[(id - 1) % colors.size].toInt())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 54f; isFakeBoldText = true }
                canvas.drawText("PODCAST", 30f, 180f, paint)
                canvas.drawText("$id", 30f, 255f, paint)
                paint.strokeWidth = 6f
                (0..15).forEach { bar -> val x = 30f + bar * 25; canvas.drawLine(x, 330f - (bar % 5) * 10, x, 380f + (bar % 4) * 10, paint) }
                SuccessResult(bitmap.asImage(), chain.request, DataSource.MEMORY)
            })
        }.build())
    }
    @After fun reset() { SingletonImageLoader.reset() }
    @Composable private fun Screen() {
        MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8CD4BE))) { Surface { Column(Modifier.fillMaxSize()) {
            Text("Your podcasts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
            PodcastGridRow(row(1)) {}
            PodcastGridRow(row(4)) {}
        } } }
    }
    @Test fun coversAreAdjacentEqualSquaresAndRemainClickable() {
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        var selected = -1L
        activity.setContent { MaterialTheme { Column(Modifier.fillMaxWidth()) { PodcastGridRow(row(1)) { selected = it ?: -1 } } } }
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }
        assertEquals(3, nodes.size)
        val first = nodes[0].boundsInRoot; val second = nodes[1].boundsInRoot; val third = nodes[2].boundsInRoot
        assertEquals(0f, first.left, 1f)
        assertEquals(first.width, first.height, 1f)
        assertEquals(first.right, second.left, 1f)
        assertEquals(second.right, third.left, 1f)
        assertEquals(first.width, third.width, 1f)
        compose.onAllNodes(hasClickAction())[1].performClick()
        assertEquals(2L, selected)
    }
    @Test fun fullWidthPhone() = captureRoboImage("build/screens/grid-412.png") { Screen() }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun narrowPhone() = captureRoboImage("build/screens/grid-360.png") { Screen() }
    @Test @Config(qualifiers = "w915dp-h412dp-land-xxhdpi") fun landscape() = captureRoboImage("build/screens/grid-landscape.png") { Screen() }
}
