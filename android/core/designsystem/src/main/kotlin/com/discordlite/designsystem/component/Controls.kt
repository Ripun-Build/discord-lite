package com.discordlite.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/**
 * iOS-style switch. The thumb stretches while the finger is down (a squish that says "I'm holding
 * it"), glides with a spring, and a haptic fires on the same frame the value flips.
 */
@Composable
fun DlSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val stretch = remember { Animatable(0f) }
    val current by rememberUpdatedState(checked)
    val onChange by rememberUpdatedState(onCheckedChange)

    val track by animateColorAsState(
        if (checked) colors.success else if (colors.isDark) Color(0xFF39393D) else Color(0xFFE3E3E8),
        DlMotion.snappy(),
        label = "track",
    )
    val thumbX by animateDpAsState(if (checked) 22.dp else 2.dp, DlMotion.snappy(), label = "thumb")

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
                    scope.launch { stretch.animateTo(1f, DlMotion.press()) }
                    val up = waitForUpOrCancellation()
                    scope.launch { stretch.animateTo(0f, DlMotion.snappy()) }
                    if (up != null) {
                        haptics.tick()
                        onChange(!current)
                    }
                }
            },
    ) {
        val extra = 7.dp * stretch.value
        // Stretch grows toward the side the thumb is heading.
        Box(
            Modifier
                .offset(x = if (checked) thumbX - extra else thumbX, y = 2.dp)
                .size(width = 27.dp + extra, height = 27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Segmented control with a sliding thumb. The thumb springs to the tapped segment from wherever
 * it currently is, so quick successive taps stay continuous.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * selected, DlMotion.snappy(), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .fillMaxHeight()
                .shadow(if (colors.isDark) 0.dp else 2.dp, RoundedCornerShape(8.dp))
                .background(if (colors.isDark) Color(0xFF3A3A44) else Color.White, RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, label ->
                val textColor by animateColorAsState(
                    if (index == selected) colors.textPrimary else colors.textSecondary,
                    DlMotion.snappy(),
                    label = "segmentText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressable(pressedScale = 0.95f, role = Role.Tab) {
                            if (index != selected) {
                                haptics.selection()
                                onSelect(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = DlTheme.type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = textColor, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
