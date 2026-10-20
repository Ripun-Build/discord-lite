package com.discordlite.app.ui.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.discordlite.app.data.LocalAppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.dp
import com.discordlite.app.data.ME
import com.discordlite.app.data.Message
import com.discordlite.app.data.SampleData
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.InviteCard
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.glass
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.gradientFor
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Replies = listOf("😂 no way", "say less", "ok I'm hopping on in 5", "lmaooo", "bet 🔥", "send the clip!!")

/** A direct message or group DM. All demo state lives in memory. */
@Composable
fun ConversationScreen(chatId: String, onBack: () -> Unit) {
    val chat = remember(chatId) { SampleData.chat(chatId) }
    val colors = DlTheme.colors
    MessageThread(
        initial = chat.messages,
        placeholder = "Message",
        bubbles = true,
        fakeReplies = chat.members,
        onBack = onBack,
        title = {
            if (chat.isGroup) {
                GroupAvatar(chat.members, 38.dp)
            } else {
                Avatar(chat.title, 38.dp, online = chat.online)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = colors.success, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (chat.isGroup) "${chat.members.size + 1} members · encrypted" else if (chat.online) "Online · encrypted" else "End-to-end encrypted",
                        style = DlTheme.type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        },
        actions = { IconCircleButton(Icons.Rounded.Call, "Call (coming later)", {}, size = 38.dp) },
        header = { EncryptionNotice() },
    )
}

/** A server text channel: Discord-style rows (avatar + name + text) rather than bubbles. Not E2EE. */
@Composable
fun ChannelScreen(serverId: String, channelId: String, onBack: () -> Unit) {
    val server = remember(serverId) { SampleData.server(serverId) }
    val channel = remember(serverId, channelId) { server.categories.flatMap { it.channels }.first { it.id == channelId } }
    val colors = DlTheme.colors
    MessageThread(
        initial = remember(serverId, channelId) { SampleData.channelMessages(serverId, channel.name) },
        placeholder = "Message #${channel.name}",
        bubbles = false,
        fakeReplies = emptyList(),
        onBack = onBack,
        title = {
            Icon(Icons.Rounded.Tag, null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.name, style = DlTheme.type.caption, color = colors.textSecondary, maxLines = 1)
            }
        },
        actions = { IconCircleButton(Icons.Rounded.Group, "Members", {}, size = 38.dp) },
        header = { ChannelWelcome(channel.name) },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MessageThread(
    initial: List<Message>,
    placeholder: String,
    bubbles: Boolean,
    fakeReplies: List<com.discordlite.app.data.User>,
    onBack: () -> Unit,
    title: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    header: @Composable () -> Unit,
) {
    val messages = remember { mutableStateListOf<Message>().apply { addAll(initial) } }
    val justSent = remember { mutableStateSetOf<Long>() }
    var draft by rememberSaveable { mutableStateOf("") }
    var typing by remember { mutableStateOf<com.discordlite.app.data.User?>(null) }
    var joinedInvite by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = if (WindowInsets.isImeVisible) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val msg = Message(System.nanoTime(), ME, text, LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")))
        justSent += msg.id
        messages += msg
        draft = ""
        haptics.tick()
        if (fakeReplies.isNotEmpty()) {
            scope.launch {
                // Demo only: simulates a friend typing back so the real-time feel can be reviewed.
                delay(700)
                val who = fakeReplies.random()
                typing = who
                delay(1500)
                typing = null
                messages += Message(System.nanoTime(), who.id, Replies.random(), LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")))
            }
        }
    }

    val display = messages.asReversed()

    // Follow new messages (and the typing indicator) when already at the bottom; if the user has
    // scrolled up to read history, leave them where they are.
    LaunchedEffect(messages.size, typing) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    val latestMine = messages.lastOrNull { it.authorId == ME }?.id

    GlassScaffold(
        modifier = Modifier.imePadding(),
        overlay = {
            DetailTopBar(onBack = onBack, title = title, actions = actions)
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                placeholder = placeholder,
                onSend = ::send,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop + TopBarHeight + 18.dp, bottom = 74.dp + navBottom),
        ) {
            typing?.let { who ->
                item(key = "typing") {
                    TypingIndicator(who.name, bubbles, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold)))
                }
            }
            itemsIndexed(display, key = { _, m -> m.id }) { index, message ->
                val newer = display.getOrNull(index - 1)
                val older = display.getOrNull(index + 1)
                val firstOfGroup = older?.authorId != message.authorId
                val lastOfGroup = newer?.authorId != message.authorId
                val animateIn = message.id in justSent
                Box(
                    Modifier
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold),
                            fadeOutSpec = null,
                        )
                        .sendAnimation(animateIn, mine = message.authorId == ME) { justSent -= message.id },
                ) {
                    if (bubbles) {
                        BubbleMessage(message, firstOfGroup, lastOfGroup, receipt = message.id == latestMine, joined = joinedInvite) { joinedInvite = true }
                    } else {
                        ChannelMessage(message, firstOfGroup)
                    }
                }
            }
            item(key = "header") { header() }
        }
    }
}

/**
 * New messages are "thrown" out of the composer: they grow from the bottom-right corner (where
 * the send button is) with a little momentum, so the cause and effect read spatially.
 */
@Composable
private fun Modifier.sendAnimation(active: Boolean, mine: Boolean, onDone: () -> Unit): Modifier {
    if (!active) return this
    val reduced = DlTheme.reducedMotion
    // Width and height grow on different springs, so the bubble lands like a drop of jelly.
    val px = remember { Animatable(0f) }
    val py = remember { Animatable(0f) }
    val rise = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(Unit) {
        if (reduced) {
            px.snapTo(1f)
            py.animateTo(1f, DlMotion.smooth())
        } else {
            launch { px.animateTo(1f, DlMotion.spring(0.6f, 0.32f)) }
            py.animateTo(1f, DlMotion.spring(0.68f, 0.44f))
        }
        onDone()
    }
    return graphicsLayer {
        alpha = py.value.coerceIn(0f, 1f)
        if (!reduced) {
            transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
            scaleX = 0.6f + 0.4f * px.value
            scaleY = 0.6f + 0.4f * py.value
            translationY = (1f - py.value) * rise
        }
    }
}

@Composable
private fun BubbleMessage(message: Message, firstOfGroup: Boolean, lastOfGroup: Boolean, receipt: Boolean, joined: Boolean, onJoin: () -> Unit) {
    val colors = DlTheme.colors
    val mine = message.authorId == ME
    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (firstOfGroup) big else small, bottomEnd = if (lastOfGroup) big else small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (firstOfGroup) big else small, topEnd = big, bottomEnd = big, bottomStart = if (lastOfGroup) big else small)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = if (firstOfGroup) 10.dp else 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (message.text.isNotBlank()) {
            Box(
                Modifier
                    .widthIn(max = 290.dp)
                    .pressable(pressedScale = 0.97f) {}
                    .clip(shape)
                    .background(if (mine) Brush.linearGradient(listOf(colors.accentGradient[0], colors.accentGradient[1])) else SolidColor(colors.bubbleOther))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(message.text, style = DlTheme.type.body, color = if (mine) Color.White else colors.textPrimary)
            }
        }
        message.invite?.let { invite ->
            Spacer(Modifier.height(4.dp))
            InviteCard(invite = invite, joined = joined, onJoin = onJoin)
        }
        if (lastOfGroup || receipt) {
            Text(
                if (receipt) "${message.time} · Read" else message.time,
                style = DlTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun ChannelMessage(message: Message, firstOfGroup: Boolean) {
    val colors = DlTheme.colors
    val me by LocalAppContainer.current.account.me.collectAsStateWithLifecycle()
    // Your own messages use your real display name; everyone else is still sample data until Phase 4.
    val authorName = if (message.authorId == ME) me?.displayName ?: SampleData.me.name else SampleData.user(message.authorId).name
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 1f, highlight = colors.textPrimary.copy(alpha = 0.05f)) {}
            .padding(start = 16.dp, end = 16.dp, top = if (firstOfGroup) 14.dp else 2.dp, bottom = 2.dp),
    ) {
        if (firstOfGroup) Avatar(authorName, 40.dp) else Spacer(Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (firstOfGroup) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(authorName, style = DlTheme.type.headline, color = gradientFor(authorName)[0])
                    Spacer(Modifier.width(8.dp))
                    Text("Today at ${message.time}", style = DlTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 1.dp))
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(message.text, style = DlTheme.type.body, color = colors.textPrimary)
        }
    }
}

/** Three dots that breathe in sequence — a short, quick cycle, not a slow oscillation. */
@Composable
private fun TypingIndicator(name: String, bubble: Boolean, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (bubble) colors.bubbleOther else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                val v by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse, StartOffset(i * 140)),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = v; translationY = (1f - v) * 4f }
                        .background(colors.textSecondary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${name.substringBefore(' ')} is typing", style = DlTheme.type.caption, color = colors.textTertiary)
    }
}

@Composable
private fun EncryptionNotice() {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, tint = colors.success, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Messages are end-to-end encrypted. No one outside this chat — not even discord-lite — can read them.",
                style = DlTheme.type.caption,
                color = colors.textSecondary,
            )
        }
    }
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
        Text("This is the start of the #$channel channel.", style = DlTheme.type.subheadline, color = colors.textSecondary)
    }
}

/**
 * Glass composer. The mic swaps for a gradient send button the moment there's text — it pops in
 * from its own centre so the change reads as the same control transforming.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DlTheme.colors
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(modifier.fillMaxWidth().glass(RectangleShape, edge = false)) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconCircleButton(Icons.Rounded.Add, "Attach", {}, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (colors.isDark) colors.surfaceRaised.copy(alpha = 0.9f) else colors.surface)
                    .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) Text(placeholder, style = DlTheme.type.body, color = colors.textTertiary, maxLines = 1)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = DlTheme.type.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = draft.isNotBlank(),
                transitionSpec = {
                    (scaleIn(DlMotion.pop(), initialScale = 0.5f) + fadeIn(DlMotion.snappy())) togetherWith
                        (scaleOut(DlMotion.snappy(), targetScale = 0.5f) + fadeOut(DlMotion.snappy()))
                },
                label = "send",
            ) { canSend ->
                if (canSend) {
                    IconCircleButton(Icons.Rounded.ArrowUpward, "Send", onSend, size = 40.dp, gradient = true)
                } else {
                    IconCircleButton(Icons.Rounded.Mic, "Voice note (coming later)", {}, size = 40.dp, filled = false, tint = colors.textSecondary)
                }
            }
        }
    }
}
