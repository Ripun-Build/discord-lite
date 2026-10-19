package com.discordlite.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Apple-style press feedback. The element reacts on pointer-*down* (not on release), shrinks with
 * a near-instant spring, and commits on pointer-up. Dragging away or starting a scroll cancels it.
 * The release spring starts from the live scale, so rapid taps never jump.
 *
 * @param pressedScale how far it sinks under the finger.
 * @param highlight optional colour washed over the content while pressed (list rows).
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    highlight: Color = Color.Unspecified,
    haptic: Boolean = false,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val wash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val currentOnClick by rememberUpdatedState(onClick)

    this
        .semantics {
            this.role = role
            if (enabled) onClick { currentOnClick(); true }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .then(
            if (highlight.isSpecified) {
                Modifier.drawWithContent {
                    drawContent()
                    if (wash.value > 0f) drawRect(highlight.copy(alpha = highlight.alpha * wash.value))
                }
            } else {
                Modifier
            },
        )
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { scale.animateTo(pressedScale, DlMotion.press()) }
                scope.launch { wash.animateTo(1f, DlMotion.press()) }
                val up = waitForUpOrCancellation()
                scope.launch { scale.animateTo(1f, DlMotion.spring(damping = 0.75f, response = 0.35f)) }
                scope.launch { wash.animateTo(0f, DlMotion.smooth()) }
                if (up != null) {
                    up.consume()
                    if (haptic) haptics.tick()
                    currentOnClick()
                }
            }
        }
}

private val Color.isSpecified get() = this != Color.Unspecified
