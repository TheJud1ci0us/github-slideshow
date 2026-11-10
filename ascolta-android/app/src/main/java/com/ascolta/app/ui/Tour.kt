package com.ascolta.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ascolta.app.R

/**
 * A short guided tour for first-time users: the screen is dimmed except for one control at a
 * time, with a line of explanation and Next / Skip. Controls take part with [tourTarget].
 */
object Tour {
    /** Where each control is on screen (registered by [tourTarget]). */
    val targets = mutableStateMapOf<String, Rect>()

    /** One step: the control to show ([target], or null for a card in the middle) and what to say. */
    data class Step(val target: String?, val title: Int, val text: Int)

    /** Home and Discover (after the setup). */
    val MAIN = listOf(
        Step("language", R.string.tour_language_title, R.string.tour_language_text),
        Step("tab_discover", R.string.tour_discover_title, R.string.tour_discover_text),
        Step("tab_profile", R.string.tour_models_title, R.string.tour_models_text),
        Step("tab_words", R.string.tour_words_title, R.string.tour_words_text),
        Step("tab_profile", R.string.tour_stats_title, R.string.tour_stats_text),
    )

    /** The first time an episode is opened. */
    val PLAYER = listOf(
        Step("transcript", R.string.tour_transcript_title, R.string.tour_transcript_text),
        Step("player_more", R.string.tour_more_title, R.string.tour_more_text),
        Step("player_controls", R.string.tour_controls_title, R.string.tour_controls_text),
    )
}

/** Marks a control for the tour under [id]. */
fun Modifier.tourTarget(id: String): Modifier = this.then(
    Modifier.onGloballyPositioned { Tour.targets[id] = it.boundsInRoot() },
)

/** Forgets a control's place when it leaves the screen. */
@Composable
fun ForgetTourTarget(id: String) {
    DisposableEffect(id) { onDispose { Tour.targets.remove(id) } }
}

@Composable
fun TourOverlay(steps: List<Tour.Step>, onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val step = steps.getOrNull(index) ?: return
    val rect = step.target?.let { Tour.targets[it] }
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Swallow touches outside the card.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Dim everything except the control being explained.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            if (rect != null) {
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(rect.left - pad, rect.top - pad),
                    size = androidx.compose.ui.geometry.Size(rect.width + 2 * pad, rect.height + 2 * pad),
                    cornerRadius = CornerRadius(pad * 2),
                    blendMode = BlendMode.Clear,
                )
            }
        }
        val screenH = with(density) { maxHeight.toPx() }
        // The card goes below the control, or above it when the control is low on the screen.
        val below = rect == null || rect.bottom < screenH * 0.55f
        val cardModifier = when {
            rect == null -> Modifier.align(Alignment.Center)
            below -> Modifier.offset { IntOffset(0, (rect.bottom + pad * 2).toInt()) }
            else -> Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -(screenH - rect.top + pad * 2).toInt()) }
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = cardModifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("${index + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(step.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.tour_skip)) }
                    Box(Modifier.weight(1f))
                    Button(onClick = { if (index < steps.lastIndex) index++ else onDone() }) {
                        Text(stringResource(if (index < steps.lastIndex) R.string.tour_next else R.string.tour_done))
                    }
                }
            }
        }
    }
}
