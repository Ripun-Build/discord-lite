package com.discordlite.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A bottom sheet built on the fluid-interface rules:
 *  - The sheet tracks the finger 1:1 and can be grabbed mid-animation (the running spring stops
 *    and the drag continues from the live position — no jump).
 *  - On release, the finger's velocity is projected forward (as scrolling would) to pick open vs
 *    closed, and handed to the spring so drag and animation have no seam.
 *  - Pulling up past fully-open rubber-bands instead of hard-stopping.
 *  - The scrim dims in proportion to how open the sheet is.
 */
@Composable
fun SpringSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var rendered by remember { mutableStateOf(visible) }
    if (visible) rendered = true
    if (!rendered) return

    BoxWithConstraints(Modifier.fillMaxSize().zIndex(10f)) {
        val density = LocalDensity.current
        val containerPx = with(density) { maxHeight.toPx() }
        var sheetPx by remember { mutableFloatStateOf(containerPx) }
        val offset = remember { Animatable(containerPx) } // 0 = fully open, sheetPx = fully closed
        var rawDrag by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(visible) {
            if (visible) {
                offset.animateTo(0f, DlMotion.smooth())
            } else {
                // Inherits whatever velocity a flick already gave it.
                offset.animateTo(containerPx, DlMotion.spring(1f, 0.35f))
                rendered = false
            }
        }

        val progress = (1f - offset.value / sheetPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

        BackHandler(enabled = visible) { dismiss() }

        // Scrim: tap anywhere outside to dismiss.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(colors.scrim, alpha = progress) }
                .pointerInput(visible) {
                    if (visible) detectTapGestures { dismiss() }
                },
        )

        val dragState = rememberDraggableState { delta ->
            rawDrag += delta
            val shown = if (rawDrag < 0f) -DlMotion.rubberband(-rawDrag, sheetPx) else rawDrag
            scope.launch { offset.snapTo(shown) }
        }

        Column(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .onSizeChanged { sheetPx = it.height.toFloat() }
                .graphicsLayer { translationY = offset.value }
                .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(if (colors.isDark) colors.surface else colors.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = {
                        offset.stop() // grab it mid-flight
                        rawDrag = offset.value
                    },
                    onDragStopped = { velocity ->
                        val projected = offset.value + DlMotion.project(velocity)
                        val close = projected > sheetPx / 2f
                        if (close) {
                            launch { offset.animateTo(containerPx, DlMotion.spring(1f, 0.35f), initialVelocity = velocity) }
                            dismiss()
                        } else {
                            haptics.tick()
                            val spec = if (abs(velocity) > 400f) DlMotion.momentum<Float>() else DlMotion.smooth()
                            offset.animateTo(0f, spec, initialVelocity = velocity)
                        }
                    },
                )
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 10.dp)
                    .size(width = 38.dp, height = 5.dp)
                    .background(colors.textTertiary.copy(alpha = 0.45f), CircleShape),
            )
            content()
        }
    }
}

/** Spacer that keeps sheet content above the gesture area. */
@Composable
fun SheetSpacer() = Box(Modifier.height(8.dp))
