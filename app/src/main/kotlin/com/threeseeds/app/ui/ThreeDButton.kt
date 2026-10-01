package com.threeseeds.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.threeseeds.app.theme.LocalGameTheme
import kotlin.math.roundToInt

/** Visual treatment of [ThreeDButton]. */
enum class Button3DVariant {

    /** Themed gradient fill with a solid depth lip — the primary action. */
    PRIMARY,

    /** Outlined pill with a subtle depth lip — secondary actions. */
    OUTLINE,

    /** Flat text control that still dips on press — toolbar/link actions. */
    TEXT
}

/**
 * The app's shared button: a physical, 3D-feeling control. A depth lip
 * sits below the face at rest; pressing makes the face sink and the
 * lip collapse (with a springy bounce on release) while the face eases
 * down a notch, so every tap lands with weight. Material3 semantics
 * (role, enabled, no ripple) are preserved; the depth IS the feedback.
 */
@Composable
fun ThreeDButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: Button3DVariant = Button3DVariant.OUTLINE,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    shape: RoundedCornerShape = RoundedCornerShape(50),
    containerColor: Color = Color.Transparent,
    borderColor: Color? = null,
    contentColor: Color? = null,
    brush: Brush? = null,
    label: @Composable () -> Unit
) {
    val theme = LocalGameTheme.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Rest: lip under the face; pressed: face sinks, lip collapses.
    val restDepth = if (variant == Button3DVariant.TEXT) 0.dp else 4.dp
    val dropDepth = if (variant == Button3DVariant.TEXT) 2.dp else 3.dp
    val pressSpring = spring<Dp>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    val scaleSpring = spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
    val dropTarget = if (pressed) dropDepth else 0.dp
    val liftTarget = if (pressed) 0.dp else restDepth
    val scaleTarget = if (pressed) 0.975f else 1f
    val dropAnim by animateDpAsState(dropTarget, pressSpring, label = "drop")
    val liftAnim by animateDpAsState(liftTarget, pressSpring, label = "lift")
    val scaleAnim by animateFloatAsState(scaleTarget, scaleSpring, label = "scale")
    // Reduce-motion: the pressed state still shows (it is feedback),
    // it simply arrives without a spring.
    val drop = if (reduceMotion) dropTarget else dropAnim
    val lift = if (reduceMotion) liftTarget else liftAnim
    val scale = if (reduceMotion) scaleTarget else scaleAnim

    val faceBrush = brush ?: when (variant) {
        Button3DVariant.PRIMARY -> Brush.linearGradient(listOf(theme.accentColor, theme.playerOne))
        else -> null
    }
    val border = if (variant == Button3DVariant.OUTLINE) {
        borderColor ?: MaterialTheme.colorScheme.outlineVariant
    } else {
        null
    }
    val faceContent = contentColor ?: when (variant) {
        Button3DVariant.PRIMARY -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val lipColor = when (variant) {
        Button3DVariant.PRIMARY -> theme.playerOne.darkened(0.45f)
        Button3DVariant.OUTLINE -> (border ?: MaterialTheme.colorScheme.outlineVariant).darkened(0.55f)
        Button3DVariant.TEXT -> Color.Transparent
    }
    val labelPadding = if (variant == Button3DVariant.TEXT) 12.dp else 20.dp
    val liftAtRest = lift

    // Box() measures children loosely, which would let the face wrap to
    // its label while the lip fills the caller's width. A Layout keeps
    // the caller's constraints on the face (fixed width stays fixed,
    // wrap stays wrap) and sizes the lip to match the face exactly.
    Layout(
        content = {
            // Depth lip: peeks out below the face at rest, hidden once pressed.
            Box(modifier = Modifier.background(lipColor, shape))
            // The face: content + min height define the button box.
            Box(
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.45f)
                    .graphicsLayer {
                        translationY = drop.toPx()
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(
                        if (faceBrush != null) Modifier.background(faceBrush, shape)
                        else Modifier.background(containerColor, shape)
                    )
                    .then(
                        if (border != null) Modifier.border(1.5.dp, border, shape) else Modifier
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.defaultMinSize(minHeight = minHeight).padding(horizontal = labelPadding)) { label() }
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val face = measurables[1].measure(constraints)
        val lip = measurables[0].measure(Constraints.fixed(face.width, face.height))
        val liftPx = liftAtRest.toPx().roundToInt()
        layout(face.width, face.height) {
            lip.place(0, liftPx)
            face.place(0, 0)
        }
    }
}

private fun Color.darkened(factor: Float): Color =
    Color(red = red * factor, green = green * factor, blue = blue * factor, alpha = alpha)
