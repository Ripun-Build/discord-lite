package com.discordlite.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics

/**
 * iOS-style switch with a liquid thumb. While the finger is down the thumb swells toward the side
 * it's about to travel; on release it flows across — leading edge first, tail following — and lands
 * with a small wobble. The haptic fires on the same frame the value flips.
 */
@Composable
fun DlSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val reduced = DlTheme.reducedMotion
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(checked)
    val onChange by rememberUpdatedState(onCheckedChange)
    var pressed by remember { mutableStateOf(false) }

    // Thumb edges in dp, inside a 51×31 track with 2dp inset; thumb is 27dp at rest.
    val edges = remember { LiquidEdges(if (checked) 22f else 2f, if (checked) 49f else 29f) }
    LaunchedEffect(checked, pressed) {
        val swell = if (pressed) 7f else 0f
        val start = if (checked) 22f - swell else 2f
        val end = if (checked) 49f else 29f + swell
        edges.flowTo(start, end, reduced, lead = DlMotion.spring(0.62f, 0.22f), trail = DlMotion.spring(0.72f, 0.38f))
    }

    val track by animateColorAsState(
        if (checked) colors.success else if (colors.isDark) Color(0xFF39393D) else Color(0xFFE3E3E8),
        DlMotion.snappy(),
        label = "track",
    )

    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "On" else "Off"
                onClick { onChange(!current); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) {
                        haptics.tick()
                        onChange(!current)
                    }
                }
            },
    ) {
        val width = edges.width
        // Thinner while stretched, like a drop in motion.
        val squash = (27f / width).coerceIn(0.86f, 1f)
        Box(
            Modifier
                .offset(x = edges.start.value.dp, y = 2.dp)
                .size(width = width.dp, height = 27.dp)
                .graphicsLayer { scaleY = squash }
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Segmented control with a liquid thumb: it flows between segments (stretch → catch up → wobble)
 * and can be dragged along the track. Labels brighten continuously as the thumb passes over them.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DlTheme.colors
    val thumb = if (colors.isDark) Color(0xFF3A3A44) else Color.White
    val rim = if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
            .padding(2.dp),
    ) {
        LiquidSelector(
            count = options.size,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            pressedScale = 0.95f,
            drawPill = { frame ->
                val corner = CornerRadius(8.dp.toPx() * (1f + frame.stretch))
                drawRoundRect(thumb, frame.rect.topLeft, frame.rect.size, corner)
                drawRoundRect(rim, frame.rect.topLeft, frame.rect.size, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            },
        ) { index, focus, lifted ->
            val scale = 1f + 0.04f * focus + 0.06f * focus * lifted
            Text(
                options[index],
                style = DlTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = lerp(colors.textSecondary, colors.textPrimary, focus),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
    }
}
