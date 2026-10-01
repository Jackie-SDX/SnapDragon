package com.threeseeds.app.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A [GameTheme] carries its own background wash, so the Material color
 * scheme must follow the THEME, not the system. Deriving it from the
 * theme's background luminance keeps text readable on every appearance
 * — light themes get dark text, dark themes get light text — instead of
 * trusting a device setting that has nothing to do with the art.
 */
private fun schemeFor(theme: GameTheme): androidx.compose.material3.ColorScheme {
    val avgLuminance = theme.backgroundStops.map { it.luminance() }.average().toFloat()
    return if (avgLuminance < 0.5f) {
        darkColorScheme(
            background = theme.backgroundStops.first(),
            onBackground = Color.White,
            surface = theme.surfaceColor,
            onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.78f),
            primary = PlayerOneColor,
            onPrimary = Color(0xFF1A1200),
            secondary = PlayerTwoColor,
            outline = Color.White.copy(alpha = 0.35f),
        )
    } else {
        lightColorScheme(
            background = theme.backgroundStops.first(),
            onBackground = Color(0xFF14100E),
            surface = theme.surfaceColor,
            onSurface = Color(0xFF14100E),
            onSurfaceVariant = Color(0xFF14100E).copy(alpha = 0.72f),
            primary = PlayerOneColor,
            onPrimary = Color(0xFF1A1200),
            secondary = PlayerTwoColor,
            outline = Color(0xFF14100E).copy(alpha = 0.40f),
        )
    }
}

@Composable
fun ThreeSeedsTheme(
    theme: GameTheme = ThemeCatalog.DEFAULT,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = schemeFor(theme),
        typography = ThreeSeedsTypography
    ) {
        // Bare Text() falls back to LocalContentColor, whose default is
        // Color.Black — without this the whole app renders black-on-dark
        // on a dark theme. Materials' Surface normally supplies it; the
        // screens here draw their own wash, so the theme root does.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}
