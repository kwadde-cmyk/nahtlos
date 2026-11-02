package de.jenz.weiterhoeren.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF10120F)
private val Paper = Color(0xFFF6F3EA)
private val Amber = Color(0xFFF0A202)
private val AmberDeep = Color(0xFF8A5A00)
private val Sage = Color(0xFFB7C7A4)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFE7B8),
    secondary = Sage,
    onSecondary = Color(0xFF24301C),
    background = Ink,
    onBackground = Color(0xFFF3F1E7),
    surface = Ink,
    onSurface = Color(0xFFF3F1E7),
    surfaceVariant = Color(0xFF242922),
    onSurfaceVariant = Color(0xFFC9C6B8),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF690100),
    outline = Color(0xFF3E4538),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color(0xFFFFF8EC),
    primaryContainer = Color(0xFFFFE0A3),
    onPrimaryContainer = Color(0xFF2A1C00),
    secondary = Color(0xFF3E5234),
    onSecondary = Color(0xFFF3F7EE),
    background = Paper,
    onBackground = Color(0xFF1A1C16),
    surface = Paper,
    onSurface = Color(0xFF1A1C16),
    surfaceVariant = Color(0xFFE7E2D4),
    onSurfaceVariant = Color(0xFF45483E),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFFC8C2B2),
)

@Composable
fun WeiterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
