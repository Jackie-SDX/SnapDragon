package com.threeseeds.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Ambient motifs that animate over the gradient wash. */
enum class Motif {
    NONE,
    PETALS,
    EMBERS,
    STARS,
    SNOW,
    BUBBLES,
    LEAVES,
    SPARKLES,
    RAIN,
    FIREFLIES,
    BOKEH,
    FLOWERS,
    SCALES,
    CONFETTI,
    GEOMETRIC
}

/**
 * One selectable board appearance: background gradient, board/seed
 * colors, accent, and the ambient motif drawn over it. Themes only
 * re-skin — functional feedback colors (legal destination, invalid
 * flash, winning line) stay fixed so their meaning never changes.
 */
data class GameTheme(
    val id: String,
    val name: String,
    /** Coin price; 0 means everyone starts with it. */
    val cost: Int,
    val backgroundStops: List<Color>,
    val lineColor: Color,
    val lineGlowColor: Color,
    val nodeColor: Color,
    val playerOne: Color,
    val playerOneDeep: Color,
    val playerTwo: Color,
    val playerTwoDeep: Color,
    val surfaceColor: Color,
    val accentColor: Color,
    val motif: Motif,
    val motifColor: Color,
) {
    fun backgroundBrush(): Brush = Brush.verticalGradient(backgroundStops)

    /** Title / primary CTA gradient derived from the theme's own palette. */
    fun accentBrush(): Brush =
        Brush.linearGradient(listOf(accentColor, playerOne, playerTwo))
}

/** Current theme for composables that draw themed colors. */
val LocalGameTheme = staticCompositionLocalOf { ThemeCatalog.DEFAULT }

val GameTheme.isFree: Boolean get() = cost == 0
