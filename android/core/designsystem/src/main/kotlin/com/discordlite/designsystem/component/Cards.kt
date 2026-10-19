package com.discordlite.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.gradientFor
import com.discordlite.designsystem.theme.rememberHaptics

data class InvitePreview(
    val code: String,
    val serverName: String,
    val online: Int,
    val members: Int,
)

/**
 * Discord-style invite card: banner, server icon, live counts and a Join button that morphs into
 * "Joined" — the width change springs, and a success haptic lands on the same frame.
 */
@Composable
fun InviteCard(
    invite: InvitePreview,
    joined: Boolean,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "YOU'VE BEEN INVITED TO JOIN",
) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val palette = gradientFor(invite.serverName)
    Column(
        modifier
            .width(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (colors.isDark) colors.surfaceRaised else colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Brush.linearGradient(palette.map { it.copy(alpha = 0.85f) })),
        )
        Column(Modifier.padding(14.dp)) {
            Text(label, style = DlTheme.type.micro, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = invite.serverName, size = 52.dp, shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(invite.serverName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(8.dp).background(colors.online, CircleShape))
                        Text("${"%,d".format(invite.online)} Online", style = DlTheme.type.footnote, color = colors.textSecondary)
                        Spacer(Modifier.width(3.dp))
                        Box(Modifier.size(8.dp).background(colors.textTertiary, CircleShape))
                        Text("${"%,d".format(invite.members)} Members", style = DlTheme.type.footnote, color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            JoinButton(joined = joined, onJoin = {
                haptics.confirm()
                onJoin()
            })
        }
    }
}

@Composable
private fun JoinButton(joined: Boolean, onJoin: () -> Unit) {
    val colors = DlTheme.colors
    val bg by animateColorAsState(
        if (joined) (if (colors.isDark) Color(0xFF2A2A33) else colors.bubbleOther) else colors.success,
        DlMotion.snappy(),
        label = "joinBg",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .pressable(enabled = !joined, pressedScale = 0.97f, onClick = onJoin)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = joined,
            transitionSpec = {
                (fadeIn(DlMotion.snappy()) + scaleIn(DlMotion.pop(), initialScale = 0.6f)) togetherWith
                    fadeOut(DlMotion.snappy()) using SizeTransform(clip = false) { _, _ -> DlMotion.snappy() }
            },
            label = "join",
        ) { isJoined ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isJoined) Icon(Icons.Rounded.Check, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                Text(
                    if (isJoined) "Joined" else "Join",
                    style = DlTheme.type.headline,
                    color = if (isJoined) colors.textPrimary else Color.White,
                )
            }
        }
    }
}

/** Capsule tag naming the game a post or reel is about. */
@Composable
fun GameTag(game: String, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    Row(
        modifier
            .background(colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.10f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.SportsEsports, null, tint = colors.accent, modifier = Modifier.size(13.dp))
        Text(game, style = DlTheme.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = colors.accent)
    }
}

/** Rounded section card for grouped lists (settings, channel lists). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = DlTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (colors.isDark) colors.surface else colors.surface),
    ) { content() }
}
