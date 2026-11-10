package com.ascolta.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * LingoPod's building blocks for a calm, minimal look: big bold titles, dark rounded cards,
 * full-width pill buttons, icon tiles and a floating navigation bar.
 */

/** Big bold screen title with an optional grey subtitle. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 4.dp).size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full-width pill button in the accent colour. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** A rounded card holding a group of rows, with an optional heading. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        content()
    }
}

/** Square icon tile used at the start of rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp)) }
}

/** A row in a [SectionCard]: icon tile, title (and subtitle), and a chevron, value or switch. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            checked != null -> Switch(checked = checked, onCheckedChange = { onClick() })
            value != null -> Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Grid tile: a round icon (or picture) above a label of at most two lines. Selected tiles get
 * the accent ring and a check.
 */
@Composable
fun SelectTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emoji: String? = null,
    imageUrl: String? = null,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, label = "tile",
    )
    Box(
        modifier
            .aspectRatio(0.84f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, border, MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    imageUrl != null -> coil3.compose.AsyncImage(
                        // 64 dp circle: a ~200-pixel copy is plenty.
                        model = com.ascolta.app.net.Artwork.sized(imageUrl, with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.roundToPx() }), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                    emoji != null -> Text(emoji, style = MaterialTheme.typography.headlineMedium)
                    icon != null -> Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

/** Onboarding header: one dash per step (done ones bright) and an optional Skip link. */
@Composable
fun StepHeader(step: Int, steps: Int, onSkip: (() -> Unit)?, skipLabel: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            repeat(steps) { i ->
                Box(
                    Modifier.width(28.dp).height(5.dp).clip(CircleShape)
                        .background(if (i <= step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        if (onSkip != null) {
            TextButton(onClick = onSkip) {
                Text(skipLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating pill navigation bar; the current item sits in its own pill in the accent colour. */
@Composable
fun FloatingNavBar(items: List<NavItem>, current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { item ->
            val selected = item.route == current
            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(item.route) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A podcast cover: square, slightly rounded, as big as its column. Optional corner marks: the
 * number of new episodes, a ✓ when followed or picked, a ★ for favourites; optional one-line
 * title under it. A long press shows the full name.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CoverTile(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    checked: Boolean = false,
    favourite: Boolean = false,
    showTitle: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.roundToPx() }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { android.widget.Toast.makeText(context, title, android.widget.Toast.LENGTH_SHORT).show() },
                ),
        ) {
            // The name, shown until (or instead of) the picture.
            Text(
                title, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
            if (imageUrl != null) {
                coil3.compose.AsyncImage(
                    model = com.ascolta.app.net.Artwork.sized(imageUrl, px), contentDescription = title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            when {
                checked -> Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                badge > 0 -> Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomStart = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (favourite) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Star, null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), CircleShape).padding(2.dp),
                )
            }
        }
        if (showTitle) {
            Text(
                title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
    }
}

/** A filter as plain text with a small arrow ("All categories ▾"); in the accent colour when set. */
@Composable
fun FilterMenu(label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        Icon(androidx.compose.material.icons.Icons.Filled.ArrowDropDown, null, tint = color)
    }
}

/** A bottom sheet listing choices (for [FilterMenu]). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScrollable().padding(bottom = 24.dp),
        ) { content() }
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** A choice in a [MenuSheet]: optional icon, name, and a check when selected. */
@Composable
fun MenuSheetItem(text: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun MenuSheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
}
