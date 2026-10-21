package com.discordlite.app.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discordlite.app.ui.common.entrance
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.theme.DlTheme

/**
 * Covers the whole app while it's locked. Asks for unlock straight away; if dismissed, the button
 * asks again. Nothing underneath is visible or tappable.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val colors = DlTheme.colors
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler {} // back can't bypass the lock
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .entrance(0, baseDelayMs = 0)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(colors.accentGradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("discord-lite is locked", style = DlTheme.type.title2, color = colors.textPrimary, modifier = Modifier.entrance(1, baseDelayMs = 0))
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock with your fingerprint, face or phone PIN.",
                style = DlTheme.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.entrance(2, baseDelayMs = 0),
            )
        }
        PrimaryButton(
            "Unlock",
            onClick = onUnlock,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp)
                .entrance(3, baseDelayMs = 0),
        )
    }
}
