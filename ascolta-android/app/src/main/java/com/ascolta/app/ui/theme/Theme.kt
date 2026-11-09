package com.ascolta.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascolta.app.graph

/**
 * LingoPod's look: near-black background, dark rounded cards, the teal accent, big bold
 * titles and quiet grey secondary text. Dark mode only.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF8CD4BE),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0E4A3D),
    onPrimaryContainer = Color(0xFFBDF3E1),
    secondary = Color(0xFFFFB596),
    secondaryContainer = Color(0xFF5C2A12),
    background = Color(0xFF0A0B0B),
    onBackground = Color(0xFFF2F4F3),
    surface = Color(0xFF0A0B0B),
    onSurface = Color(0xFFF2F4F3),
    surfaceVariant = Color(0xFF232627),
    onSurfaceVariant = Color(0xFF9EA4A2),
    surfaceContainerLowest = Color(0xFF070808),
    surfaceContainerLow = Color(0xFF121414),
    surfaceContainer = Color(0xFF17191A),
    surfaceContainerHigh = Color(0xFF1F2223),
    surfaceContainerHighest = Color(0xFF282B2C),
    outline = Color(0xFF3A3F3E),
    outlineVariant = Color(0xFF2A2E2D),
)

private val Rounded = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()
private val Type = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun AscoltaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic by context.graph.settings.dynamicColor.flow.collectAsState()
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        // Wallpaper colours, on LingoPod's black background.
        dynamicDarkColorScheme(context).copy(background = Dark.background, surface = Dark.surface)
    } else Dark
    MaterialTheme(colorScheme = colors, shapes = Rounded, typography = Type, content = content)
}
