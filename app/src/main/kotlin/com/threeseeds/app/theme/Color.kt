package com.threeseeds.app.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Player colors: warm vs. cool, not just different hues on a single
// axis, so they stay distinguishable under most color-vision
// deficiencies. This is a backup, not the primary accessibility
// mechanism — BoardCanvas draws Player Two as a distinct ring shape,
// not a solid disc, so identity never depends on color perception alone.
val PlayerOneColor = Color(0xFFFFB020) // amber
val PlayerOneDeep = Color(0xFFF57C00) // amber, shaded end of the seed gradient
val PlayerTwoColor = Color(0xFF3ED8F0) // cyan
val PlayerTwoDeep = Color(0xFF00A5C4) // cyan, shaded end of the seed gradient

val BoardLineColor = Color(0xFF7C87FF) // periwinkle lines
val BoardLineGlowColor = Color(0x667C87FF)
val BoardNodeColor = Color(0xFFA9B0FF)
val WinningLineColor = Color(0xFFFFD54F)
val LegalDestinationColor = Color(0xFF69F0AE)
val InvalidFlashColor = Color(0xFFFF5252)
val AccentMagenta = Color(0xFFFF4081)

val BackgroundDark = Color(0xFF0D1026) // deep indigo night
val SurfaceDark = Color(0xFF191D42)
val BackgroundLight = Color(0xFFF3F1FF)
val SurfaceLight = Color(0xFFFFFFFF)

/** Full-screen wash used behind every screen — a two-stop vertical gradient. */
fun appBackgroundBrush(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(listOf(BackgroundDark, Color(0xFF241B4D), Color(0xFF101338)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFFFFFFF), BackgroundLight, Color(0xFFE7E3FF)))
}

/** Play-button / title accent gradient. */
val AccentBrush: Brush
    get() = Brush.linearGradient(listOf(PlayerOneColor, AccentMagenta, PlayerTwoColor))
