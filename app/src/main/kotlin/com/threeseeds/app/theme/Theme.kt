package com.threeseeds.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    background = BackgroundDark,
    surface = SurfaceDark,
    primary = PlayerOneColor,
    secondary = PlayerTwoColor
)

private val LightColors = lightColorScheme(
    background = BackgroundLight,
    surface = SurfaceLight,
    primary = PlayerOneColor,
    secondary = PlayerTwoColor
)

@Composable
fun ThreeSeedsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ThreeSeedsTypography,
        content = content
    )
}
