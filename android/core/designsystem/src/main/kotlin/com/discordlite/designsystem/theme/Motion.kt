package com.discordlite.designsystem.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

/**
 * Motion tokens. Everything a finger can touch animates with a spring, described the way Apple
 * describes them: a damping ratio (1 = no overshoot) and a response in seconds (lower = snappier).
 * Springs start from the current on-screen value and carry velocity, so every animation can be
 * grabbed and reversed mid-flight.
 */
object DlMotion {

    /** Converts Apple's "response" into Compose stiffness (mass = 1): k = (2π / response)². */
    fun stiffness(response: Float): Float = (2 * PI / response).pow(2).toFloat()

    fun <T> spring(damping: Float = 1f, response: Float = 0.4f, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness(response), visibilityThreshold = visibilityThreshold)

    /** Default for UI that moves on its own: critically damped, graceful, no bounce. */
    fun <T> smooth(visibilityThreshold: T? = null) = spring(1f, 0.4f, visibilityThreshold)

    /** Quicker settle for small elements: indicators, toggles, icons. */
    fun <T> snappy(visibilityThreshold: T? = null) = spring(1f, 0.3f, visibilityThreshold)

    /** Only after a gesture carried momentum (flick, throw, release). Drawer/sheet values Apple ships. */
    fun <T> momentum(visibilityThreshold: T? = null) = spring(0.8f, 0.3f, visibilityThreshold)

    /** Press-down feedback: near-instant. */
    fun <T> press(visibilityThreshold: T? = null) = spring(1f, 0.15f, visibilityThreshold)

    /** Small celebratory pop (like, join, digit entry). */
    fun <T> pop(visibilityThreshold: T? = null) = spring(0.55f, 0.35f, visibilityThreshold)

    /**
     * Where a flick will come to rest, using Apple's exponential-decay projection.
     * [velocity] is in px/s; the result is a px distance. Linear in velocity, so density-independent.
     */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /** Progressive resistance past a boundary: the further you pull, the less it follows. */
    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
        (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
}
