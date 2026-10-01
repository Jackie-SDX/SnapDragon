package com.threeseeds.app.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.threeseeds.app.ui.LocalReduceMotion

/**
 * The UI half of the theme transformation: every board/UI color of
 * [target] animates over the same 500ms FastOutSlowIn window as the
 * background crossfade in [ThemedBackground]. Before this, the canvas
 * and Material surfaces snapped to the new palette on the very frame
 * the wash started fading, so each rotation rendered mixed-theme
 * frames (new board colors on the old backdrop) for ~500ms.
 *
 * The motif itself stays out of this — it is drawn only by the
 * background layer, which crossfades static themes independently.
 */
@Composable
fun rememberAnimatedTheme(target: GameTheme): GameTheme {
    // Under reduce-motion the palette still changes — it just snaps.
    val spec = tween<Color>(durationMillis = if (LocalReduceMotion.current) 0 else 500)

    val firstStop by animateColorAsState(target.backgroundStops.first(), spec, label = "bg0")
    val lineColor by animateColorAsState(target.lineColor, spec, label = "line")
    val lineGlowColor by animateColorAsState(target.lineGlowColor, spec, label = "lineGlow")
    val nodeColor by animateColorAsState(target.nodeColor, spec, label = "node")
    val playerOne by animateColorAsState(target.playerOne, spec, label = "playerOne")
    val playerOneDeep by animateColorAsState(target.playerOneDeep, spec, label = "playerOneDeep")
    val playerTwo by animateColorAsState(target.playerTwo, spec, label = "playerTwo")
    val playerTwoDeep by animateColorAsState(target.playerTwoDeep, spec, label = "playerTwoDeep")
    val surfaceColor by animateColorAsState(target.surfaceColor, spec, label = "surface")
    val accentColor by animateColorAsState(target.accentColor, spec, label = "accent")
    val motifColor by animateColorAsState(target.motifColor, spec, label = "motif")

    return target.copy(
        backgroundStops = listOf(firstStop) + target.backgroundStops.drop(1),
        lineColor = lineColor,
        lineGlowColor = lineGlowColor,
        nodeColor = nodeColor,
        playerOne = playerOne,
        playerOneDeep = playerOneDeep,
        playerTwo = playerTwo,
        playerTwoDeep = playerTwoDeep,
        surfaceColor = surfaceColor,
        accentColor = accentColor,
        motifColor = motifColor,
    )
}
