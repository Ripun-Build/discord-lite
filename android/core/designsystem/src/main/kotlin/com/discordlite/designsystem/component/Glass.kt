package com.discordlite.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The haze source for the current screen; floating chrome blurs whatever scrolls underneath. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun glassStyle(thick: Boolean = false): HazeStyle {
    val colors = DlTheme.colors
    return HazeStyle(
        backgroundColor = colors.background,
        tint = HazeTint(colors.glassTint),
        // Bigger surfaces read as thicker material.
        blurRadius = if (thick) 34.dp else 24.dp,
        noiseFactor = 0.03f,
        fallbackTint = HazeTint(colors.glassTint.copy(alpha = 0.96f)),
    )
}

/**
 * Translucent floating material: blur of the content underneath + tint + a faint bright edge.
 * Falls back to a near-solid tint on devices without blur support (Android < 12).
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    thick: Boolean = false,
    edge: Boolean = true,
): Modifier {
    val state = LocalHazeState.current
    val colors = DlTheme.colors
    val style = glassStyle(thick)
    val clipped = this.clip(shape)
    val blurred = if (state != null) clipped.hazeEffect(state = state, style = style) else clipped
    return if (edge && shape != RectangleShape) blurred.border(0.75.dp, colors.glassEdge, shape) else blurred
}
