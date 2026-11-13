package com.ascolta.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ascolta.app.R
import com.ascolta.app.data.FishGame
import com.ascolta.app.data.FishGame.Background
import com.ascolta.app.data.FishGame.Bait
import com.ascolta.app.data.FishGame.Decoration
import com.ascolta.app.data.FishGame.Fish
import com.ascolta.app.data.FishGame.MINUTE
import com.ascolta.app.data.FishGame.Species
import com.ascolta.app.data.FishGame.Tier
import com.ascolta.app.graph
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

internal fun tierColor(t: Tier): Color = when (t) {
    Tier.COMMON -> Color(0xFF9AA0A6)
    Tier.UNCOMMON -> Color(0xFF3FA35B)
    Tier.RARE -> Color(0xFF3B82F6)
    Tier.VERY_RARE -> Color(0xFF9B4DDB)
    Tier.LEGENDARY -> Color(0xFFF2B01E)
}

private fun tierName(t: Tier): Int = when (t) {
    Tier.COMMON -> R.string.aq_tier_common
    Tier.UNCOMMON -> R.string.aq_tier_uncommon
    Tier.RARE -> R.string.aq_tier_rare
    Tier.VERY_RARE -> R.string.aq_tier_very_rare
    Tier.LEGENDARY -> R.string.aq_tier_legendary
}

private fun baitName(b: Bait): Int = when (b) { Bait.WORM -> R.string.aq_bait_worm; Bait.SHRIMP -> R.string.aq_bait_shrimp; Bait.LURE -> R.string.aq_bait_lure }
private fun baitArt(b: Bait): Int = when (b) { Bait.WORM -> R.drawable.aq_bait_worm; Bait.SHRIMP -> R.drawable.aq_bait_shrimp; Bait.LURE -> R.drawable.aq_bait_lure }
private fun backgroundName(b: Background): Int = when (b) { Background.REEF -> R.string.aq_bg_reef; Background.LAGOON -> R.string.aq_bg_lagoon; Background.DEEP -> R.string.aq_bg_deep }

private fun water(b: Background): List<Color> = when (b) {
    Background.REEF -> listOf(Color(0xFF5CCBF5), Color(0xFF1E88D0), Color(0xFF0B4F94))
    Background.LAGOON -> listOf(Color(0xFFFFB37A), Color(0xFFC2578A), Color(0xFF3B3F8F))
    Background.DEEP -> listOf(Color(0xFF12386B), Color(0xFF071B3A), Color(0xFF020915))
}

private fun sand(b: Background): Color = when (b) {
    Background.REEF -> Color(0xFFF1D9A5)
    Background.LAGOON -> Color(0xFFE9BE98)
    Background.DEEP -> Color(0xFF34435F)
}

/** The 3D model of [species] (read once from the app's assets). */
@Composable
private fun modelOf(species: Species): FishModel {
    val context = LocalContext.current
    return remember(species) { FishModel.of(context, species) }
}

/** Seconds that keep running while [animate] (screenshots pass false and a fixed [start]). */
@Composable
private fun rememberTime(animate: Boolean, start: Float = 0f): State<Float> {
    val time = remember { mutableFloatStateOf(start) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
        }
    }
    return time
}

private fun DrawScope.star(center: Offset, radius: Float, color: Color) {
    if (radius <= 0.5f) return
    val k = radius * 0.24f
    val p = Path().apply {
        moveTo(center.x, center.y - radius); lineTo(center.x + k, center.y - k); lineTo(center.x + radius, center.y)
        lineTo(center.x + k, center.y + k); lineTo(center.x, center.y + radius); lineTo(center.x - k, center.y + k)
        lineTo(center.x - radius, center.y); lineTo(center.x - k, center.y - k); close()
    }
    drawPath(p, color)
}

/**
 * Draws one fish in 3D, heading [yaw], with its tier's effects: Rare is shiny with sparkles,
 * Very rare has a glowing aura and a trail of light bubbles, Legendary a gold glow, a shimmer
 * and a sparkle trail. [silhouette] draws a plain shape (not collected yet); [fog] fades a
 * far fish into the water.
 */
internal fun DrawScope.drawFish(
    model: FishModel, tier: Tier, center: Offset, size: Float, time: Float,
    phase: Float = 0f, yaw: Float = 0f, alpha: Float = 1f, silhouette: Color? = null, fog: Color = Color.Transparent, fogAmount: Float = 0f,
) {
    val half = size / 2
    val effects = silhouette == null
    val behind = -kotlin.math.cos(yaw)
    if (effects && tier >= Tier.VERY_RARE) {
        val c = if (tier == Tier.LEGENDARY) Color(0xFFFFD54A) else Color(0xFF8AF6FF)
        val r = half * 1.1f * (0.9f + 0.1f * sin(time * 3f + phase * 6f))
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.6f * alpha), c.copy(alpha = 0.18f * alpha), Color.Transparent), center, r), r, center)
    }
    drawIntoCanvas {
        model.draw(
            it.nativeCanvas, tier, center.x, center.y, size, yaw, time, phase,
            fog = fog.toArgb(), fogAmount = fogAmount, flat = silhouette?.toArgb(), effects = effects,
        )
    }
    if (!effects) return
    if (tier == Tier.VERY_RARE) {
        for (i in 0 until 5) {
            val p = (time * 0.8f + i / 5f + phase) % 1f
            val c = Offset(center.x + behind * half * (0.55f + 0.9f * p), center.y - half * 0.45f * p + sin(time * 5f + i) * half * 0.05f)
            drawCircle(Color(0xFFB8FBFF).copy(alpha = (1f - p) * 0.9f * alpha), half * (0.05f + 0.06f * (1f - p)), c)
        }
    }
    if (tier == Tier.RARE || tier == Tier.LEGENDARY) {
        val n = if (tier == Tier.LEGENDARY) 6 else 3
        for (i in 0 until n) {
            val a = i * 2.4f + phase * 6f
            val r = half * (0.35f + 0.45f * ((i * 0.37f) % 1f))
            val twinkle = sin(time * 4f + i * 1.7f).coerceAtLeast(0f)
            star(Offset(center.x + r * kotlin.math.cos(a), center.y + r * sin(a) * 0.7f), half * 0.17f * twinkle, Color.White.copy(alpha = alpha))
        }
    }
    if (tier == Tier.LEGENDARY) {
        for (i in 0 until 5) {
            val p = (time * 0.7f + i / 5f + phase) % 1f
            val c = Offset(center.x + behind * half * (0.6f + 1.0f * p), center.y + sin(time * 3f + i * 2f) * half * 0.22f)
            star(c, half * 0.14f * (1f - p), Color(0xFFFFE27A).copy(alpha = (1f - p) * alpha))
        }
    }
}

/** A fish on its own (collection, catch): it swims in place and turns a little, or all the way round with [spin]. */
@Composable
internal fun FishSprite(fish: Fish, time: Float, modifier: Modifier = Modifier, owned: Boolean = true, spin: Boolean = false) {
    val model = modelOf(fish.species)
    val shadow = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)
    Canvas(modifier) {
        val phase = fish.species.ordinal * 0.13f
        val yaw = if (!owned) 0f else if (spin) -0.5f + time * 0.9f else sin(time * 0.7f + phase * 6f) * 0.5f - 0.25f
        drawFish(model, fish.tier, Offset(size.width / 2, size.height / 2), size.minDimension * 0.8f, time, phase, yaw, silhouette = if (owned) null else shadow)
    }
}

/** A fish swimming in the tank: where it is (0…1), how near (z), and where it's heading. */
private class Swimmer(index: Int) {
    private val random = Random(index * 7919 + 13)
    var x = 0.12f + random.nextFloat() * 0.76f
    var y = 0.14f + random.nextFloat() * 0.52f
    var z = random.nextFloat()
    /** Heading: 0 nose to the right, π to the left, in between towards or away from the glass. */
    var yaw = (if (random.nextBoolean()) 3.1416f else 0f) + (random.nextFloat() - 0.5f) * 1.3f
    val phase = random.nextFloat()
    private val speed = 0.05f + random.nextFloat() * 0.05f
    private var tx = x; private var ty = y; private var tz = z

    fun step(dt: Float, attract: Offset?) {
        if (attract != null) {
            tx = (attract.x + (phase - 0.5f) * 0.16f).coerceIn(0.08f, 0.92f); ty = (attract.y + (speed - 0.075f) * 3f).coerceIn(0.1f, 0.74f); tz = 0.6f + phase * 0.4f
        } else if (hypot(tx - x, ty - y) < 0.03f) {
            tx = 0.08f + random.nextFloat() * 0.84f; ty = 0.12f + random.nextFloat() * 0.58f; tz = random.nextFloat()
        }
        val dx = tx - x; val dy = ty - y; val d = hypot(dx, dy)
        if (d > 0.001f) {
            val v = speed * (if (attract != null) 2.6f else 1f) * dt
            x += dx / d * minOf(v, d); y += dy / d * minOf(v, d) * 0.6f
            // Turn towards where it's going: sideways, and into or out of the depth of the tank.
            val want = kotlin.math.atan2(-(tz - z) * 0.45f, if (kotlin.math.abs(dx) < 0.03f) kotlin.math.cos(yaw) * 0.03f else dx)
            var turn = (want - yaw) % 6.2832f
            if (turn > 3.1416f) turn -= 6.2832f else if (turn < -3.1416f) turn += 6.2832f
            yaw += turn * (dt * 2.6f).coerceAtMost(1f)
        }
        z += (tz - z) * (dt * 0.4f).coerceAtMost(1f)
    }
}

/** Where each decoration stands on the sand (x of its middle, size: both as shares of the width). */
private val DECO_PLACE: Map<Decoration, Pair<Float, Float>> = mapOf(
    Decoration.SHIP to (0.20f to 0.34f), Decoration.CASTLE to (0.66f to 0.32f), Decoration.SEAWEED to (0.05f to 0.24f),
    Decoration.CORAL_PINK to (0.84f to 0.24f), Decoration.ANCHOR to (0.50f to 0.20f), Decoration.ROCKS to (0.36f to 0.22f),
    Decoration.REDPLANT to (0.95f to 0.22f), Decoration.CORAL_BRAIN to (0.44f to 0.17f), Decoration.CHEST to (0.56f to 0.18f),
    Decoration.AMPHORA to (0.10f to 0.16f), Decoration.CLAM to (0.76f to 0.14f), Decoration.STARFISH to (0.30f to 0.11f),
)

/**
 * The tank: water of the chosen background, light rays, sand, the decorations owned, rising
 * bubbles and the fish, which wander nearer and farther and come to a tap on the glass.
 */
@Composable
internal fun Tank(
    fish: List<Fish>, decorations: Set<Decoration>, background: Background, modifier: Modifier = Modifier,
    animate: Boolean = true, startTime: Float = 0f,
) {
    val swimmers = remember(fish) { fish.indices.map { Swimmer(it) } }
    val models = fish.map { modelOf(it.species) }
    val painters: List<Pair<Decoration, Painter>> = DECO_PLACE.keys.filter { it in decorations }.map { it to painterResource(DECO_ART.getValue(it).art) }
    val time = remember { mutableFloatStateOf(startTime) }
    var attract by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    LaunchedEffect(animate, swimmers) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val goal = attract?.takeIf { time.floatValue < it.second }?.first
            swimmers.forEach { it.step(dt, goal) }
            time.floatValue += dt
        }
    }
    val colors = water(background)
    val floor = sand(background)
    Canvas(
        modifier.clip(RoundedCornerShape(22.dp)).pointerInput(Unit) {
            detectTapGestures { p -> attract = Offset(p.x / size.width, p.y / size.height) to time.floatValue + 3.5f }
        },
    ) {
        val t = time.floatValue
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(colors))
        // Light from the surface.
        val rays = if (background == Background.DEEP) 0.05f else 0.12f
        for (i in 0 until 4) {
            val x = w * (0.1f + i * 0.26f) + sin(t * 0.4f + i) * w * 0.03f
            val ray = Path().apply { moveTo(x, 0f); lineTo(x + w * 0.09f, 0f); lineTo(x + w * 0.02f + w * 0.22f, h); lineTo(x - w * 0.08f + w * 0.1f, h); close() }
            drawPath(ray, Brush.verticalGradient(listOf(Color.White.copy(alpha = rays), Color.Transparent)))
        }
        // Sand.
        val top = h * 0.86f
        val ground = Path().apply {
            moveTo(0f, top)
            cubicTo(w * 0.25f, top - h * 0.035f, w * 0.45f, top + h * 0.03f, w * 0.65f, top - h * 0.01f)
            cubicTo(w * 0.8f, top - h * 0.035f, w * 0.92f, top + h * 0.01f, w, top - h * 0.015f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(ground, floor)
        drawPath(ground, Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)), startY = top, endY = h))
        for ((item, painter) in painters) {
            val (cx, share) = DECO_PLACE.getValue(item)
            val s = w * share
            translate(cx * w - s / 2, h * 0.925f - s) { with(painter) { draw(Size(s, s)) } }
        }
        for (i in 0 until 9) {
            val p = (t * (0.07f + i * 0.011f) + i * 0.37f) % 1f
            val x = w * ((i * 0.618f) % 1f) + sin(t * 1.3f + i) * w * 0.012f
            drawCircle(Color.White.copy(alpha = 0.28f * (1f - p * 0.5f)), w * (0.006f + (i % 3) * 0.003f), Offset(x, h * (0.9f - p * 0.95f)))
        }
        for (i in swimmers.indices.sortedBy { swimmers[it].z }) {
            val s = swimmers[i]
            val bob = sin(t * 1.6f + s.phase * 6f) * h * 0.008f
            drawFish(
                models[i], fish[i].tier, Offset(s.x * w, s.y * h + bob), w * (0.15f + 0.13f * s.z), t,
                phase = s.phase, yaw = s.yaw, alpha = 0.62f + 0.38f * s.z, fog = colors[1], fogAmount = (1f - s.z) * 0.32f,
            )
        }
        // The glass.
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), start = Offset.Zero, end = Offset(w * 0.5f, h * 0.5f)))
    }
}

/** What the dialog over the tank shows. */
internal enum class FishingPhase { CHOOSE, WAITING, BITE, MISSED, EARLY, CAUGHT }

/** The Aquarium with its data: listening coins, fishing, the collection and the shop. */
@Composable
fun AquariumScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val state by graph.fish.state.collectAsState()
    var welcome by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        graph.progress.flush()
        val native = graph.settings.nativeLanguage.value
        val past = graph.progress.stats.value.listenedMs.filterKeys { FishGame.isForeign(it, native) }.values.sum()
        val firstVisit = !graph.fish.state.value.gifted
        graph.fish.opened(past)
        if (firstVisit) welcome = (past.coerceAtMost(FishGame.GIFT_MAX_MS) / FishGame.COIN_MS).toInt()
    }
    AquariumContent(
        state, graph.fish.today, welcome, onBack,
        onCast = { graph.fish.cast(it) }, onMerge = { graph.fish.merge(it) }, onBuy = { graph.fish.buy(it) }, onChoose = { graph.fish.choose(it) },
    )
}

/**
 * The Aquarium's screen for a given [state] (also drawn as it is by the screenshot tests,
 * which pass [animate] = false and what to open).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AquariumContent(
    state: FishGame.State, today: Long, welcome: Int, onBack: () -> Unit,
    onCast: (Bait) -> FishGame.Catch?, onMerge: (Fish) -> Unit, onBuy: (Decoration) -> Unit, onChoose: (Background) -> Unit,
    animate: Boolean = true, initialSheet: String? = null, initialCatch: FishGame.Catch? = null, inlineSheets: Boolean = false,
) {
    var sheet by remember { mutableStateOf(initialSheet) }
    val time by rememberTime(animate, 1.3f)
    val tank = remember(state.fish) { FishGame.tank(state) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aq_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { CoinChip(state.coins, Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        if (inlineSheets && sheet != null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(top = 8.dp)) {
                when (sheet) {
                    "collection" -> CollectionSheet(state, time, onMerge)
                    "shop" -> ShopSheet(state, onBuy, onChoose)
                    "fishing" -> Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Fishing(state, today, time, onCast, animate, initialCatch) {}
                    }
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Tank(tank, state.decorations, state.background, Modifier.fillMaxSize(), animate, 1.3f)
                if (tank.isEmpty()) {
                    Text(
                        stringResource(R.string.aq_empty), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
                if (state.multiplier > 1.0) {
                    Text(
                        stringResource(R.string.aq_combo, state.multiplier.toString()), color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (welcome > 0) stringResource(R.string.aq_welcome, welcome) else stringResource(R.string.aq_how),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.aq_biting, stringResource(FISH_ART.getValue(FishGame.biting(today)).name)),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp),
            )
            val left = ((state.doubleLeftMs(today) + MINUTE - 1) / MINUTE).toInt()
            Text(
                if (left > 0) stringResource(R.string.aq_double_left, left) else stringResource(R.string.aq_double_done),
                style = MaterialTheme.typography.labelLarge, color = if (left > 0) tierColor(Tier.LEGENDARY) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Box {
                Button(onClick = { sheet = "fishing" }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Image(painterResource(R.drawable.aq_float), null, Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.aq_fish), style = MaterialTheme.typography.titleMedium)
                }
                if (state.freeCasts > 0) {
                    Text(
                        state.freeCasts.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sheet = "collection" }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                    FitText("${stringResource(R.string.aq_collection)} · ${state.collected}/${FishGame.ALL.size}", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { sheet = "shop" }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                    FitText(stringResource(R.string.aq_shop), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (!inlineSheets) when (sheet) {
        "collection" -> ModalBottomSheet(onDismissRequest = { sheet = null }) { CollectionSheet(state, time, onMerge) }
        "shop" -> ModalBottomSheet(onDismissRequest = { sheet = null }) { ShopSheet(state, onBuy, onChoose) }
        "fishing" -> Dialog(onDismissRequest = { sheet = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { Fishing(state, today, time, onCast, animate, initialCatch) { sheet = null } }
            }
        }
    }
}

@Composable
private fun CoinChip(coins: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.aq_coin), null, Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Text(coins.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TierChip(tier: Tier) {
    Text(
        stringResource(tierName(tier)).uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(tierColor(tier)).padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

/** Fishing: choose a bait, cast, wait for the float to dip, reel in, and see the catch. */
@Composable
private fun Fishing(
    state: FishGame.State, today: Long, time: Float, onCast: (Bait) -> FishGame.Catch?, animate: Boolean,
    initialCatch: FishGame.Catch?, close: () -> Unit,
) {
    var bait by remember { mutableStateOf(Bait.WORM) }
    var phase by remember { mutableStateOf(if (initialCatch != null) FishingPhase.CAUGHT else FishingPhase.CHOOSE) }
    var caught by remember { mutableStateOf(initialCatch) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase, round) {
        if (!animate) return@LaunchedEffect
        if (phase == FishingPhase.WAITING) { delay(Random.nextLong(1_200, 3_000)); phase = FishingPhase.BITE }
        else if (phase == FishingPhase.BITE) { delay(1_700); phase = FishingPhase.MISSED }
    }
    val result = caught
    if (phase == FishingPhase.CAUGHT && result != null) {
        val pop = remember(result) { Animatable(if (animate) 0.2f else 1f) }
        LaunchedEffect(result) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        FishSprite(result.fish, time, Modifier.size(200.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, spin = true)
        Text(stringResource(FISH_ART.getValue(result.fish.species).name), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        TierChip(result.fish.tier)
        Text(
            if (result.isNew) stringResource(R.string.aq_new) else stringResource(R.string.aq_have, state.count(result.fish)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.guaranteed) {
            Text(stringResource(R.string.aq_guaranteed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = close) { Text(stringResource(R.string.aq_done)) }
            Button(onClick = { caught = null; phase = FishingPhase.CHOOSE }) { Text(stringResource(R.string.aq_again)) }
        }
        return
    }
    // The pond: sky, water, and the float that dips at a bite.
    val float = painterResource(R.drawable.aq_float)
    val waiting = phase == FishingPhase.WAITING || phase == FishingPhase.BITE
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.large)) {
        val w = size.width; val h = size.height; val line = h * 0.42f
        drawRect(Brush.verticalGradient(listOf(Color(0xFFBDE7FF), Color(0xFFEAF7FF)), endY = line), size = Size(w, line))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3FA9E6), Color(0xFF155E9E)), startY = line, endY = h), topLeft = Offset(0f, line), size = Size(w, h - line))
        for (i in 0 until 3) {
            val y = line + h * (0.12f + i * 0.17f)
            drawLine(Color.White.copy(alpha = 0.18f), Offset(w * (0.1f + i * 0.2f) + sin(time + i) * 10f, y), Offset(w * (0.35f + i * 0.2f) + sin(time + i) * 10f, y), strokeWidth = 3f)
        }
        if (waiting) {
            val dip = if (phase == FishingPhase.BITE) h * 0.13f + sin(time * 22f) * h * 0.02f else sin(time * 2.4f) * h * 0.018f
            val s = h * 0.36f
            val c = Offset(w / 2, line + dip)
            drawLine(Color(0xFF33261F), Offset(w * 0.86f, 0f), Offset(c.x, c.y - s * 0.42f), strokeWidth = 2.5f)
            if (phase == FishingPhase.BITE) for (i in 0 until 2) {
                val p = (time * 1.6f + i * 0.5f) % 1f
                drawOval(Color.White.copy(alpha = 0.7f * (1f - p)), Offset(c.x - s * (0.4f + p), line - s * 0.12f * (0.4f + p)), Size(s * (0.8f + 2 * p), s * 0.24f * (0.4f + p) * 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
            translate(c.x - s / 2, c.y - s / 2) { with(float) { draw(Size(s, s)) } }
        }
    }
    val message = when (phase) {
        FishingPhase.WAITING -> stringResource(R.string.aq_waiting)
        FishingPhase.BITE -> stringResource(R.string.aq_bite)
        FishingPhase.MISSED -> stringResource(R.string.aq_missed)
        FishingPhase.EARLY -> stringResource(R.string.aq_too_early)
        else -> stringResource(R.string.aq_choose_bait)
    }
    Text(
        message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        color = if (phase == FishingPhase.BITE) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface,
    )
    if (waiting) {
        Button(
            onClick = {
                if (phase == FishingPhase.BITE) {
                    caught = onCast(bait)
                    phase = if (caught != null) FishingPhase.CAUGHT else FishingPhase.CHOOSE
                } else phase = FishingPhase.EARLY
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text(stringResource(R.string.aq_reel), style = MaterialTheme.typography.titleMedium) }
        return
    }
    for (b in Bait.entries) {
        val selected = b == bait
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .clickable { bait = b }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(baitArt(b)), null, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FitText(stringResource(baitName(b)), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.odds.forEachIndexed { i, odds ->
                        if (odds > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(tierColor(Tier.entries[i]), CircleShape))
                            Text(" " + (if (odds % 10 == 0) "${odds / 10}" else "${odds / 10}.${odds % 10}") + "%", fontSize = 11.sp)
                        }
                    }
                }
            }
            Image(painterResource(R.drawable.aq_coin), null, Modifier.size(18.dp))
            Text(" ${b.price}", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
    val free = bait == Bait.WORM && state.freeCasts > 0
    Button(
        onClick = { phase = FishingPhase.WAITING; round++ }, enabled = state.canCast(bait), modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Text(
            if (free) stringResource(R.string.aq_cast_free, state.freeCasts) else stringResource(R.string.aq_cast, bait.price),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (!state.canCast(bait)) {
        Text(stringResource(R.string.aq_need, bait.price - state.coins), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Text(
        stringResource(R.string.aq_guarantee_hint, state.sinceRare.coerceAtMost(FishGame.GUARANTEE_AFTER), FishGame.GUARANTEE_AFTER) + " " +
            stringResource(R.string.aq_free_hint, ((FishGame.FREE_CAST_EVERY_MS - state.castProgressMs + MINUTE - 1) / MINUTE).toInt()) + " " +
            stringResource(R.string.aq_biting, stringResource(FISH_ART.getValue(FishGame.biting(today)).name)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SheetColumn(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

/** The 50 fish: a row per species, a column per tier; a fish owned three times can be merged. */
@Composable
private fun CollectionSheet(state: FishGame.State, time: Float, onMerge: (Fish) -> Unit) {
    SheetColumn(stringResource(R.string.aq_collection)) {
        Text(
            stringResource(R.string.aq_collection_count, state.collected, FishGame.ALL.size) + ". " + stringResource(R.string.aq_merge_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tier in Tier.entries) {
                FitText(
                    stringResource(tierName(tier)), color = tierColor(tier), textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), minSize = 6.sp,
                )
            }
        }
        for (species in Species.entries) {
            Text(stringResource(FISH_ART.getValue(species).name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tier in Tier.entries) {
                    val fish = Fish(species, tier)
                    val count = state.count(fish)
                    val merge = state.canMerge(fish)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
                            .then(if (count > 0) Modifier.border(2.dp, tierColor(tier), MaterialTheme.shapes.medium) else Modifier)
                            .clickable(enabled = merge) { onMerge(fish) },
                    ) {
                        FishSprite(fish, if (count > 0) time else 0f, Modifier.fillMaxSize(), owned = count > 0)
                        if (count > 1) Text("×$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 5.dp, vertical = 2.dp))
                        if (merge) {
                            Text(
                                "⬆", color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).clip(CircleShape).background(tierColor(Tier.entries[tier.ordinal + 1])).padding(horizontal = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopSheet(state: FishGame.State, onBuy: (Decoration) -> Unit, onChoose: (Background) -> Unit) {
    SheetColumn(stringResource(R.string.aq_shop)) {
        Text(stringResource(R.string.aq_backgrounds), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bg in Background.entries) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.medium).background(Brush.verticalGradient(water(bg))))
                    FitText(stringResource(backgroundName(bg)), style = MaterialTheme.typography.labelMedium)
                    when {
                        bg == state.background -> Text(stringResource(R.string.aq_in_use), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp))
                        bg in state.backgrounds -> OutlinedButton(onClick = { onChoose(bg) }) { Text(stringResource(R.string.aq_use)) }
                        else -> PriceButton(bg.price, state.coins) { onChoose(bg) }
                    }
                }
            }
        }
        Text(stringResource(R.string.aq_decorations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        for (line in Decoration.entries.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in line) {
                    val art = DECO_ART.getValue(item)
                    Column(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(painterResource(art.art), null, Modifier.size(64.dp))
                        FitText(stringResource(art.name), style = MaterialTheme.typography.labelMedium)
                        if (item in state.decorations) {
                            FitText("✓ " + stringResource(R.string.aq_in_tank), style = MaterialTheme.typography.labelSmall, color = tierColor(Tier.UNCOMMON), modifier = Modifier.padding(vertical = 14.dp))
                        } else PriceButton(item.price, state.coins) { onBuy(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceButton(price: Int, coins: Int, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = coins >= price, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Image(painterResource(R.drawable.aq_coin), null, Modifier.size(16.dp))
        Text(" $price", maxLines = 1, softWrap = false)
    }
}
