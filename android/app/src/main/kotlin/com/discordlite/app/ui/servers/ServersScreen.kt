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
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Tag
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
import com.discordlite.app.data.SampleData
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.CategoryResponse
import com.discordlite.app.data.remote.ChannelResponse
import com.discordlite.app.data.remote.Permissions
import com.discordlite.app.data.remote.ServerResponse
import com.discordlite.app.data.remote.ServerSummaryResponse
import com.discordlite.app.ui.chats.CheckCircle
import com.discordlite.app.ui.chats.PeoplePicker
import com.discordlite.app.ui.chats.SheetHeader
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.DlTextField
import kotlinx.coroutines.launch


/**
 * The Servers tab: a rail of your servers on the left (Discord's circles-into-squares icons) and
 * the selected server's channels on the right. Server channels are not end-to-end encrypted.
 */
@Composable
fun ServersScreen(
    onOpenChannel: (serverId: String, channelId: String) -> Unit,
    onOpenSettings: (serverId: String) -> Unit,
    onCreateServer: () -> Unit,
    onAddMembers: (serverId: String) -> Unit,
    onInvite: (serverId: String) -> Unit,
) {
    val colors = DlTheme.colors
    val repo = LocalAppContainer.current.servers
    val servers by repo.servers.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    val server by remember(selected?.id) { repo.server(selected?.id.orEmpty()) }.collectAsStateWithLifecycle(null)
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { repo.refresh() }
        loading = false
    }
    LaunchedEffect(selected?.id) { selected?.id?.let { runCatching { repo.load(it) } } }

    Row(Modifier.fillMaxSize().background(colors.background)) {
        ServerRail(servers, selected?.id, onSelect = { selectedId = it }, onCreate = onCreateServer)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(topStart = 24.dp))
                .background(colors.surface),
        ) {
            when {
                servers.isEmpty() && !loading -> NoServers(onCreateServer)
                server == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.5.dp)
                }
                else -> AnimatedContent(
                    targetState = server!!,
                    contentKey = { it.id },
                    transitionSpec = { fadeIn(DlMotion.snappy()) togetherWith fadeOut(DlMotion.snappy()) },
                    label = "server",
                ) { s ->
                    ChannelPanel(
                        s,
                        onOpenChannel = { onOpenChannel(s.id, it) },
                        onOpenSettings = { onOpenSettings(s.id) },
                        onAddMembers = { onAddMembers(s.id) },
                        onInvite = { onInvite(s.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRail(servers: List<ServerSummaryResponse>, selectedId: String?, onSelect: (String) -> Unit, onCreate: () -> Unit) {
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
        items(servers, key = { it.id }) { server ->
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
            RailIcon(selected = false, unread = false, onClick = onCreate) {
                Box(Modifier.fillMaxSize().background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Create server", tint = colors.success, modifier = Modifier.size(26.dp))
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
private fun ChannelPanel(server: ServerResponse, onOpenChannel: (String) -> Unit, onOpenSettings: () -> Unit, onAddMembers: () -> Unit, onInvite: () -> Unit) {
    val loose = server.channels.filter { it.categoryId == null }
    LazyColumn(contentPadding = PaddingValues(bottom = tabBarClearance())) {
        item(key = "header") { ServerHeader(server, onOpenSettings, onAddMembers, onInvite) }
        if (loose.isNotEmpty()) {
            item(key = "loose") { Column { loose.forEach { channel -> ChannelRow(channel) { onOpenChannel(channel.id) } } } }
        }
        items(server.categories, key = { it.id }) { category ->
            CategorySection(category, server.channels.filter { it.categoryId == category.id }, onOpenChannel)
        }
        if (server.channels.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (server.can(Permissions.MANAGE_CHANNELS)) "No channels yet. Add one in Server Settings." else "No channels yet.",
                    style = DlTheme.type.subheadline,
                    color = DlTheme.colors.textTertiary,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

/** Banner (tap for Server Settings), member count, then "Invite" and "Add friends" for people allowed to. */
@Composable
private fun ServerHeader(server: ServerResponse, onOpenSettings: () -> Unit, onAddMembers: () -> Unit, onInvite: () -> Unit) {
    val colors = DlTheme.colors
    val palette = gradientFor(server.name)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pressable(pressedScale = 0.98f, onClick = onOpenSettings)
                .background(Brush.linearGradient(palette)),
        ) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = DlTheme.type.title3, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ExpandMore, "Server settings", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colors.textTertiary, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(if (server.memberCount == 1) "1 member" else "${"%,d".format(server.memberCount)} members", style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        val canInvite = server.can(Permissions.CREATE_INVITES)
        val canAdd = server.can(Permissions.ADD_MEMBERS)
        if (canInvite || canAdd) {
            Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInvite) HeaderButton(Icons.Rounded.Link, "Invite", onInvite, Modifier.weight(1f))
                if (canAdd) HeaderButton(Icons.Rounded.PersonAdd, "Add friends", onAddMembers, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    Row(
        modifier
            .height(40.dp)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.12f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = DlTheme.type.headline, color = colors.accent)
    }
}

@Composable
private fun CategorySection(category: CategoryResponse, channels: List<ChannelResponse>, onOpenChannel: (String) -> Unit) {
    val colors = DlTheme.colors
    var expanded by rememberSaveable(category.id) { mutableStateOf(true) }
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
                channels.forEach { channel -> ChannelRow(channel) { onOpenChannel(channel.id) } }
            }
        }
    }
}

/** Bold with a pill on the left when unread; a red badge counts @mentions of you. */
@Composable
private fun ChannelRow(channel: ChannelResponse, onClick: () -> Unit) {
    val colors = DlTheme.colors
    val unread = channel.unread || channel.mentions > 0
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
        if (unread) {
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
            Icon(Icons.Rounded.Tag, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                channel.name,
                style = DlTheme.type.callout.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium),
                color = if (unread) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (channel.mentions > 0) {
                Box(
                    Modifier.size(20.dp).background(colors.danger, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${channel.mentions}", style = DlTheme.type.micro.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            }
        }
    }
}

@Composable
private fun NoServers(onCreate: () -> Unit) {
    val colors = DlTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Tag, null, tint = colors.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("No servers yet", style = DlTheme.type.title3, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Make a server for your squad: text channels, roles, and room for everyone. Add friends to it once it's made.",
            style = DlTheme.type.subheadline,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .pressable(pressedScale = 0.95f, onClick = onCreate)
                .background(Brush.horizontalGradient(colors.accentGradient), CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text("Create a server", style = DlTheme.type.headline, color = Color.White) }
    }
}

private enum class Template(val key: String, val title: String, val detail: String) {
    Gaming("gaming", "Gaming squad", "#welcome, #general, #clips, #lfg and an Admin role"),
    Blank("blank", "Blank", "Just #general — build the rest yourself"),
}

/** Sheet content: name the server, pick a starter template, create. */
@Composable
fun CreateServerSheetContent(onCancel: () -> Unit, onCreated: (String) -> Unit) {
    val colors = DlTheme.colors
    val repo = LocalAppContainer.current.servers
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(Template.Gaming) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Create Server",
            onCancel = onCancel,
            confirm = "Create",
            confirmEnabled = name.isNotBlank() && !busy,
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching { repo.create(name, template.key, emptyList()) }
                        .onSuccess {
                            haptics.confirm()
                            onCreated(it)
                        }
                        .onFailure {
                            haptics.reject()
                            error = serverError(it)
                            errorTick++
                        }
                    busy = false
                }
            },
        )
        DlTextField(name, { name = it.take(100) }, placeholder = "Server name", modifier = Modifier.padding(horizontal = 16.dp))
        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Text("TEMPLATE", style = DlTheme.type.micro, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Template.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f)) {
                        if (template != option) haptics.selection()
                        template = option
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(option.title, style = DlTheme.type.headline, color = colors.textPrimary)
                    Text(option.detail, style = DlTheme.type.footnote, color = colors.textSecondary)
                }
                CheckCircle(template == option)
            }
        }
        Text(
            "Server channels aren't end-to-end encrypted, so people who join later can read the history. DMs and group chats are.",
            style = DlTheme.type.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** Sheet content: add friends to a server (people already in it show "In group"). */
@Composable
fun AddToServerSheetContent(serverId: String, onDone: () -> Unit) {
    val repo = LocalAppContainer.current.servers
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val members by remember(serverId) { repo.members(serverId) }.collectAsStateWithLifecycle(null)
    var picked by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(serverId) { runCatching { repo.loadMembers(serverId) } }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Add Friends",
            onCancel = onDone,
            confirm = "Add",
            confirmEnabled = picked.isNotEmpty() && !busy,
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching { repo.addMembers(serverId, picked) }
                        .onSuccess {
                            haptics.confirm()
                            picked = emptyList()
                            onDone()
                        }
                        .onFailure {
                            haptics.reject()
                            error = serverError(it)
                            errorTick++
                        }
                    busy = false
                }
            },
        )
        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        PeoplePicker(
            selected = picked,
            onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
            limit = 100,
            alreadyIn = members?.map { it.id }?.toSet().orEmpty(),
        )
    }
}

internal fun serverError(error: Throwable): String = when ((error as? ApiError)?.code) {
    "network" -> "No connection. Check your internet and try again."
    "not_friends" -> "You can only add people from your friends list."
    "missing_permission" -> "You don't have permission to do that."
    "cannot_grant_permission" -> "You can't give out powers you don't have yourself."
    "target_is_owner" -> "That's the server owner."
    "target_has_powers" -> "Only the owner can do that to someone with management powers."
    "owner_cannot_leave" -> "You own this server. Delete it instead, or it stays yours."
    "invalid_server_name" -> "Give the server a name (up to 100 characters)."
    "invalid_channel_name" -> "Channel names use letters, numbers, - and _."
    "invalid_role_name" -> "Give the role a name (up to 32 characters, not \"everyone\")."
    "invalid_category_name" -> "Give the category a name (up to 32 characters)."
    "server_full" -> "This server is full."
    "server_not_found", "channel_not_found" -> "This server or channel no longer exists."
    "invite_invalid" -> "This invite has expired, been used up, or deleted."
    "banned_from_server" -> "You're banned from this server."
    "cannot_ban_self" -> "You can't ban yourself."
    else -> "Something went wrong. Try again."
}
