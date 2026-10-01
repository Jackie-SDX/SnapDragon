package com.threeseeds.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.threeseeds.app.theme.InvalidFlashColor
import com.threeseeds.app.theme.LegalDestinationColor
import com.threeseeds.app.theme.LocalGameTheme
import com.threeseeds.app.theme.WinningLineColor
import com.threeseeds.engine.AdjacencyGraph
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlinx.coroutines.launch

private val MIN_TOUCH_TARGET = 48.dp

@Composable
fun BoardCanvas(
    gameState: GameState,
    selectedSeed: Position?,
    legalDestinations: Set<Position>,
    movableSeeds: Set<Position>,
    invalidFlashPosition: Position?,
    onPointTapped: (Position) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "board_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse"
    )
    val theme = LocalGameTheme.current
    val reduceMotion = LocalReduceMotion.current

    // Pop-in: a seed that just landed springs up from scale 0. A vacated
    // point forgets its animation so a re-placed seed pops again.
    val seedPops = remember { mutableStateMapOf<Position, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
    LaunchedEffect(gameState.board) {
        for (position in Position.ALL) {
            if (gameState.board[position] != null) {
                if (position !in seedPops && !reduceMotion) {
                    val anim = Animatable(0f)
                    seedPops[position] = anim
                    launch {
                        anim.animateTo(1f, spring(dampingRatio = 0.45f))
                    }
                } else if (position !in seedPops) {
                    seedPops[position] = Animatable(1f)
                }
            } else {
                seedPops.remove(position)
            }
        }
    }

    // Win-line sweep: the winning stroke draws itself across the board
    // the moment the game is decided, then hands over to the pulse.
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(gameState.winningLine) {
        val line = gameState.winningLine
        if (line == null) {
            sweep.snapTo(0f)
        } else if (reduceMotion) {
            sweep.snapTo(1f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(550))
        }
    }
    val sweepValue = sweep.value

    BoxWithConstraints(modifier = modifier) {
        val boardSizeDp = minOf(maxWidth, maxHeight)
        val density = LocalDensity.current
        val boardSizePx = with(density) { boardSizeDp.toPx() }
        val paddingPx = boardSizePx * 0.14f
        val usablePx = boardSizePx - 2 * paddingPx

        val centers = remember(boardSizePx) {
            Position.ALL.associateWith { position ->
                val col = position.index % 3
                val row = position.index / 3
                Offset(paddingPx + col * (usablePx / 2f), paddingPx + row * (usablePx / 2f))
            }
        }
        val seedRadiusPx = usablePx * 0.11f

        Canvas(
            modifier = Modifier
                .size(boardSizeDp)
                .semantics { contentDescription = "" } // decorative only; the overlay below carries real semantics
        ) {
            // Backdrop panel: a soft rounded card so the board reads as an object.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(theme.surfaceColor.copy(alpha = 0.55f), theme.surfaceColor.copy(alpha = 0.25f))
                ),
                cornerRadius = CornerRadius(boardSizePx * 0.06f)
            )

            // Connection lines, driven by the same AdjacencyGraph the engine
            // uses for legality — if that graph changes, the drawing follows.
            // Drawn twice: a wide translucent glow, then the crisp core line.
            for (a in Position.ALL) {
                for (b in AdjacencyGraph.neighborsOf(a)) {
                    if (b.index > a.index) {
                        val start = centers.getValue(a)
                        val end = centers.getValue(b)
                        drawLine(theme.lineGlowColor, start, end, strokeWidth = 14f)
                        drawLine(theme.lineColor, start, end, strokeWidth = 3f)
                    }
                }
            }

            // Node dots at every point, so the board still reads as a grid
            // before any seed has been placed.
            for (position in Position.ALL) {
                drawCircle(theme.nodeColor.copy(alpha = 0.85f), radius = 5f, center = centers.getValue(position))
            }

            // Guide lines from the selected seed to each legal destination.
            selectedSeed?.let { from ->
                val start = centers.getValue(from)
                for (dest in legalDestinations) {
                    drawLine(
                        LegalDestinationColor.copy(alpha = 0.35f * pulse),
                        start,
                        centers.getValue(dest),
                        strokeWidth = 6f
                    )
                }
            }

            // Winning line, drawn as a sweep across the line, under the seeds.
            gameState.winningLine?.let { line ->
                val segments = line.size - 1
                val progress = sweepValue * segments
                for (i in 0 until segments) {
                    val fraction = (progress - i).coerceIn(0f, 1f)
                    if (fraction <= 0f) continue
                    val start = centers.getValue(line[i])
                    val end = centers.getValue(line[i + 1])
                    drawLine(
                        color = WinningLineColor.copy(alpha = pulse),
                        start = start,
                        end = start + (end - start) * fraction,
                        strokeWidth = seedRadiusPx * 0.5f
                    )
                }
            }

            // Legal destination markers: soft fill plus a pulsing ring.
            for (dest in legalDestinations) {
                val center = centers.getValue(dest)
                drawCircle(LegalDestinationColor.copy(alpha = 0.22f * pulse), seedRadiusPx * 0.62f, center)
                drawCircle(
                    color = LegalDestinationColor.copy(alpha = pulse),
                    radius = seedRadiusPx * 0.55f,
                    center = center,
                    style = Stroke(width = seedRadiusPx * 0.18f)
                )
            }

            // Movable own seeds get a faint idle ring so the player can see
            // what is actionable before selecting anything.
            if (selectedSeed == null) {
                for (movable in movableSeeds) {
                    drawCircle(
                        color = LegalDestinationColor.copy(alpha = 0.45f * pulse),
                        radius = seedRadiusPx * 1.45f,
                        center = centers.getValue(movable),
                        style = Stroke(width = seedRadiusPx * 0.12f)
                    )
                }
            }

            // Seeds. Player ONE is a solid disc; Player TWO is a ring —
            // a real shape difference, not just a different color, so
            // identity survives grayscale or color-vision deficiency.
            for (position in Position.ALL) {
                val occupant = gameState.board[position] ?: continue
                val center = centers.getValue(position)
                val isWinning = gameState.winningLine?.contains(position) == true
                val isSelected = position == selectedSeed
                val pop = seedPops[position]?.value ?: if (reduceMotion) 1f else 0f
                val baseRadius = seedRadiusPx * (0.25f + 0.75f * pop)
                val radius = if (isSelected) baseRadius * (0.95f + 0.1f * pulse) else baseRadius

                if (isWinning) {
                    drawCircle(WinningLineColor.copy(alpha = pulse), radius * 1.35f, center)
                }
                if (isSelected) {
                    drawCircle(
                        LegalDestinationColor.copy(alpha = pulse),
                        radius * 1.3f,
                        center,
                        style = Stroke(seedRadiusPx * 0.15f)
                    )
                }

                // Drop shadow so the seeds sit above the board.
                drawCircle(Color.Black.copy(alpha = 0.30f), radius, center + Offset(0f, radius * 0.18f))

                when (occupant) {
                    Player.ONE -> drawGlossyDisc(center, radius, theme.playerOne, theme.playerOneDeep)
                    Player.TWO -> drawGlossyRing(center, radius, theme.playerTwo, theme.playerTwoDeep)
                }
            }

            // A brief red flash on a point that just rejected a move.
            if (invalidFlashPosition != null) {
                drawCircle(
                    InvalidFlashColor.copy(alpha = 0.6f),
                    seedRadiusPx * 1.1f,
                    centers.getValue(invalidFlashPosition),
                    style = Stroke(seedRadiusPx * 0.2f)
                )
            }
        }

        // The real accessibility layer: one labeled, adequately-sized
        // tappable target per point, independent of how small the drawn
        // circle is. All touch input goes through here — the Canvas
        // above never receives taps.
        for (position in Position.ALL) {
            val center = centers.getValue(position)
            val targetPx = with(density) { MIN_TOUCH_TARGET.toPx() }
            val topLeftPx = Offset(center.x - targetPx / 2f, center.y - targetPx / 2f)
            val description = describePoint(position, gameState, selectedSeed, legalDestinations)

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { topLeftPx.x.toDp() },
                        y = with(density) { topLeftPx.y.toDp() }
                    )
                    .size(MIN_TOUCH_TARGET)
                    .clip(CircleShape)
                    .clickable(onClickLabel = description) { onPointTapped(position) }
                    .semantics { contentDescription = description }
            )
        }
    }
}

/** Player ONE's seed: solid disc with a top-left gloss highlight. */
private fun DrawScope.drawGlossyDisc(center: Offset, radius: Float, color: Color, deep: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, deep),
            center = center + Offset(-radius * 0.3f, -radius * 0.3f),
            radius = radius * 1.5f
        ),
        radius = radius,
        center = center
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = radius * 0.30f,
        center = center + Offset(-radius * 0.35f, -radius * 0.38f)
    )
}

/** Player TWO's seed: ring with a gradient stroke and a glossy tick. */
private fun DrawScope.drawGlossyRing(center: Offset, radius: Float, color: Color, deep: Color) {
    val stroke = radius * 0.42f
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(color, deep),
            start = center + Offset(-radius, -radius),
            end = center + Offset(radius, radius)
        ),
        radius = radius - stroke / 2f,
        center = center,
        style = Stroke(width = stroke)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.50f),
        radius = radius * 0.20f,
        center = center + Offset(-radius * 0.55f, -radius * 0.55f)
    )
    // Inner disc keeps the ring readable against light backgrounds.
    drawCircle(
        color = deep.copy(alpha = 0.18f),
        radius = radius * 0.42f,
        center = center
    )
}

private fun describePoint(
    position: Position,
    gameState: GameState,
    selectedSeed: Position?,
    legalDestinations: Set<Position>
): String {
    val occupant = gameState.board[position]
    val n = position.index + 1
    return when {
        occupant != null && gameState.winningLine?.contains(position) == true ->
            "Point $n, Player ${occupant.ordinalLabel()}'s seed, part of the winning line"
        occupant != null && position == selectedSeed ->
            "Point $n, Player ${occupant.ordinalLabel()}'s seed, selected"
        occupant != null ->
            "Point $n, Player ${occupant.ordinalLabel()}'s seed"
        position in legalDestinations ->
            "Point $n, empty, legal move"
        else ->
            "Point $n, empty"
    }
}

private fun Player.ordinalLabel(): Int = if (this == Player.ONE) 1 else 2
