package com.discordlite.app.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discordlite.app.data.Category
import com.discordlite.app.data.Channel
import com.discordlite.app.data.SampleData
import com.discordlite.app.data.Server
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.InviteCard
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.component.SegmentedControl
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.component.tabBarClearance
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.gradientFor
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay

@Composable
fun ServersScreen(onOpenChannel: (serverId: String, channelId: String) -> Unit, onInvite: (serverId: String) -> Unit) {
    val colors = DlTheme.colors
    var selectedId by rememberSaveable { mutableStateOf(SampleData.servers.first().id) }
    val server = SampleData.server(selectedId)

    Row(Modifier.fillMaxSize().background(colors.background)) {
        ServerRail(selectedId) { selectedId = it }
        AnimatedContent(
            targetState = server,
            transitionSpec = { fadeIn(DlMotion.snappy()) togetherWith fadeOut(DlMotion.snappy()) },
            modifier = Modifier.weight(1f),
            label = "server",
        ) { s ->
            ChannelPanel(s, onOpenChannel = { onOpenChannel(s.id, it) }, onInvite = { onInvite(s.id) })
        }
    }
}

@Composable
private fun ServerRail(selectedId: String, onSelect: (String) -> Unit) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    LazyColumn(
        Modifier.width(76.dp).fillMaxHeight(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
            bottom = tabBarClearance(),
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            RailIcon(selected = false, unread = false) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors.accentGradient)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Forum, "Direct messages", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
        item { Box(Modifier.padding(vertical = 4.dp).width(32.dp).height(1.5.dp).background(colors.separator, CircleShape)) }
        items(SampleData.servers, key = { it.id }) { server ->
            RailIcon(
                selected = server.id == selectedId,
                unread = server.unread,
                mentions = server.mentions,
                onClick = {
                    if (server.id != selectedId) haptics.selection()
                    onSelect(server.id)
                },
            ) {
                Avatar(server.name, 52.dp, shape = RoundedCornerShape(0.dp))
            }
        }
        item {
            RailIcon(selected = false, unread = false) {
                Box(Modifier.fillMaxSize().background(if (colors.isDark) colors.surfaceRaised else colors.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Create server", tint = colors.success, modifier = Modifier.size(26.dp))
                }
            }
        }
        item {
            RailIcon(selected = false, unread = false) {
                Box(Modifier.fillMaxSize().background(if (colors.isDark) colors.surfaceRaised else colors.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Explore, "Discover servers", tint = colors.success, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Discord's signature: icons are circles that morph into rounded squares when selected, with a pill
 * on the left edge that grows from an unread dot to a full bar. Both springs start from the live
 * value, so switching quickly between servers stays fluid.
 */
@Composable
private fun RailIcon(
    selected: Boolean,
    unread: Boolean,
    mentions: Int = 0,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = DlTheme.colors
    val corner by animateDpAsState(if (selected) 16.dp else 26.dp, DlMotion.snappy(), label = "corner")
    val pill by animateDpAsState(
        when {
            selected -> 36.dp
            unread -> 8.dp
            else -> 0.dp
        },
        DlMotion.snappy(),
        label = "pill",
    )
    Box(Modifier.fillMaxWidth().height(58.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .height(pill)
                .background(colors.textPrimary, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(52.dp)
                .pressable(pressedScale = 0.9f, onClick = onClick)
                .clip(RoundedCornerShape(corner)),
        ) { content() }
        if (mentions > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-6).dp, y = (-2).dp)
                    .size(20.dp)
                    .border(3.dp, colors.background, CircleShape)
                    .padding(3.dp)
                    .background(colors.danger, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$mentions", style = DlTheme.type.micro.copy(fontWeight = FontWeight.Bold), color = Color.White) }
        }
    }
}

@Composable
private fun ChannelPanel(server: Server, onOpenChannel: (String) -> Unit, onInvite: () -> Unit) {
    val colors = DlTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(topStart = 24.dp))
            .background(colors.surface),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = tabBarClearance())) {
            item { ServerHeader(server, onInvite) }
            items(server.categories, key = { it.name }) { category -> CategorySection(category, onOpenChannel) }
        }
    }
}

@Composable
private fun ServerHeader(server: Server, onInvite: () -> Unit) {
    val colors = DlTheme.colors
    val palette = gradientFor(server.name)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Brush.linearGradient(palette)),
        ) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = DlTheme.type.title3, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ExpandMore, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colors.online, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text("${"%,d".format(server.online)} online", style = DlTheme.type.footnote, color = colors.textSecondary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(8.dp).background(colors.textTertiary, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text("${"%,d".format(server.members)} members", style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        Row(
            Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .height(40.dp)
                .pressable(pressedScale = 0.97f, onClick = onInvite)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.12f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.PersonAdd, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Invite friends", style = DlTheme.type.headline, color = colors.accent)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CategorySection(category: Category, onOpenChannel: (String) -> Unit) {
    val colors = DlTheme.colors
    var expanded by rememberSaveable(category.name) { mutableStateOf(true) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, DlMotion.snappy(), label = "chevron")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.98f) { expanded = !expanded }
                .padding(start = 8.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ExpandMore, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
            Spacer(Modifier.width(2.dp))
            Text(category.name.uppercase(), style = DlTheme.type.micro, color = colors.textTertiary)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(DlMotion.smooth()) + fadeIn(DlMotion.smooth()),
            exit = shrinkVertically(DlMotion.smooth()) + fadeOut(DlMotion.snappy()),
        ) {
            Column {
                category.channels.forEach { channel -> ChannelRow(channel) { if (!channel.voice) onOpenChannel(channel.id) } }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
        if (channel.unread) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = (-8).dp).width(4.dp).height(8.dp).background(colors.textPrimary, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .pressable(pressedScale = 0.98f, highlight = colors.textPrimary.copy(alpha = 0.07f), onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (channel.voice) Icons.Rounded.VolumeUp else Icons.Rounded.Tag,
                null,
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                channel.name,
                style = DlTheme.type.callout.copy(fontWeight = if (channel.unread) FontWeight.SemiBold else FontWeight.Medium),
                color = if (channel.unread) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.voice) {
                Spacer(Modifier.weight(1f))
                Text("Soon", style = DlTheme.type.micro, color = colors.textTertiary)
            }
        }
    }
}

private val ExpiryOptions = listOf("30 min", "1 hour", "1 day", "7 days", "Never")
private val UseOptions = listOf("No limit", "1", "5", "10", "25")

/** Sheet content: create and share a Discord-style invite link, with a live preview of its card. */
@Composable
fun InviteSheetContent(serverId: String) {
    val colors = DlTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val server = SampleData.server(serverId)
    var expiry by rememberSaveable { mutableIntStateOf(3) }
    var uses by rememberSaveable { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val link = "https://discord-lite.app/invite/${server.inviteCode}"

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Invite friends to ${server.name}", style = DlTheme.type.title3, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Anyone with this link can join the server.", style = DlTheme.type.subheadline, color = colors.textSecondary)
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(link.removePrefix("https://"), style = DlTheme.type.callout, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .height(38.dp)
                    .pressable(pressedScale = 0.94f) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link))
                        haptics.confirm()
                        copied = true
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (copied) colors.success else colors.accent)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = { (scaleIn(DlMotion.pop(), initialScale = 0.6f) + fadeIn(DlMotion.snappy())) togetherWith fadeOut(DlMotion.snappy()) },
                    label = "copy",
                ) { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (done) "Copied" else "Copy", style = DlTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("EXPIRE AFTER", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        SegmentedControl(ExpiryOptions, expiry, { expiry = it })
        Spacer(Modifier.height(14.dp))
        Text("MAX NUMBER OF USES", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        SegmentedControl(UseOptions, uses, { uses = it })

        Spacer(Modifier.height(18.dp))
        Text("PREVIEW", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            InviteCard(server.invite, joined = true, onJoin = {})
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Share invite link", onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Join ${server.name} on discord-lite: $link")
            }
            context.startActivity(Intent.createChooser(send, "Share invite"))
        })
    }
}
