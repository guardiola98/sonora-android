package org.sonora.music.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFC5B4FF), onPrimary = Color(0xFF291C4C),
    primaryContainer = Color(0xFF362957), onPrimaryContainer = Color(0xFFEAE0FF),
    secondary = Color(0xFFFFBEA4), onSecondary = Color(0xFF4B281A),
    background = Color(0xFF0D1017), onBackground = Color(0xFFF2EFF8),
    surface = Color(0xFF141721), onSurface = Color(0xFFF2EFF8),
    surfaceVariant = Color(0xFF292C3A), onSurfaceVariant = Color(0xFFB8B5C7),
    outline = Color(0xFF777383)
)
@Composable fun SonoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
