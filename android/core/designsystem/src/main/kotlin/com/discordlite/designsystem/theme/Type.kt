package com.discordlite.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale modelled on Apple's text styles. Tracking is size-specific: large text gets
 * negative letter-spacing, small text slightly positive; leading tightens as size grows.
 * Uses the system font, which already carries the platform's legibility tuning.
 */
@Immutable
data class DlTypography(
    val largeTitle: TextStyle = style(34, 40, FontWeight.Bold, -0.022),
    val title1: TextStyle = style(28, 34, FontWeight.Bold, -0.02),
    val title2: TextStyle = style(22, 28, FontWeight.Bold, -0.015),
    val title3: TextStyle = style(20, 25, FontWeight.SemiBold, -0.01),
    val headline: TextStyle = style(17, 22, FontWeight.SemiBold, -0.006),
    val body: TextStyle = style(16, 22, FontWeight.Normal, -0.004),
    val callout: TextStyle = style(15, 20, FontWeight.Normal, -0.002),
    val subheadline: TextStyle = style(14, 19, FontWeight.Normal, 0.0),
    val footnote: TextStyle = style(13, 18, FontWeight.Normal, 0.002),
    val caption: TextStyle = style(12, 16, FontWeight.Normal, 0.008),
    val micro: TextStyle = style(11, 13, FontWeight.SemiBold, 0.04),
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight, trackingEm: Double) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = trackingEm.em,
)
