package com.discordlite.app.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.Permissions
import com.discordlite.app.data.remote.RoleResponse
import com.discordlite.app.data.remote.ServerMemberResponse
import com.discordlite.app.data.remote.ServerResponse
import com.discordlite.app.servers.ChannelMessage
import com.discordlite.app.ui.chats.Composer
import com.discordlite.app.ui.chats.TypingIndicator
import com.discordlite.app.ui.chats.timeLabel
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.glass
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.gradientFor
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** Messages closer together than this, from the same person, share one name header. */
private const val GROUP_WINDOW_MS = 5 * 60_000L
private val MentionToken = Regex("(?<![\\w@])@([a-zA-Z0-9._]{3,20})")

/** Discord's gold for "this message mentions you". */
private val MentionTint = Color(0xFFF0B232)

/**
 * A server text channel: Discord-style rows (avatar, coloured name, text) rather than bubbles.
 * Not end-to-end encrypted. Long-press a message to copy or delete it; type @ to mention someone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerChannelScreen(serverId: String, channelId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.servers
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val history by remember(channelId) { repo.history(channelId) }.collectAsStateWithLifecycle(com.discordlite.app.servers.ChannelHistory())
    val members by remember(serverId) { repo.members(serverId) }.collectAsStateWithLifecycle(null)
    val typingByChannel by repo.typing.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ChannelMessage?>(null) }
    var lastSelected by remember { mutableStateOf<ChannelMessage?>(null) }
    if (selected != null) lastSelected = selected
    var showMembers by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val channel = server?.channels?.firstOrNull { it.id == channelId }
    val channelName = channel?.name ?: "…"
    val memberById = members?.associateBy { it.id }.orEmpty()
    fun nameOf(id: String, fallback: String) = if (id == me?.id) me?.displayName ?: fallback else memberById[id]?.displayName ?: fallback.ifEmpty { "Someone" }
    fun colorOf(id: String): Color? = server?.let { s -> topRole(s, memberById[id])?.color?.takeIf { it != 0 }?.let { Color(it) } }

    LaunchedEffect(serverId, channelId) {
        if (server == null) runCatching { repo.load(serverId) }
        runCatching { repo.loadLatest(channelId, force = true) }
        runCatching { repo.loadMembers(serverId) }
    }
    LifecycleResumeEffect(channelId) {
        repo.openChannel = channelId
        onPauseOrDispose { if (repo.openChannel == channelId) repo.openChannel = null }
    }
    // Reading the channel: clear its dot and mention badge whenever new messages show up.
    LaunchedEffect(history.messages.lastOrNull()?.id) { repo.markRead(serverId, channelId) }

    val display = history.messages.asReversed()
    // Follow new messages when already at the bottom.
    LaunchedEffect(history.messages.size) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
    // Near the top of what's loaded: fetch the page before it.
    val nearTop by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearTop, history.loaded, history.complete) {
        if (nearTop && history.loaded && !history.complete && !loadingOlder) {
            loadingOlder = true
            runCatching { repo.loadOlder(channelId) }
            loadingOlder = false
        }
    }

    val typingNames = typingByChannel[channelId].orEmpty().map { nameOf(it, "Someone") }
    val mentionQuery = Regex("(?:^|\\s)@([a-zA-Z0-9._]*)$").find(draft)?.groupValues?.get(1)
    val suggestions = if (mentionQuery == null) emptyList() else members.orEmpty().filter {
        it.id != me?.id && (it.username.startsWith(mentionQuery, ignoreCase = true) || it.displayName.startsWith(mentionQuery, ignoreCase = true))
    }.take(6)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = if (WindowInsets.isImeVisible) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassScaffold(
        modifier = Modifier.imePadding(),
        overlay = {
            DetailTopBar(
                onBack = onBack,
                title = {
                    Icon(Icons.Rounded.Tag, null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channelName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                typingNames.size == 1 -> "${typingNames[0].substringBefore(' ')} is typing…"
                                typingNames.size > 1 -> "${typingNames.size} people are typing…"
                                else -> server?.name ?: ""
                            },
                            style = DlTheme.type.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                },
                actions = { IconCircleButton(Icons.Rounded.Group, "Members", { showMembers = true }, size = 38.dp) },
            )
            Column(Modifier.align(Alignment.BottomCenter)) {
                AnimatedVisibility(
                    visible = suggestions.isNotEmpty(),
                    enter = expandVertically(DlMotion.smooth()) + fadeIn(DlMotion.snappy()),
                    exit = shrinkVertically(DlMotion.smooth()) + fadeOut(DlMotion.snappy()),
                ) {
                    LazyRow(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(0.dp), edge = false),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(suggestions, key = { it.id }) { member ->
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .pressable(pressedScale = 0.94f) {
                                        haptics.selection()
                                        draft = draft.replace(Regex("@([a-zA-Z0-9._]*)$"), "@${member.username} ")
                                    }
                                    .background(colors.accent.copy(alpha = 0.15f))
                                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(member.displayName, 26.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("@${member.username}", style = DlTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.accent)
                            }
                        }
                    }
                }
                Composer(
                    draft = draft,
                    onDraftChange = {
                        draft = it
                        if (it.isNotBlank()) repo.onTyping(channelId)
                    },
                    placeholder = "Message #$channelName",
                    onSend = {
                        val text = draft
                        draft = ""
                        haptics.tick()
                        scope.launch { repo.send(channelId, text) }
                    },
                )
            }
            // Sheets go above the composer.
            SpringSheet(visible = selected != null, onDismiss = { selected = null }) {
                lastSelected?.let { message ->
                    val canDelete = message.authorId == me?.id || server?.can(Permissions.MANAGE_MESSAGES) == true
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            message.body,
                            style = DlTheme.type.subheadline,
                            color = colors.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                        ActionRow(Icons.Rounded.ContentCopy, "Copy text", colors.textPrimary) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.body))
                            haptics.confirm()
                            selected = null
                        }
                        if (canDelete) {
                            ActionRow(Icons.Rounded.Delete, "Delete message", colors.danger) {
                                selected = null
                                scope.launch { runCatching { repo.deleteMessage(message) } }
                            }
                        }
                    }
                }
            }

            SpringSheet(visible = showMembers, onDismiss = { showMembers = false }) {
                MembersSheet(server, members.orEmpty())
            }
        },
    ) {
        if (!history.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.5.dp)
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop + TopBarHeight + 18.dp, bottom = 74.dp + navBottom),
        ) {
            typingNames.firstOrNull()?.let { name ->
                item(key = "typing") { TypingIndicator(name, bubble = false, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold))) }
            }
            itemsIndexed(display, key = { _, m -> m.clientId ?: m.id.toString() }) { index, message ->
                val older = display.getOrNull(index + 1)
                val first = older == null || older.authorId != message.authorId || message.createdAt - older.createdAt > GROUP_WINDOW_MS
                Box(Modifier.animateItem(fadeInSpec = null, placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold), fadeOutSpec = null)) {
                    ChannelMessageRow(
                        message = message,
                        author = nameOf(message.authorId, message.authorName),
                        authorColor = colorOf(message.authorId),
                        firstOfGroup = first,
                        onLongPress = {
                            haptics.tick()
                            selected = message
                        },
                        onRetry = { scope.launch { repo.retry(message) } },
                    )
                }
            }
            if (history.loaded && history.complete) item(key = "welcome") { ChannelWelcome(channelName) }
            if (loadingOlder) {
                item(key = "older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelMessageRow(
    message: ChannelMessage,
    author: String,
    authorColor: Color?,
    firstOfGroup: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            // Mentions of you get Discord's tinted background and accent bar.
            .background(if (message.mentionsMe) MentionTint.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = { if (message.failed) onRetry() },
                onLongClick = onLongPress,
            )
            .padding(start = 16.dp, end = 16.dp, top = if (firstOfGroup) 14.dp else 2.dp, bottom = 2.dp)
            .alpha(if (message.pending) 0.6f else 1f),
    ) {
        if (firstOfGroup) Avatar(author, 40.dp) else Spacer(Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (firstOfGroup) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(author, style = DlTheme.type.headline, color = authorColor ?: gradientFor(author)[0], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(timeLabel(message.createdAt), style = DlTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 1.dp))
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(withMentions(message.body, colors.accent), style = DlTheme.type.body, color = colors.textPrimary)
            InviteCards(message.body, Modifier.padding(top = 6.dp))
            if (message.failed) {
                Text("Not sent · Tap to retry", style = DlTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** @username in the accent colour, bold. */
private fun withMentions(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    MentionToken.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, background = accent.copy(alpha = 0.12f))) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
private fun ChannelWelcome(channel: String) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Tag, null, tint = colors.textPrimary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(12.dp))
        Text("Welcome to #$channel!", style = DlTheme.type.title2, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "This is the start of #$channel. Server channels aren't end-to-end encrypted — keep private things for DMs.",
            style = DlTheme.type.subheadline,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, tint: Color, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = DlTheme.type.body, color = tint)
    }
}

/** Members, grouped under their highest role (Discord's member list). */
@Composable
private fun MembersSheet(server: ServerResponse?, members: List<ServerMemberResponse>) {
    val colors = DlTheme.colors
    val groups = members.groupBy { member -> server?.let { topRole(it, member) } }
        .toList()
        .sortedByDescending { (role, _) -> role?.position ?: -1 }
    Column(Modifier.fillMaxWidth()) {
        Text("Members · ${members.size}", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            groups.forEach { (role, inRole) ->
                Text(
                    "${role?.name ?: "Members"} — ${inRole.size}".uppercase(),
                    style = DlTheme.type.micro,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                inRole.forEach { member -> MemberRow(member, role, isOwner = member.id == server?.ownerId) }
            }
        }
    }
}

@Composable
internal fun MemberRow(member: ServerMemberResponse, topRole: RoleResponse?, isOwner: Boolean, trailing: @Composable () -> Unit = {}) {
    val colors = DlTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.displayName, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.displayName,
                style = DlTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = topRole?.color?.takeIf { it != 0 }?.let { Color(it) } ?: colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("@${member.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        if (isOwner) {
            Text(
                "Owner",
                style = DlTheme.type.caption,
                color = colors.accent,
                modifier = Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        trailing()
    }
}

/** The member's highest role that isn't "everyone" (decides their name colour and list group). */
internal fun topRole(server: ServerResponse, member: ServerMemberResponse?): RoleResponse? =
    member?.let { m -> server.roles.filter { !it.isDefault && it.id in m.roleIds }.maxByOrNull { it.position } }
