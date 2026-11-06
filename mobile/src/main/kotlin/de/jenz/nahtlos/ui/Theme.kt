package de.jenz.nahtlos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val HoppOrange = Color(0xFFFF7A1A)
private val Ink = Color(0xFF0B0B0D)
private val InkRaised = Color(0xFF161618)
private val Paper = Color(0xFFF4F1EB)
private val CreamCard = Color(0xFFFFFFFF)

private val DarkColors = darkColorScheme(
    primary = HoppOrange,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = Color(0xFF5A3208),
    onPrimaryContainer = Color(0xFFFFE4CC),
    secondary = Color(0xFFB7B7C2),
    onSecondary = Color(0xFF1A1A1E),
    secondaryContainer = Color(0xFF2A2A30),
    onSecondaryContainer = Color(0xFFF4F1EB),
    background = Ink,
    onBackground = Color(0xFFF6F4F0),
    surface = Ink,
    onSurface = Color(0xFFF6F4F0),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFB9B6AE),
    surfaceContainer = InkRaised,
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF690100),
    outline = Color(0xFF3A3A40),
)

private val LightColors = lightColorScheme(
    primary = HoppOrange,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = Color(0xFFFFE1C7),
    onPrimaryContainer = Color(0xFF3A2208),
    secondary = Color(0xFF3A3A40),
    onSecondary = Color(0xFFF6F4F0),
    secondaryContainer = Color(0xFFE7E2D8),
    onSecondaryContainer = Color(0xFF1A1A1E),
    background = Paper,
    onBackground = Color(0xFF141416),
    surface = Paper,
    onSurface = Color(0xFF141416),
    surfaceVariant = CreamCard,
    onSurfaceVariant = Color(0xFF5C5852),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFFD5D0C6),
)

private val NahtlosShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NahtlosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = NahtlosShapes,
        content = content,
    )
}
