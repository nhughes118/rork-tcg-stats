package com.rork.tcgstatsandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Binder Showcase palette (matches the iOS app). */
object Palette {
    val Canvas = Color(0xFFF7F4EC)
    val Surface = Color(0xFFFFFFFF)
    val Red = Color(0xFFE3350D)
    val Blue = Color(0xFF2A75BB)
    val Gold = Color(0xFFE8B84A)
    val Silver = Color(0xFF9AA3AE)
    val Bronze = Color(0xFFC27C4A)
    val Ink = Color(0xFF15202E)
    val Muted = Color(0xFF6B7380)
    val Hairline = Color(0xFFECE7DC)
    val Green = Color(0xFF1F9D55)
    val CardBack = Color(0xFFEFE9DC)
    val ShadowTint = Color(0xFF6B5A3A)

    fun placeColor(placing: Int?): Color = when (placing) {
        1 -> Gold
        2 -> Silver
        3, 4 -> Bronze
        else -> Muted
    }
}

private val LightColors = lightColorScheme(
    primary = Palette.Red,
    onPrimary = Color.White,
    secondary = Palette.Blue,
    onSecondary = Color.White,
    tertiary = Palette.Gold,
    background = Palette.Canvas,
    onBackground = Palette.Ink,
    surface = Palette.Surface,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Hairline,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLow = Palette.Canvas,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface,
    outline = Palette.Hairline,
    outlineVariant = Palette.Hairline,
    error = Palette.Red,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
