package com.discordlite.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.LocalReducedMotion
import com.discordlite.designsystem.theme.gradientFor
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * One-time entrance: rises [distance] and fades in, staggered by [index]. Plays once per
 * composition; with reduced motion it is a plain fade.
 */
fun Modifier.entrance(index: Int, distance: Dp = 22.dp, baseDelayMs: Long = 60L): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { distance.toPx() }
    LaunchedEffect(Unit) {
        delay(baseDelayMs + index * 70L)
        progress.animateTo(1f, if (reduced) DlMotion.smooth() else DlMotion.spring(1f, 0.6f))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = if (reduced) 0f else (1f - progress.value) * px
    }
}

/**
 * Placeholder "game screenshot": layered gradients and light blooms seeded by [seed], so each post
 * looks distinct until real media upload lands in Phase 7.
 */
@Composable
fun ArtPlaceholder(seed: String, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    val palette = gradientFor(seed)
    val second = gradientFor(seed.reversed() + "x")
    val rnd = remember(seed) { Random(seed.hashCode()) }
    val blooms = remember(seed) { List(3) { Offset(rnd.nextFloat(), rnd.nextFloat()) to (0.35f + rnd.nextFloat() * 0.4f) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            drawRect(Brush.linearGradient(listOf(palette[0], palette[1], second[1]), start = Offset.Zero, end = Offset(size.width, size.height)))
            blooms.forEachIndexed { i, (pos, r) ->
                val c = Offset(pos.x * size.width, pos.y * size.height)
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (i == 0) 0.38f else 0.2f), Color.Transparent),
                        center = c,
                        radius = size.minDimension * r,
                    ),
                    radius = size.minDimension * r,
                    center = c,
                )
            }
            // Soft vignette for depth.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f))))
        }
        if (showIcon) {
            Icon(Icons.Rounded.SportsEsports, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
fun FullWidthSpacer(height: Dp) = Box(Modifier.fillMaxWidth().size(height))
