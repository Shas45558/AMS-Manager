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

// Amtoli Model School logo palette: deep green, leaf green, red accents, and white.
private val SchoolGreen = Color(0xFF007A4D)
private val DeepGreen = Color(0xFF004D32)
private val LeafGreen = Color(0xFF00A36C)
private val PaleGreen = Color(0xFFDDF5E8)
private val MenuMint = Color(0xFFE7F7EE)
private val MenuRose = Color(0xFFFFE8E5)
private val Cloud = Color(0xFFF6FAF7)
private val Slate = Color(0xFF53665C)
private val SchoolRed = Color(0xFFB71C1C)
private val DarkBackground = Color(0xFF0B1711)
private val DarkSurface = Color(0xFF14251B)
private val DarkGreen = Color(0xFF75D6A5)

private val LightColors = lightColorScheme(
    primary = SchoolGreen,
    onPrimary = Color.White,
    primaryContainer = PaleGreen,
    onPrimaryContainer = DeepGreen,
    secondary = LeafGreen,
    onSecondary = Color.White,
    secondaryContainer = MenuMint,
    onSecondaryContainer = Color(0xFF123D29),
    tertiary = SchoolRed,
    onTertiary = Color.White,
    tertiaryContainer = MenuRose,
    onTertiaryContainer = Color(0xFF650909),
    background = Cloud,
    onBackground = Color(0xFF172B20),
    surface = Color.White,
    onSurface = Color(0xFF172B20),
    surfaceVariant = MenuMint,
    onSurfaceVariant = Slate,
    outline = Color(0xFFB7C9BC),
    error = SchoolRed
)

private val DarkColors = darkColorScheme(
    primary = DarkGreen,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF18563A),
    onPrimaryContainer = Color(0xFFBDF4D1),
    secondary = Color(0xFF8DDBAE),
    onSecondary = Color(0xFF123D29),
    secondaryContainer = Color(0xFF254C36),
    onSecondaryContainer = Color(0xFFD1F4DD),
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
    tertiaryContainer = Color(0xFF93000A),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = DarkBackground,
    onBackground = Color(0xFFE5F2E8),
    surface = DarkSurface,
    onSurface = Color(0xFFE5F2E8),
    surfaceVariant = Color(0xFF263A2D),
    onSurfaceVariant = Color(0xFFB8CCBD),
    outline = Color(0xFF52695A),
    error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
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
