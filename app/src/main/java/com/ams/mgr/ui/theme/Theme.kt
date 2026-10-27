package com.ams.mgr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

// Premium AMS palette: deep forest background, emerald primary, warm gold accent.
private val Emerald = Color(0xFF20C997)
private val EmeraldBright = Color(0xFF38E0A7)
private val EmeraldDeep = Color(0xFF087F5B)
private val Forest = Color(0xFF061713)
private val ForestSurface = Color(0xFF0B211B)
private val ForestSurface2 = Color(0xFF102A22)
private val Mint = Color(0xFFD6F8E8)
private val Gold = Color(0xFFFFC857)
private val GoldSoft = Color(0xFFFFE6A6)
private val Ink = Color(0xFFEAF7F1)
private val Muted = Color(0xFFA9C0B6)
private val Outline = Color(0xFF315A4B)
private val Error = Color(0xFFFFB4AB)

private val DarkColors = darkColorScheme(
    primary = EmeraldBright,
    onPrimary = Color(0xFF003A2A),
    primaryContainer = Color(0xFF075B43),
    onPrimaryContainer = Mint,
    secondary = Gold,
    onSecondary = Color(0xFF3B2A00),
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = GoldSoft,
    tertiary = Color(0xFF9ED7FF),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF004D70),
    onTertiaryContainer = Color(0xFFC9E8FF),
    background = Forest,
    onBackground = Ink,
    surface = ForestSurface,
    onSurface = Ink,
    surfaceVariant = ForestSurface2,
    onSurfaceVariant = Muted,
    outline = Outline,
    outlineVariant = Color(0xFF1E3D32),
    error = Error
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFF3DC),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF8A6500),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7A8),
    onSecondaryContainer = Color(0xFF2B2100),
    tertiary = Color(0xFF00628A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC5E8FF),
    onTertiaryContainer = Color(0xFF001E2D),
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF14231D),
    surface = Color.White,
    onSurface = Color(0xFF14231D),
    surfaceVariant = Color(0xFFE3F1EB),
    onSurfaceVariant = Color(0xFF52645C),
    outline = Color(0xFF8BA39A),
    outlineVariant = Color(0xFFC7D8D1),
    error = Color(0xFFBA1A1A)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun SchoolManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
