package com.threeseeds.app.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen themed backdrop: an animated crossfade between the old
 * and new theme's gradient, the theme's ambient motif drifting over it,
 * then [content] on top. Screens no longer paint their own background —
 * this is the single place the board's mood is set.
 */
@Composable
fun ThemedBackground(theme: GameTheme, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = theme, animationSpec = tween(500), label = "theme_crossfade") { current ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(current.backgroundBrush())
            ) {
                ThemeMotifLayer(theme = current, modifier = Modifier.fillMaxSize())
            }
        }
        content()
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val speed: Float,
    val phase: Float,
    val scale: Float,
    val hueShift: Float,
)

/**
 * Deterministic, unit-space particle field for one motif. Positions
 * live in 0..1 and are scaled by the canvas at draw time, so the same
 * seed always renders the same field at any screen size.
 */
@Composable
private fun ThemeMotifLayer(theme: GameTheme, modifier: Modifier = Modifier) {
    if (theme.motif == Motif.NONE) return

    val transition = rememberInfiniteTransition(label = "motif")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "motif_progress"
    )
    val particles = remember(theme.motif) { particlesFor(theme.motif) }

    Canvas(modifier = modifier) {
        val dim = size.minDimension
        for (p in particles) {
            drawParticle(theme, p, progress, dim)
        }
    }
}

private fun particlesFor(motif: Motif): List<Particle> {
    val count = when (motif) {
        Motif.NONE -> 0
        Motif.BOKEH -> 9
        Motif.FLOWERS -> 8
        Motif.SCALES -> 24
        Motif.GEOMETRIC -> 26
        Motif.RAIN -> 44
        else -> 30
    }
    val rng = Random(motif.ordinal * 7919 + 13)
    return List(count) {
        Particle(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            speed = 0.35f + rng.nextFloat() * 0.65f,
            phase = rng.nextFloat() * 6.283f,
            scale = 0.55f + rng.nextFloat() * 0.9f,
            hueShift = rng.nextFloat(),
        )
    }
}

private fun DrawScope.drawParticle(theme: GameTheme, p: Particle, t: Float, dim: Float) {
    val color = theme.motifColor
    val sway = sin((t * 6.283f) + p.phase)
    val unit = dim

    when (theme.motif) {
        Motif.NONE -> Unit

        Motif.STARS, Motif.SPARKLES -> {
            // Twinkle in place: brightness oscillates, position never jumps.
            val alpha = 0.25f + 0.65f * abs(sin(t * 12.5f + p.phase))
            val r = unit * 0.012f * p.scale
            val c = Offset(p.x * size.width, p.y * size.height)
            drawCircle(color.copy(alpha = alpha), r, c)
            if (theme.motif == Motif.SPARKLES) {
                drawLine(color.copy(alpha = alpha * 0.7f), c - Offset(r * 2.4f, 0f), c + Offset(r * 2.4f, 0f), r * 0.5f)
                drawLine(color.copy(alpha = alpha * 0.7f), c - Offset(0f, r * 2.4f), c + Offset(0f, r * 2.4f), r * 0.5f)
            }
        }

        Motif.FIREFLIES -> {
            // Wander around the anchor with an independent blink.
            val x = (p.x + sin(t * 4f + p.phase) * 0.05f) * size.width
            val y = (p.y + sin(t * 3f + p.phase * 1.7f) * 0.04f) * size.height
            val alpha = 0.2f + 0.75f * abs(sin(t * 9f + p.phase))
            val r = unit * 0.016f * p.scale
            drawCircle(color.copy(alpha = alpha * 0.35f), r * 2.4f, Offset(x, y))
            drawCircle(color.copy(alpha = alpha), r, Offset(x, y))
        }

        Motif.BOKEH -> {
            val y = ((p.y + t * p.speed * 0.3f) % 1f) * size.height
            val r = unit * 0.10f * p.scale
            val alpha = 0.05f + 0.07f * abs(sin(t * 3f + p.phase))
            drawCircle(color.copy(alpha = alpha), r, Offset(p.x * size.width, y))
        }

        Motif.RAIN -> {
            val y = ((p.y + t * p.speed * 1.6f) % 1.1f) * size.height
            val x = (p.x + sway * 0.01f) * size.width
            val len = unit * 0.05f * p.scale
            drawLine(
                color.copy(alpha = 0.35f),
                Offset(x, y - len),
                Offset(x, y),
                unit * 0.006f
            )
        }

        Motif.SNOW, Motif.PETALS, Motif.LEAVES, Motif.CONFETTI, Motif.EMBERS, Motif.BUBBLES -> {
            val rising = theme.motif == Motif.EMBERS || theme.motif == Motif.BUBBLES
            val raw = if (rising) 1f - ((p.y + t * p.speed * 0.5f) % 1f) else (p.y + t * p.speed * 0.5f) % 1f
            val x = (p.x + sway * 0.03f) * size.width
            val y = raw * size.height
            val alpha = when (theme.motif) {
                Motif.EMBERS -> 0.3f + 0.6f * abs(sin(t * 14f + p.phase))
                Motif.BUBBLES -> 0.25f
                else -> 0.55f
            }
            val base = unit * 0.016f * p.scale
            val shapeColor = color.copy(alpha = alpha)
            when (theme.motif) {
                Motif.SNOW -> drawCircle(shapeColor, base * 0.7f, Offset(x, y))

                Motif.BUBBLES -> drawCircle(
                    shapeColor,
                    base * 1.6f,
                    Offset(x, y),
                    style = Stroke(unit * 0.005f)
                )

                Motif.PETALS -> rotate(sway * 40f + p.hueShift * 90f, Offset(x, y)) {
                    drawOval(shapeColor, Offset(x - base, y - base * 0.55f), Size(base * 2f, base * 1.1f))
                }

                Motif.LEAVES -> rotate(sway * 55f + p.hueShift * 90f, Offset(x, y)) {
                    drawOval(shapeColor, Offset(x - base * 1.2f, y - base * 0.5f), Size(base * 2.4f, base))
                }

                Motif.CONFETTI -> rotate(sway * 120f + p.hueShift * 180f, Offset(x, y)) {
                    drawRect(shapeColor, Offset(x - base, y - base * 0.5f), Size(base * 2f, base))
                }

                Motif.EMBERS -> drawCircle(shapeColor, base * 0.6f, Offset(x, y))

                else -> Unit
            }
        }

        Motif.FLOWERS -> {
            val y = ((p.y + t * p.speed * 0.25f) % 1f) * size.height
            val x = (p.x + sway * 0.015f) * size.width
            val center = Offset(x, y)
            val petal = unit * 0.014f * p.scale
            val alpha = 0.5f
            rotate(p.hueShift * 360f + t * 25f, center) {
                for (k in 0 until 5) {
                    val ang = k * 72f
                    val ox = petal * 1.5f * kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat()
                    val oy = petal * 1.5f * kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat()
                    drawCircle(color.copy(alpha = alpha), petal * 0.8f, center + Offset(ox, oy))
                }
                drawCircle(Color.White.copy(alpha = alpha), petal * 0.55f, center)
            }
        }

        Motif.SCALES -> {
            // Static lattice of shimmering half-round scales.
            val cols = 6
            val rows = 6
            val cellW = size.width / cols
            val cellH = size.height / rows
            for (row in 0 until rows) {
                for (col in 0..cols) {
                    val cx = col * cellW + (if (row % 2 == 0) 0f else cellW / 2f)
                    val cy = row * cellH + p.phase
                    val shimmer = 0.10f + 0.10f * abs(sin(t * 4f + (row * cols + col)))
                    drawArc(
                        color = color.copy(alpha = shimmer),
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(cx - cellW / 2f, cy),
                        size = Size(cellW, cellH),
                        style = Stroke(unit * 0.008f)
                    )
                }
            }
        }

        Motif.GEOMETRIC -> {
            val cols = 7
            val rows = 9
            val cellW = size.width / cols
            val cellH = size.height / rows
            val idx = (p.phase * 57).toInt()
            val cx = (idx % cols) * cellW + cellW / 2f
            val cy = ((idx / cols) % rows) * cellH + cellH / 2f + (t * p.speed * 8f) % cellH
            val alpha = 0.10f + 0.12f * abs(sin(t * 5f + p.phase))
            val s = minOf(cellW, cellH) * 0.28f * p.scale
            rotate(45f + p.hueShift * 90f, Offset(cx, cy)) {
                drawRect(
                    color.copy(alpha = alpha),
                    topLeft = Offset(cx - s / 2f, cy - s / 2f),
                    size = Size(s, s),
                    style = Stroke(unit * 0.005f)
                )
            }
        }
    }
}
