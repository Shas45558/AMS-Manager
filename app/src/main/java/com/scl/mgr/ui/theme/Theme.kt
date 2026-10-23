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

private val Navy = Color(0xFF17324D)
private val DeepNavy = Color(0xFF0D2238)
private val Teal = Color(0xFF087E8B)
private val PaleTeal = Color(0xFFD9F0EF)
private val Cloud = Color(0xFFF4F7FA)
private val Slate = Color(0xFF526579)
private val DarkBackground = Color(0xFF101A24)
private val DarkSurface = Color(0xFF182633)
private val DarkTeal = Color(0xFF79D0D0)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = PaleTeal,
    onPrimaryContainer = DeepNavy,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F3F1),
    onSecondaryContainer = Color(0xFF103B42),
    tertiary = Color(0xFF477A62),
    background = Cloud,
    onBackground = Color(0xFF172635),
    surface = Color.White,
    onSurface = Color(0xFF172635),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Slate,
    outline = Color(0xFFB7C4CE),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = DarkTeal,
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF244B52),
    onPrimaryContainer = Color(0xFFD0F4F1),
    secondary = Color(0xFF9FC9E8),
    onSecondary = Color(0xFF153247),
    secondaryContainer = Color(0xFF294557),
    onSecondaryContainer = Color(0xFFD8EAF8),
    tertiary = Color(0xFFA6D5B8),
    background = DarkBackground,
    onBackground = Color(0xFFE5EDF4),
    surface = DarkSurface,
    onSurface = Color(0xFFE5EDF4),
    surfaceVariant = Color(0xFF263746),
    onSurfaceVariant = Color(0xFFB7C7D4),
    outline = Color(0xFF526575),
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
