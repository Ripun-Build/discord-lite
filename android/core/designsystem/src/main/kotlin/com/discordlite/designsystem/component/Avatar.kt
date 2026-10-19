package com.discordlite.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.gradientFor
import kotlinx.coroutines.delay

/** Gradient avatar with initials and an optional presence dot. */
@Composable
fun Avatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    online: Boolean = false,
    shape: Shape = CircleShape,
) {
    val colors = DlTheme.colors
    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(gradientFor(name))),
            contentAlignment = Alignment.Center,
        ) {
            // Circles (people) get two letters; squircles (servers) get up to three, like Discord.
            val letters = initials(name, if (shape == CircleShape) 2 else 3)
            Text(
                text = letters,
                color = Color.White,
                fontSize = (size.value * if (letters.length > 2) 0.3f else 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
        }
        if (online) {
            val dot = (size * 0.28f).coerceAtLeast(10.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .background(colors.background, CircleShape)
                    .padding(dot * 0.18f)
                    .background(colors.online, CircleShape),
            )
        }
    }
}

fun initials(name: String, max: Int = 2): String =
    name.split(' ', '_', '.').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(max).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * Story avatar: an Instagram-style gradient ring for unseen stories (grey once seen).
 * The ring draws itself in on first appearance — staggered by [index] — instead of looping.
 */
@Composable
fun StoryAvatar(
    name: String,
    label: String,
    seen: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    isYou: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = DlTheme.colors
    val reduced = DlTheme.reducedMotion
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(80L + index * 45L)
            sweep.animateTo(1f, DlMotion.spring(1f, 0.7f))
        }
    }
    Column(
        modifier
            .width(76.dp)
            .pressable(pressedScale = 0.92f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            val ring = if (seen) listOf(colors.separator, colors.separator) else colors.storyGradient + colors.storyGradient.first()
            Canvas(Modifier.size(70.dp)) {
                val stroke = 2.6.dp.toPx()
                val inset = stroke / 2
                if (!isYou || !seen) {
                    drawArc(
                        brush = Brush.sweepGradient(ring, center = center),
                        startAngle = -90f,
                        sweepAngle = 360f * sweep.value,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Avatar(name = name, size = 60.dp)
            if (isYou) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .border(2.5.dp, colors.background, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(colors.accentGradient)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = DlTheme.type.caption,
            color = if (seen) colors.textSecondary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
