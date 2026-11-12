package com.ascolta.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ascolta.app.R
import com.ascolta.app.data.DigGame
import com.ascolta.app.data.DigGame.Content
import com.ascolta.app.data.DigGame.CrateType
import com.ascolta.app.data.DigGame.Layer
import com.ascolta.app.data.DigGame.MINUTE
import com.ascolta.app.data.DigGame.Rarity
import com.ascolta.app.data.DigGame.Tool
import com.ascolta.app.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

internal fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> Color(0xFF9AA0A6)
    Rarity.UNCOMMON -> Color(0xFF3FA35B)
    Rarity.RARE -> Color(0xFF3B82F6)
    Rarity.EPIC -> Color(0xFF9B4DDB)
    Rarity.LEGENDARY -> Color(0xFFF2B01E)
}

private fun rarityName(r: Rarity): Int = when (r) {
    Rarity.COMMON -> R.string.dig_rarity_common
    Rarity.UNCOMMON -> R.string.dig_rarity_uncommon
    Rarity.RARE -> R.string.dig_rarity_rare
    Rarity.EPIC -> R.string.dig_rarity_epic
    Rarity.LEGENDARY -> R.string.dig_rarity_legendary
}

private fun layerName(l: Layer): Int = when (l) {
    Layer.TOPSOIL -> R.string.dig_layer_topsoil
    Layer.CLAY -> R.string.dig_layer_clay
    Layer.SANDSTONE -> R.string.dig_layer_sandstone
    Layer.LIMESTONE -> R.string.dig_layer_limestone
    Layer.GRANITE -> R.string.dig_layer_granite
    Layer.CRYSTAL -> R.string.dig_layer_crystal
}

private fun layerColor(row: Int): Color {
    val base = when (DigGame.layer(row)) {
        Layer.TOPSOIL -> Color(0xFF8B5E3C)
        Layer.CLAY -> Color(0xFFB5653A)
        Layer.SANDSTONE -> Color(0xFFD3A95F)
        Layer.LIMESTONE -> Color(0xFFB4B8AA)
        Layer.GRANITE -> Color(0xFF6E7480)
        Layer.CRYSTAL -> Color(0xFF6A56B0)
    }
    // The layers come back deeper down, darker each time.
    return lerp(base, Color.Black, (DigGame.depth(row) / Layer.entries.size * 0.22f).coerceAtMost(0.6f))
}

private fun toolName(t: Tool): Int = when (t) {
    Tool.WOOD -> R.string.dig_tool_wood
    Tool.STONE -> R.string.dig_tool_stone
    Tool.IRON -> R.string.dig_tool_iron
    Tool.STEEL -> R.string.dig_tool_steel
    Tool.DIAMOND -> R.string.dig_tool_diamond
}

private fun toolArt(t: Tool): Int = when (t) {
    Tool.WOOD -> R.drawable.dig_pick_wood
    Tool.STONE -> R.drawable.dig_pick_stone
    Tool.IRON -> R.drawable.dig_pick_iron
    Tool.STEEL -> R.drawable.dig_pick_steel
    Tool.DIAMOND -> R.drawable.dig_pick_diamond
}

private fun crateName(t: CrateType): Int = when (t) {
    CrateType.WOOD -> R.string.dig_crate_wood
    CrateType.IRON -> R.string.dig_crate_iron
    CrateType.GOLD -> R.string.dig_crate_gold
}

private fun crateArt(t: CrateType, open: Boolean = false): Int = when (t) {
    CrateType.WOOD -> if (open) R.drawable.dig_crate_wood_open else R.drawable.dig_crate_wood
    CrateType.IRON -> if (open) R.drawable.dig_crate_iron_open else R.drawable.dig_crate_iron
    CrateType.GOLD -> if (open) R.drawable.dig_crate_gold_open else R.drawable.dig_crate_gold
}

private fun power(t: Tool): String = if (t.power % 1.0 == 0.0) t.power.toInt().toString() else t.power.toString()

/** What the dialog over the wall shows. */
private sealed interface Reveal {
    data class Found(val find: DigGame.Find, val isNew: Boolean, val count: Int) : Reveal
    data class CrateFound(val type: CrateType) : Reveal
    data class Opening(val type: CrateType, val round: Int) : Reveal
}

/**
 * The Dig site: a wall of blocks dug with listening minutes ([DigGame] has the rules). Blocks
 * hide finds of five rarities and crates; pickaxes, crates and the museum open as sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val state by graph.dig.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var sheet by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf<Reveal?>(null) }
    var welcome by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        graph.progress.flush()
        val native = graph.settings.nativeLanguage.value
        val past = graph.progress.stats.value.listenedMs.filterKeys { it != native }.values.sum()
        val firstVisit = !graph.dig.state.value.gifted
        graph.dig.opened(past)
        if (firstVisit) welcome = (past.coerceAtMost(DigGame.GIFT_MAX_MS) / MINUTE).toInt()
    }

    fun tap(row: Int, col: Int): Boolean {
        val before = graph.dig.state.value
        val result = graph.dig.dig(row, col)
        if (result == null) {
            val missing = DigGame.costMinutes(row, before.tool) * MINUTE - before.walletMs
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.getString(R.string.dig_need, ((missing + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)))
            }
            return false
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (val c = result.content) {
            is Content.Treasure -> reveal = Reveal.Found(c.find, result.isNew, result.state.finds[c.find.id] ?: 1)
            is Content.Crate -> reveal = Reveal.CrateFound(c.type)
            Content.Nothing -> Unit
        }
        return true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dig_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    Row(
                        Modifier.padding(end = 12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(painterResource(R.drawable.dig_minute), null, Modifier.size(26.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dig_minutes, state.minutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        val rows = maxOf(8, state.deepestRow + 8)
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 40.dp),
        ) {
            item(key = "top") {
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Shortcut(toolArt(state.tool), stringResource(R.string.dig_tools), null, Modifier.weight(1f)) { sheet = "tools" }
                        Shortcut(
                            R.drawable.dig_crate_wood, stringResource(R.string.dig_crates),
                            state.crates.values.sum().takeIf { it > 0 }?.toString(), Modifier.weight(1f),
                        ) { sheet = "crates" }
                        Shortcut(
                            R.drawable.dig_museum, stringResource(R.string.dig_museum),
                            "${state.finds.size}/${DigGame.FINDS.size}", Modifier.weight(1f), badgeIsCount = false,
                        ) { sheet = "museum" }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.dig_site), null, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            if (state.multiplier > 1.0) {
                                Text(
                                    stringResource(R.string.dig_combo, state.multiplier.toString()),
                                    style = MaterialTheme.typography.titleSmall, color = rarityColor(Rarity.LEGENDARY), fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                if (welcome > 0) stringResource(R.string.dig_welcome, welcome) else stringResource(R.string.dig_how),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(rows, key = { it }) { row ->
                Column {
                    if (row % DigGame.LAYER_ROWS == 0) {
                        Text(
                            stringResource(R.string.dig_layer_cost, stringResource(layerName(DigGame.layer(row))), DigGame.costMinutes(row, state.tool)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = if (row == 0) 0.dp else 10.dp, bottom = 4.dp, start = 2.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until DigGame.COLS) Block(row, col, state, Modifier.weight(1f)) { tap(row, col) }
                    }
                }
            }
        }
    }

    when (sheet) {
        "tools" -> ModalBottomSheet(onDismissRequest = { sheet = null }) { ToolsSheet(state) { graph.dig.buyTool() } }
        "crates" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            CratesSheet(state, buy = { graph.dig.buyCrate(it) }, open = { reveal = Reveal.Opening(it, 0) })
        }
        "museum" -> ModalBottomSheet(onDismissRequest = { sheet = null }) { MuseumSheet(state) }
    }

    when (val r = reveal) {
        is Reveal.Found -> RevealDialog({ reveal = null }) { FindReveal(r.find, r.isNew, r.count) { reveal = null } }
        is Reveal.CrateFound -> RevealDialog({ reveal = null }) {
            Image(painterResource(crateArt(r.type)), null, Modifier.size(120.dp))
            Text(stringResource(R.string.dig_found_crate), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(crateName(r.type)) + " · " + stringResource(R.string.dig_found_crate_text),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { reveal = null }) { Text(stringResource(R.string.dig_nice)) }
                Button(onClick = { reveal = Reveal.Opening(r.type, 0) }) { Text(stringResource(R.string.dig_open, state.crates(r.type))) }
            }
        }
        is Reveal.Opening -> RevealDialog({ reveal = null }) {
            CrateOpening(r, left = state.crates(r.type), again = { reveal = Reveal.Opening(r.type, r.round + 1) }, done = { reveal = null })
        }
        null -> Unit
    }
}

/** One of the three buttons above the wall: a drawing, a name and a small count. */
@Composable
private fun Shortcut(art: Int, label: String, badge: String?, modifier: Modifier, badgeIsCount: Boolean = true, onClick: () -> Unit) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(art), null, Modifier.size(40.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (badge != null && !badgeIsCount) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge != null && badgeIsCount) {
            Text(
                badge, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFD9453C)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

private fun DrawScope.star(center: Offset, radius: Float, color: Color) {
    val k = radius * 0.24f
    val p = Path().apply {
        moveTo(center.x, center.y - radius); lineTo(center.x + k, center.y - k); lineTo(center.x + radius, center.y)
        lineTo(center.x + k, center.y + k); lineTo(center.x, center.y + radius); lineTo(center.x - k, center.y + k)
        lineTo(center.x - radius, center.y); lineTo(center.x - k, center.y - k); close()
    }
    drawPath(p, color)
}

/**
 * One block of the wall. Bright when it can be dug (with its price), dimmed when buried, a
 * dark hole once dug. A glint in the rarity's colour gives away a find; a white one, a crate.
 */
@Composable
private fun Block(row: Int, col: Int, state: DigGame.State, modifier: Modifier, onTap: () -> Boolean) {
    val dug = state.isDug(row, col)
    val exposed = !dug && state.exposed(row, col)
    val content = remember(state.seed, row, col) { DigGame.content(state.seed, row, col) }
    val solid by animateFloatAsState(if (dug) 0f else 1f, tween(260), label = "block")
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val base = layerColor(row)
    val glint = when (content) {
        is Content.Treasure -> rarityColor(content.find.rarity)
        is Content.Crate -> Color.White
        Content.Nothing -> null
    }
    Box(
        modifier.aspectRatio(1f).padding(1.5.dp).graphicsLayer { translationX = shake.value }
            .clip(RoundedCornerShape(7.dp)).background(lerp(base, Color.Black, 0.66f))
            .clickable(enabled = exposed) {
                if (!onTap()) scope.launch { for (x in listOf(-9f, 9f, -6f, 6f, 0f)) shake.animateTo(x, tween(45)) }
            },
    ) {
        if (solid > 0f) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 0.55f + 0.45f * solid; scaleY = 0.55f + 0.45f * solid; alpha = solid; rotationZ = (1f - solid) * 16f
                },
            ) {
                val r = CornerRadius(size.minDimension * 0.12f)
                drawRoundRect(base, cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.14f), size = Size(size.width, size.height * 0.2f), cornerRadius = r)
                drawRoundRect(Color.Black.copy(alpha = 0.16f), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f), cornerRadius = r)
                val random = Random(row * 131 + col * 17 + 5)
                repeat(7) {
                    drawCircle(
                        if (it % 2 == 0) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.14f),
                        radius = size.minDimension * (0.03f + random.nextFloat() * 0.05f),
                        center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                    )
                }
                if (glint != null) {
                    val c = Offset(size.width * (0.3f + random.nextFloat() * 0.4f), size.height * (0.28f + random.nextFloat() * 0.3f))
                    drawCircle(glint.copy(alpha = 0.35f), size.minDimension * 0.2f, c)
                    star(c, size.minDimension * 0.2f, glint)
                    star(c, size.minDimension * 0.1f, Color.White)
                }
                if (!exposed) drawRoundRect(Color.Black.copy(alpha = 0.42f), cornerRadius = r)
            }
        }
        if (exposed) {
            Text(
                DigGame.costMinutes(row, state.tool).toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun RevealDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/** A find popping out with its rarity's glow. */
@Composable
private fun FindArt(find: DigGame.Find, size: Int = 132) {
    val pop = remember(find) { Animatable(0.2f) }
    LaunchedEffect(find) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    val color = rarityColor(find.rarity)
    Box(Modifier.size((size + 48).dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pop.value.coerceIn(0f, 1f) }.background(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
        Image(
            painterResource(DIG_FIND_ART[find.id] ?: R.drawable.dig_minute), null,
            Modifier.size(size.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -30f },
        )
    }
}

@Composable
private fun RarityChip(rarity: Rarity) {
    Text(
        stringResource(rarityName(rarity)).uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(rarityColor(rarity)).padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun FindText(find: DigGame.Find, isNew: Boolean, count: Int) {
    Text(stringResource(DIG_FIND_NAME[find.id] ?: R.string.dig_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    RarityChip(find.rarity)
    Text(
        if (isNew) stringResource(R.string.dig_found_new) else stringResource(R.string.dig_found_again, count, find.rarity.refundMinutes),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

@Composable
private fun FindReveal(find: DigGame.Find, isNew: Boolean, count: Int, done: () -> Unit) {
    FindArt(find)
    FindText(find, isNew, count)
    Button(onClick = done) { Text(stringResource(R.string.dig_nice)) }
}

/** A crate shaking, then bursting open on its find. */
@Composable
private fun CrateOpening(opening: Reveal.Opening, left: Int, again: () -> Unit, done: () -> Unit) {
    val graph = LocalContext.current.graph
    val haptic = LocalHapticFeedback.current
    var opened by remember(opening) { mutableStateOf<DigGame.Opened?>(null) }
    val shake = remember(opening) { Animatable(0f) }
    LaunchedEffect(opening) {
        for (i in 0 until 9) shake.animateTo(if (i % 2 == 0) -7f - i else 7f + i, tween(70))
        shake.animateTo(0f, tween(60))
        opened = graph.dig.openCrate(opening.type)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (opened == null) { delay(50); done() }
    }
    val result = opened
    if (result == null) {
        Image(painterResource(crateArt(opening.type)), null, Modifier.size(150.dp).graphicsLayer { rotationZ = shake.value; scaleX = 1f + kotlin.math.abs(shake.value) / 120f; scaleY = scaleX })
        Text(stringResource(crateName(opening.type)), style = MaterialTheme.typography.titleLarge)
    } else {
        Image(painterResource(crateArt(opening.type, open = true)), null, Modifier.size(64.dp))
        FindArt(result.find)
        FindText(result.find, result.isNew, result.state.finds[result.find.id] ?: 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = done) { Text(stringResource(R.string.dig_nice)) }
            if (left > 0) Button(onClick = again) { Text(stringResource(R.string.dig_open_another)) }
        }
    }
}

@Composable
private fun SheetColumn(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun ToolsSheet(state: DigGame.State, buy: () -> Boolean) {
    SheetColumn(stringResource(R.string.dig_tools)) {
        val next = DigGame.nextTool(state)
        for (tool in Tool.entries) {
            val owned = tool.ordinal <= state.tool.ordinal
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .background(if (tool == state.tool) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(toolArt(tool)), null, Modifier.size(52.dp).graphicsLayer { alpha = if (owned || tool == next) 1f else 0.45f })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(toolName(tool)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (tool == Tool.WOOD) stringResource(R.string.dig_tool_first) else stringResource(R.string.dig_tool_power, power(tool)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    tool == state.tool -> Text(stringResource(R.string.dig_in_use), style = MaterialTheme.typography.labelLarge)
                    owned -> Text("✓", style = MaterialTheme.typography.titleMedium)
                    tool == next -> Button(onClick = { buy() }, enabled = state.minutes >= tool.priceMinutes) { Text(stringResource(R.string.dig_buy, tool.priceMinutes)) }
                    else -> Text(stringResource(R.string.dig_minutes, tool.priceMinutes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CratesSheet(state: DigGame.State, buy: (CrateType) -> Boolean, open: (CrateType) -> Unit) {
    SheetColumn(stringResource(R.string.dig_crates)) {
        Text(stringResource(R.string.dig_crate_earn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.dig_crate_next, ((DigGame.CRATE_EVERY_MS - state.crateProgressMs + MINUTE - 1) / MINUTE).toInt()),
            style = MaterialTheme.typography.labelLarge,
        )
        for (type in CrateType.entries) {
            val owned = state.crates(type)
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(crateArt(type)), null, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(crateName(type)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.dig_odds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DigGame.crateOdds(type, state.deepest).forEachIndexed { i, odds ->
                                if (odds > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(rarityColor(Rarity.entries[i]), CircleShape))
                                    Text(" $odds%", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { buy(type) }, enabled = state.minutes >= type.priceMinutes, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dig_buy, type.priceMinutes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { open(type) }, enabled = owned > 0, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dig_open, owned), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in Rarity.entries) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(rarityColor(r), CircleShape))
                Text(" " + stringResource(rarityName(r)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MuseumSheet(state: DigGame.State) {
    SheetColumn(stringResource(R.string.dig_museum)) {
        Text(
            stringResource(R.string.dig_museum_count, state.finds.size, DigGame.FINDS.size),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (rarity in Rarity.entries) {
            val finds = DigGame.FINDS.filter { it.rarity == rarity }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                RarityChip(rarity)
                Spacer(Modifier.width(8.dp))
                Text("${finds.count { it.id in state.finds }}/${finds.size}", style = MaterialTheme.typography.labelLarge)
            }
            for (line in finds.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (find in line) {
                        val count = state.finds[find.id] ?: 0
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
                                    .then(if (count > 0) Modifier.border(2.dp, rarityColor(rarity), MaterialTheme.shapes.medium) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painterResource(DIG_FIND_ART[find.id] ?: R.drawable.dig_minute), null, Modifier.fillMaxSize().padding(8.dp),
                                    colorFilter = if (count > 0) null else ColorFilter.tint(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                                )
                                if (count > 1) {
                                    Text(
                                        "×$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                    )
                                }
                            }
                            Text(
                                if (count > 0) stringResource(DIG_FIND_NAME[find.id] ?: R.string.dig_title) else "?",
                                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.height(30.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
