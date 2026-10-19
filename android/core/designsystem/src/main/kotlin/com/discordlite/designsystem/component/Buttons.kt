package com.discordlite.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme

private val ButtonShape = RoundedCornerShape(16.dp)

/** Gradient call-to-action. Label cross-fades into a spinner while [loading]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = DlTheme.colors
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, DlMotion.smooth(), label = "enabled")
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .pressable(enabled = enabled && !loading, pressedScale = 0.97f, onClick = onClick)
            .alpha(alpha)
            .clip(ButtonShape)
            .background(Brush.horizontalGradient(colors.accentGradient)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = {
                (fadeIn(DlMotion.snappy()) + scaleIn(DlMotion.snappy(), initialScale = 0.8f)) togetherWith
                    (fadeOut(DlMotion.snappy()) + scaleOut(DlMotion.snappy(), targetScale = 0.8f))
            },
            label = "loading",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            } else {
                Text(text, style = DlTheme.type.headline, color = colors.onAccent)
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 54.dp,
) {
    val colors = DlTheme.colors
    Box(
        modifier
            .height(height)
            .pressable(enabled = enabled, pressedScale = 0.97f, onClick = onClick)
            .clip(ButtonShape)
            .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DlTheme.type.headline, color = colors.textPrimary)
    }
}

/** Plain text action, tinted with the accent. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = DlTheme.colors.accent) {
    Box(modifier.pressable(pressedScale = 0.95f, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = DlTheme.type.headline, color = color)
    }
}

/** Round icon button used in bars. [filled] gives it a soft circular backing. */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    filled: Boolean = true,
    tint: Color = DlTheme.colors.textPrimary,
    gradient: Boolean = false,
) {
    val colors = DlTheme.colors
    Box(
        modifier
            .size(size)
            .pressable(pressedScale = 0.88f, onClick = onClick)
            .clip(CircleShape)
            .then(
                when {
                    gradient -> Modifier.background(Brush.linearGradient(colors.accentGradient))
                    filled -> Modifier.background(if (colors.isDark) colors.surfaceRaised.copy(alpha = 0.9f) else colors.bubbleOther)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (gradient) Color.White else tint, modifier = Modifier.size(size * 0.5f))
    }
}
