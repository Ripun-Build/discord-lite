package com.discordlite.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics

/**
 * Inline error that expands in under the form. Each new error (a new [tick]) gives it the iOS
 * "no" shake — a sideways kick handed to an under-damped spring — plus a reject haptic.
 */
@Composable
fun ErrorBanner(message: String?, tick: Int, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val reduced = DlTheme.reducedMotion
    val shake = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick > 0 && message != null) {
            haptics.reject()
            if (!reduced) shake.animateTo(0f, DlMotion.spring(0.25f, 0.32f), initialVelocity = 1400f)
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(DlMotion.snappy()) + fadeIn(DlMotion.snappy()),
        exit = shrinkVertically(DlMotion.snappy()) + fadeOut(DlMotion.snappy()),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shake.value }
                .background(colors.danger.copy(alpha = if (colors.isDark) 0.16f else 0.10f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = colors.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message.orEmpty(), style = DlTheme.type.subheadline, color = colors.textPrimary)
        }
    }
}
