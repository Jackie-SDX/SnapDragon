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
 *
 * The crossfade is keyed on the theme ID and resolves the STATIC theme
 * inside, so callers can pass a color-animated theme without the
 * animation restarting the crossfade on every interpolated frame. The
 * background and the UI palette therefore transform together over the
 * same window instead of snapping apart.
 */
@Composable
fun ThemedBackground(theme: GameTheme, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = theme.id, animationSpec = tween(500), label = "theme_crossfade") { id ->
            val current = ThemeCatalog.byId(id)
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
        if (theme.motif == Motif.FOUNTAIN) drawFountainBasin(theme, dim)
        for (p in particles) {
            drawParticle(theme, p, progress, dim)
        }
        if (theme.motif == Motif.DRAGON) drawDragon(theme, progress, dim)
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
        Motif.DRAGON -> 22
        Motif.FOUNTAIN -> 45
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

        Motif.SNOW, Motif.PETALS, Motif.LEAVES, Motif.CONFETTI, Motif.EMBERS, Motif.BUBBLES, Motif.DRAGON -> {
            val rising =
                theme.motif == Motif.EMBERS || theme.motif == Motif.BUBBLES || theme.motif == Motif.DRAGON
            val raw = if (rising) 1f - ((p.y + t * p.speed * 0.5f) % 1f) else (p.y + t * p.speed * 0.5f) % 1f
            val x = (p.x + sway * 0.03f) * size.width
            val y = raw * size.height
            val alpha = when (theme.motif) {
                Motif.EMBERS, Motif.DRAGON -> 0.3f + 0.6f * abs(sin(t * 14f + p.phase))
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

                Motif.EMBERS, Motif.DRAGON -> drawCircle(shapeColor, base * 0.6f, Offset(x, y))

                else -> Unit
            }
        }

        Motif.FOUNTAIN -> {
            // Ballistic droplets: three jets launch on staggered phases and
            // fall back through the basin, fading in and out along the arc.
            val s = (t * 1.5f + p.hueShift) % 1f
            val jet = ((p.phase / 6.283f) * 3f).toInt().coerceIn(0, 2)
            val vx = (jet - 1) * 0.16f + (p.x - 0.5f) * 0.05f
            // The centre jet climbs higher than the two side jets.
            val vy = if (jet == 1) -1.35f - p.speed * 0.25f else -1.02f - p.speed * 0.2f
            val g = 2.5f
            val x = size.width / 2f + vx * s * size.width
            val y = size.height * 0.765f + (vy * s + 0.5f * g * s * s) * size.height
            val alpha = 0.85f * sin(Math.PI.toFloat() * s)
            val dirX = vx * size.width
            val dirY = (vy + g * s) * size.height
            val mag = kotlin.math.sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(1f)
            val tail = unit * 0.03f
            val c = color.copy(alpha = alpha * 0.65f)
            drawLine(
                c,
                Offset(x, y),
                Offset(x - dirX / mag * tail, y - dirY / mag * tail),
                unit * 0.0045f
            )
            drawCircle(color.copy(alpha = alpha), unit * 0.0065f * p.scale, Offset(x, y))
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

/** Static fountain furniture: nozzle, basin, pedestal, and pool. */
private fun DrawScope.drawFountainBasin(theme: GameTheme, dim: Float) {
    val color = theme.motifColor
    val cx = size.width / 2f
    val baseY = size.height * 0.78f
    drawArc(
        color = color.copy(alpha = 0.28f),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - dim * 0.30f, baseY - dim * 0.10f),
        size = Size(dim * 0.60f, dim * 0.20f),
        style = Stroke(dim * 0.010f),
    )
    drawLine(
        color.copy(alpha = 0.20f),
        Offset(cx, baseY + dim * 0.09f),
        Offset(cx, baseY + dim * 0.22f),
        dim * 0.014f,
    )
    drawLine(
        color.copy(alpha = 0.16f),
        Offset(cx - dim * 0.42f, baseY + dim * 0.22f),
        Offset(cx + dim * 0.42f, baseY + dim * 0.22f),
        dim * 0.008f,
    )
    drawCircle(color.copy(alpha = 0.35f), dim * 0.016f, Offset(cx, baseY - dim * 0.02f))
}

/** One full sine-glide sweep across the screen; u=0 and u=1 match, so the loop never jumps. */
private fun dragonAt(u: Float, width: Float, height: Float): Offset {
    val x = (-0.18f + 1.36f * u) * width
    val y = height * (0.40f + 0.16f * sin(u * 6.283185f + 0.4f))
    return Offset(x, y)
}

/**
 * The hero of the DRAGON motif: a serpentine body of tapering
 * segments, fan wings beating at the shoulder, horns, an eye, and a
 * flickering breath — gliding along [dragonAt] so it crosses and
 * re-enters on every 12s cycle.
 */
private fun DrawScope.drawDragon(theme: GameTheme, t: Float, dim: Float) {
    val color = theme.motifColor
    val head = dragonAt(t, size.width, size.height)
    val ahead = dragonAt(t + 0.004f, size.width, size.height)
    val dir = ahead - head
    val angle = kotlin.math.atan2(dir.y, dir.x)
    val forward = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))

    // Trailing body: thicker at the neck, tapering to the tail tip.
    val segments = 14
    for (i in segments downTo 1) {
        val ui = t - i * 0.010f
        if (ui < 0f) continue
        val pos = dragonAt(ui, size.width, size.height)
        val f = 1f - i.toFloat() / segments
        val r = dim * (0.004f + 0.016f * f * f)
        drawCircle(color.copy(alpha = 0.30f + 0.50f * f), r, pos)
    }

    // Side-view wing: one membrane above the shoulder with finger
    // bones, plus a dimmer far wing behind the body. The flap drives
    // the tip so the wing visibly beats on the same 12s clock.
    val shoulder = dragonAt(t - 0.030f, size.width, size.height)
    val up = Offset(forward.y, -forward.x)
    val flap = sin(t * 94.2f)
    val span = dim * 0.13f
    val tip = shoulder + up * (span * (0.95f + 0.30f * flap)) - forward * (span * (0.55f - 0.20f * flap))
    val back = shoulder + up * (span * 0.22f) - forward * (span * 0.95f)
    val membrane = androidx.compose.ui.graphics.Path().apply {
        moveTo(shoulder.x, shoulder.y)
        quadraticTo(
            shoulder.x + up.x * span * 1.15f - forward.x * span * 0.15f,
            shoulder.y + up.y * span * 1.15f - forward.y * span * 0.15f,
            tip.x,
            tip.y,
        )
        quadraticTo(
            shoulder.x + up.x * span * 0.50f - forward.x * span * 0.55f,
            shoulder.y + up.y * span * 0.50f - forward.y * span * 0.55f,
            back.x,
            back.y,
        )
        close()
    }
    drawPath(membrane, color.copy(alpha = 0.28f), style = Stroke(dim * 0.005f))
    drawLine(color.copy(alpha = 0.55f), shoulder, tip, dim * 0.005f)
    drawLine(color.copy(alpha = 0.45f), shoulder, back, dim * 0.004f)
    // Far wing: a dim echo sweeping back and under.
    val farTip = shoulder - up * (span * (0.35f + 0.15f * flap)) - forward * (span * 0.85f)
    drawLine(color.copy(alpha = 0.30f), shoulder, farTip, dim * 0.004f)

    // Head, snout, swept-back horns, eye.
    val headR = dim * 0.024f
    drawCircle(color.copy(alpha = 0.90f), headR, head)
    drawCircle(color.copy(alpha = 0.85f), headR * 0.55f, head + forward * (headR * 1.6f))
    val hornBase = head - forward * (headR * 0.4f) + up * (headR * 0.5f)
    drawLine(
        color.copy(alpha = 0.85f),
        hornBase,
        hornBase - forward * (headR * 1.4f) + up * (headR * 1.1f),
        dim * 0.004f,
    )
    drawLine(
        color.copy(alpha = 0.70f),
        hornBase - up * (headR * 0.6f),
        hornBase - forward * (headR * 1.0f) + up * (headR * 0.3f),
        dim * 0.0035f,
    )
    drawCircle(
        Color.White.copy(alpha = 0.90f),
        headR * 0.22f,
        head + up * (headR * 0.30f) + forward * (headR * 0.25f),
    )

    // Breath: flickering puffs carried ahead of the snout.
    for (k in 1..5) {
        val d = headR * (2.0f + k * 1.15f)
        val rr = dim * 0.015f * (1f - k * 0.15f)
        val a = 0.65f * (1f - k * 0.15f) * (0.55f + 0.45f * abs(sin(t * 25f + k)))
        val wobble = Offset(0f, sin(t * 20f + k) * dim * 0.008f)
        drawCircle(color.copy(alpha = a), rr, head + forward * d + wobble)
    }
}
