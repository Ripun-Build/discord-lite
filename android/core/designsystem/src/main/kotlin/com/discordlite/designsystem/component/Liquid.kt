package com.discordlite.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The two edges of a liquid shape, each driven by its own spring.
 *
 * When the shape travels, its *leading* edge rides a quick spring and its *trailing* edge a slower
 * one, so the shape stretches toward its destination and then the tail catches up — the way a drop
 * of water moves. Both springs are under-damped just enough to leave a small wobble on arrival.
 * Every move starts from the edges' live values and velocities, so it can be redirected mid-flight.
 */
@Stable
class LiquidEdges(start: Float, end: Float) {
    val start = Animatable(start)
    val end = Animatable(end)

    val center: Float get() = (start.value + end.value) / 2f
    val width: Float get() = end.value - start.value

    suspend fun flowTo(
        targetStart: Float,
        targetEnd: Float,
        reduced: Boolean,
        lead: SpringSpec<Float> = DlMotion.spring(0.7f, 0.24f),
        trail: SpringSpec<Float> = DlMotion.spring(0.78f, 0.44f),
        velocity: Float? = null,
    ) = coroutineScope {
        val forward = (targetStart + targetEnd) / 2f >= center
        val calm = DlMotion.smooth<Float>()
        launch {
            start.animateTo(
                targetStart,
                if (reduced) calm else if (forward) trail else lead,
                initialVelocity = velocity ?: start.velocity,
            )
        }
        launch {
            end.animateTo(
                targetEnd,
                if (reduced) calm else if (forward) lead else trail,
                initialVelocity = velocity ?: end.velocity,
            )
        }
    }

    /** How much the shape is currently stretched beyond [restWidth] (0 = at rest). */
    fun stretch(restWidth: Float): Float = (width / restWidth - 1f).coerceAtLeast(0f)
}

/** Geometry of the liquid pill at this frame, handed to the caller to paint. */
data class LiquidPillFrame(val rect: Rect, val corner: Float, val stretch: Float, val lift: Float)

/**
 * A row of options with one liquid selection pill — the heart of the tab bar and segmented control.
 *
 * - Tap an option: the pill flows there (stretch, thin out, catch up, settle with a wobble).
 * - Press and slide anywhere on the row: the pill lifts like a lens, follows the finger with a
 *   gooey lag, rubber-bands at the ends, and ticks a haptic as it crosses each option. On release
 *   the finger's velocity is projected forward to pick the option, and handed to the springs.
 * - Each option receives `focus` (0–1): how much of the pill currently sits over it, so content
 *   under the pill can magnify and tint continuously instead of switching abruptly.
 */
@Composable
fun LiquidSelector(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pillPadding: Dp = 0.dp,
    itemRole: Role = Role.Tab,
    pressedScale: Float = 0.92f,
    drawPill: DrawScope.(LiquidPillFrame) -> Unit,
    item: @Composable BoxScope.(index: Int, focus: Float, lifted: Float) -> Unit,
) {
    val reduced = DlTheme.reducedMotion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val slot = widthPx / count
        val edges = remember { LiquidEdges(selected * slot, (selected + 1) * slot) }
        val lift = remember { Animatable(0f) } // 0 = resting, 1 = lifted lens while dragging
        var dragging by remember { mutableStateOf(false) }
        var releaseVelocity by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(selected, slot) {
            if (!dragging) {
                val v = releaseVelocity.takeIf { it != 0f }
                releaseVelocity = 0f
                edges.flowTo(selected * slot, (selected + 1) * slot, reduced, velocity = v)
            }
        }

        val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { pillPadding.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val s = edges.stretch(slot)
                    val l = lift.value
                    // Volume is (roughly) preserved: as the drop stretches it gets thinner.
                    val squash = (1f / (1f + s * 0.55f)).coerceIn(0.72f, 1f)
                    val grow = 1f + 0.08f * l
                    val cx = edges.center
                    val w = (edges.width - padPx * 2f) * grow
                    val h = (size.height - padPx * 2f) * squash * (1f + 0.06f * l)
                    val rect = Rect(Offset(cx - w / 2f, size.height / 2f - h / 2f), Size(w, h))
                    drawPill(LiquidPillFrame(rect, h / 2f, s, l))
                }
                .pointerInput(count, slot) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        dragging = true
                        val tracker = VelocityTracker()
                        var hovered = currentSelected
                        scope.launch { lift.animateTo(1f, DlMotion.snappy()) }

                        fun follow(x: Float) {
                            val minC = slot / 2f
                            val maxC = widthPx - slot / 2f
                            val c = when {
                                x < minC -> minC - DlMotion.rubberband(minC - x, slot)
                                x > maxC -> maxC + DlMotion.rubberband(x - maxC, slot)
                                else -> x
                            }
                            // Gooey follow: tight springs, still leading/trailing so fast drags stretch.
                            scope.launch {
                                edges.flowTo(
                                    c - slot / 2f, c + slot / 2f, reduced,
                                    lead = DlMotion.spring(0.85f, 0.1f),
                                    trail = DlMotion.spring(0.85f, 0.2f),
                                )
                            }
                            val index = (c / slot).toInt().coerceIn(0, count - 1)
                            if (index != hovered) {
                                hovered = index
                                haptics.selection()
                            }
                        }

                        tracker.addPosition(first.uptimeMillis, first.position)
                        follow(first.position.x)
                        horizontalDrag(first.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            follow(change.position.x)
                            change.consume()
                        }

                        val velocity = tracker.calculateVelocity().x
                        val projected = edges.center + DlMotion.project(velocity, decelerationRate = 0.99f)
                        val target = (projected / slot).toInt().coerceIn(0, count - 1)
                        dragging = false
                        scope.launch { lift.animateTo(0f, DlMotion.spring(0.6f, 0.4f)) }
                        if (target != currentSelected) {
                            releaseVelocity = velocity
                            select(target)
                        } else {
                            scope.launch { edges.flowTo(target * slot, (target + 1) * slot, reduced, velocity = velocity) }
                        }
                    }
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                repeat(count) { index ->
                    val slotStart = index * slot
                    val overlap = (min(edges.end.value, slotStart + slot) - max(edges.start.value, slotStart)).coerceAtLeast(0f)
                    val focus = (overlap / slot).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(pressedScale = pressedScale, role = itemRole) {
                                if (index != currentSelected) haptics.selection()
                                select(index)
                            },
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { item(index, focus, lift.value) }
                }
            }
        }
    }
}

/**
 * "Worm" page indicator: the active dot stretches out toward the next page and then pulls its tail
 * in, tracking the pager 1:1 while the user swipes. [position] is page + offset fraction.
 */
@Composable
fun LiquidPageIndicator(
    count: Int,
    position: () -> Float,
    modifier: Modifier = Modifier,
    dot: Dp = 6.dp,
    spacing: Dp = 6.dp,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.45f),
) {
    Canvas(modifier.size(width = dot * count + spacing * (count - 1), height = dot)) {
        val d = dot.toPx()
        val step = d + spacing.toPx()
        repeat(count) { i ->
            drawCircle(inactiveColor, radius = d / 2f, center = Offset(i * step + d / 2f, size.height / 2f))
        }
        val p = position().coerceIn(0f, (count - 1).toFloat())
        val i = floor(p)
        val f = p - i
        val lead = i + min(1f, f * 2f)
        val trail = i + max(0f, f * 2f - 1f)
        val left = trail * step
        val right = lead * step + d
        val h = d * (1f - 0.22f * sin(PI.toFloat() * f))
        drawRoundRect(
            activeColor,
            topLeft = Offset(left, size.height / 2f - h / 2f),
            size = Size(right - left, h),
            cornerRadius = CornerRadius(h / 2f),
        )
    }
}


