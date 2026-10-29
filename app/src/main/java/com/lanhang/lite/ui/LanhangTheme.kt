package com.lanhang.lite.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF084168),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E9F7),
    onPrimaryContainer = Color(0xFF052F4A),
    secondary = Color(0xFF084168),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E9F7),
    onSecondaryContainer = Color(0xFF052F4A),
    background = Color(0xFFF6FAFC),
    onSurface = Color(0xFF10181F),
    surfaceContainerHigh = Color(0xFFE3EDF3),
    outlineVariant = Color(0xFFC2D0D9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED8F8),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF103D58),
    onPrimaryContainer = Color(0xFFC7E9FA),
    secondary = Color(0xFF8ED8F8),
    onSecondary = Color(0xFF00344D),
    secondaryContainer = Color(0xFF103D58),
    onSecondaryContainer = Color(0xFFC7E9FA),
    background = Color(0xFF0D1418),
    onSurface = Color(0xFFDCE6ED),
    surfaceContainerHigh = Color(0xFF1B262E),
    outlineVariant = Color(0xFF3D4C56),
)

@Composable
fun LanhangTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
