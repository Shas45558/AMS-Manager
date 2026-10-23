package com.ams.mgr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val Navy = Color(0xFF172554)
private val DeepNavy = Color(0xFF0B1537)
private val Teal = Color(0xFF0F9D91)
private val PaleTeal = Color(0xFFD9F7F1)
private val Cloud = Color(0xFFF4F7FC)
private val Slate = Color(0xFF53627A)
private val DarkBackground = Color(0xFF0B1220)
private val DarkSurface = Color(0xFF121D30)
private val DarkTeal = Color(0xFF55E0C1)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = PaleTeal,
    onPrimaryContainer = DeepNavy,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF7F2),
    onSecondaryContainer = Color(0xFF073F3B),
    tertiary = Color(0xFF6750A4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF25104D),
    background = Cloud,
    onBackground = Color(0xFF17243A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17243A),
    surfaceVariant = Color(0xFFE9EEF7),
    onSurfaceVariant = Slate,
    outline = Color(0xFFB8C4D8),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = DarkTeal,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF174D49),
    onPrimaryContainer = Color(0xFFBDF7E9),
    secondary = Color(0xFF9BBEFF),
    onSecondary = Color(0xFF152E59),
    secondaryContainer = Color(0xFF263F69),
    onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFFD0BCFF),
    onTertiary = Color(0xFF381E72),
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = DarkBackground,
    onBackground = Color(0xFFE8EEFC),
    surface = DarkSurface,
    onSurface = Color(0xFFE8EEFC),
    surfaceVariant = Color(0xFF243149),
    onSurfaceVariant = Color(0xFFB8C5DE),
    outline = Color(0xFF52617C),
    error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SchoolManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
