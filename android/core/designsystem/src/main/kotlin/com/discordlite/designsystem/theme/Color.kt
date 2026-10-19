package com.discordlite.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class DlColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Brand gradient: blurple → violet → pink. Used on primary actions and the user's own bubbles. */
    val accentGradient: List<Color>,
    /** Instagram-style ring for unseen stories. */
    val storyGradient: List<Color>,
    val bubbleOther: Color,
    val success: Color,
    val danger: Color,
    val online: Color,
    /** Tint laid over the blur for glass bars. Heavier in light mode so text stays legible. */
    val glassTint: Color,
    /** Bright top edge of glass — "light catching the material". */
    val glassEdge: Color,
    val scrim: Color,
)

private val BrandGradient = listOf(Color(0xFF5865F2), Color(0xFF9B5CFF), Color(0xFFFF4FA3))
private val StoryGradient = listOf(Color(0xFFFFC145), Color(0xFFFF4F7B), Color(0xFFB84DFF))

val DarkColors = DlColors(
    isDark = true,
    background = Color(0xFF07070A),
    surface = Color(0xFF111117),
    surfaceRaised = Color(0xFF1C1C24),
    separator = Color(0x1AFFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF6B6B76),
    accent = Color(0xFF8B7CFF),
    onAccent = Color.White,
    accentGradient = BrandGradient,
    storyGradient = StoryGradient,
    bubbleOther = Color(0xFF1F1F28),
    success = Color(0xFF30D158),
    danger = Color(0xFFFF453A),
    online = Color(0xFF30D158),
    glassTint = Color(0xB8101016),
    glassEdge = Color(0x1FFFFFFF),
    scrim = Color(0x99000000),
)

val LightColors = DlColors(
    isDark = false,
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    separator = Color(0x14000000),
    textPrimary = Color(0xFF111114),
    textSecondary = Color(0xFF6E6E73),
    textTertiary = Color(0xFFA1A1A6),
    accent = Color(0xFF5B4CF5),
    onAccent = Color.White,
    accentGradient = BrandGradient,
    storyGradient = StoryGradient,
    bubbleOther = Color(0xFFE9E9EF),
    success = Color(0xFF28B14C),
    danger = Color(0xFFE5342B),
    online = Color(0xFF28B14C),
    glassTint = Color(0xD1F7F7F9),
    glassEdge = Color(0x99FFFFFF),
    scrim = Color(0x66000000),
)

/** Stable, pleasant two-stop gradients for avatars and placeholder art, picked by name. */
private val AvatarPalettes = listOf(
    listOf(Color(0xFF5865F2), Color(0xFF9B5CFF)),
    listOf(Color(0xFFFF7A59), Color(0xFFFF3D77)),
    listOf(Color(0xFF00C6FF), Color(0xFF0072FF)),
    listOf(Color(0xFF34E89E), Color(0xFF0F9B8E)),
    listOf(Color(0xFFFFC145), Color(0xFFFF6A3D)),
    listOf(Color(0xFFB84DFF), Color(0xFFFF4FA3)),
    listOf(Color(0xFF43E97B), Color(0xFF38A1F9)),
    listOf(Color(0xFFFA709A), Color(0xFFFEC140)),
)

fun gradientFor(key: String): List<Color> = AvatarPalettes[(key.hashCode() and Int.MAX_VALUE) % AvatarPalettes.size]
