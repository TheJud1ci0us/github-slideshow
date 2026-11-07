package com.ascolta.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1F6F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F0DA),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFFB4532A),
    secondaryContainer = Color(0xFFFFDBCC),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CD4BE),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFA8F0DA),
    secondary = Color(0xFFFFB596),
    secondaryContainer = Color(0xFF8A3A16),
)

@Composable
fun AscoltaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
