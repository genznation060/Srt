package com.srtcolorsection.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.srtcolorsection.studio.domain.ThemeMode

private val Ink = Color(0xFF12161C)
private val Paper = Color(0xFFF4F1EA)
private val Coral = Color(0xFFE85D4C)
private val Teal = Color(0xFF1F8A7B)

private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF2B0D09),
    secondary = Teal,
    onSecondary = Color(0xFF04221D),
    background = Ink,
    onBackground = Color(0xFFF4F7FB),
    surface = Color(0xFF1B212A),
    onSurface = Color(0xFFF4F7FB),
    surfaceVariant = Color(0xFF2A3340),
    onSurfaceVariant = Color(0xFFC5CEDA),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB23B2E),
    onPrimary = Color.White,
    secondary = Color(0xFF0F6E62),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFCF7),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E1D6),
    onSurfaceVariant = Color(0xFF3E4652),
    error = Color(0xFFBA1A1A),
)

@Composable
fun StudioTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
