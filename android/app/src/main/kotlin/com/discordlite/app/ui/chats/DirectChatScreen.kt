package com.discordlite.app.ui.chats

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.chat.ChatMessage
import com.discordlite.app.chat.GatewayClient
import com.discordlite.app.chat.MessageStatus
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A real one-to-one chat. Every message is end-to-end encrypted with the Signal Protocol on this
 * phone before it leaves; the server only relays ciphertext.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectChatScreen(peerId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val chat = container.chat
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val conversation by remember(peerId) { chat.conversation(peerId) }.collectAsStateWithLifecycle(null)
    val messages by remember(peerId) { chat.messages(peerId) }.collectAsStateWithLifecycle(emptyList())
    val connection by container.gateway.state.collectAsStateWithLifecycle()
    val typingPeers by chat.typing.collectAsStateWithLifecycle()
    val peerTyping = peerId in typingPeers
    var draft by rememberSaveable { mutableStateOf("") }
    // Plain set (not observable state): it only remembers which bubbles already played their entrance.
    val justSent = remember { HashSet<String>() }
    val listState = rememberLazyListState()
    // Ask for notifications the first time you send a message — that's when replies matter.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Mark as read while open; incoming messages here don't count as unread.
    // "Viewing this chat" only while the app is actually on screen: leaving the app (or the chat)
    // stops read receipts and lets notifications for this chat show again.
    LifecycleResumeEffect(peerId) {
        scope.launch { chat.open(peerId) }
        onPauseOrDispose { chat.close(peerId) }
    }

    val display = messages.asReversed()
    val latestMine = messages.lastOrNull { it.fromMe }?.clientId
    // The list first draws empty (just the header); when history loads, jump to the newest message
    // instead of keeping the header in view. After that, follow new messages only if already at the bottom.
    var initialScrollDone by remember(peerId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, peerTyping) {
        if (!initialScrollDone) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
                initialScrollDone = true
            }
        } else if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = if (WindowInsets.isImeVisible) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val name = conversation?.displayName ?: "…"

    GlassScaffold(
        modifier = Modifier.imePadding(),
        overlay = {
            DetailTopBar(
                onBack = onBack,
                title = {
                    Avatar(name, 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = colors.success, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                when {
                                    peerTyping -> "typing…"
                                    connection == GatewayClient.State.Online -> "End-to-end encrypted"
                                    connection == GatewayClient.State.Connecting -> "Connecting…"
                                    else -> "Waiting for network…"
                                },
                                style = DlTheme.type.caption,
                                color = colors.textSecondary,
                            )
                        }
                    }
                },
            )
            Composer(
                draft = draft,
                onDraftChange = {
                    draft = it
                    chat.onTyping(peerId, it.isNotBlank())
                },
                placeholder = "Message",
                onSend = {
                    val text = draft
                    draft = ""
                    haptics.tick()
                    if (Build.VERSION.SDK_INT >= 33 && !container.notifier.canNotify() && !container.settings.askedNotifications) {
                        container.settings.markAskedNotifications()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    scope.launch { chat.send(peerId, text) }
                },
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
            if (peerTyping) {
                item(key = "typing") {
                    TypingIndicator(name, bubble = true, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold)))
                }
            }
            itemsIndexed(display, key = { _, m -> m.localId }) { index, message ->
                val newer = display.getOrNull(index - 1)
                val older = display.getOrNull(index + 1)
                val first = older == null || older.fromMe != message.fromMe || older.status == MessageStatus.NOTICE
                val last = newer == null || newer.fromMe != message.fromMe || newer.status == MessageStatus.NOTICE
                // Animate only messages that appear while the chat is open.
                val animate = message.fromMe && message.status == MessageStatus.SENDING && justSent.add(message.clientId)
                Box(
                    Modifier
                        .animateItem(fadeInSpec = null, placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold), fadeOutSpec = null)
                        .sendAnimation(animate, mine = message.fromMe) {},
                ) {
                    if (message.status == MessageStatus.NOTICE) {
                        Notice(message.body)
                    } else {
                        Bubble(
                            message = message,
                            firstOfGroup = first,
                            lastOfGroup = last,
                            showStatus = message.fromMe && message.clientId == latestMine || message.status == MessageStatus.FAILED,
                            onRetry = { scope.launch { chat.retry(message) } },
                        )
                    }
                }
            }
            item(key = "header") { EncryptionNotice() }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, firstOfGroup: Boolean, lastOfGroup: Boolean, showStatus: Boolean, onRetry: () -> Unit) {
    val colors = DlTheme.colors
    val mine = message.fromMe
    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (firstOfGroup) big else small, bottomEnd = if (lastOfGroup) big else small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (firstOfGroup) big else small, topEnd = big, bottomEnd = big, bottomStart = if (lastOfGroup) big else small)
    }
    val failed = message.status == MessageStatus.FAILED
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = if (firstOfGroup) 10.dp else 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 290.dp)
                .pressable(pressedScale = 0.97f, onClick = { if (failed) onRetry() })
                .clip(shape)
                .background(
                    if (mine) Brush.linearGradient(listOf(colors.accentGradient[0], colors.accentGradient[1])) else SolidColor(colors.bubbleOther),
                    alpha = if (message.status == MessageStatus.SENDING) 0.75f else 1f,
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(message.body, style = DlTheme.type.body, color = if (mine) Color.White else colors.textPrimary)
        }
        if (lastOfGroup || showStatus) {
            val time = timeLabel(message.sentAt)
            val (label, tint) = when {
                !mine -> time to colors.textTertiary
                message.status == MessageStatus.SENDING -> "Sending…" to colors.textTertiary
                failed -> "Not sent · Tap to retry" to colors.danger
                showStatus && message.status == MessageStatus.READ -> "$time · Read" to colors.accent
                showStatus && message.status == MessageStatus.DELIVERED -> "$time · Delivered" to colors.textTertiary
                showStatus -> "$time · Sent" to colors.textTertiary
                else -> time to colors.textTertiary
            }
            Text(label, style = DlTheme.type.caption, color = tint, modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp))
        }
    }
}

@Composable
private fun Notice(text: String) {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.bubbleOther)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Shield, null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = DlTheme.type.caption, color = colors.textSecondary, textAlign = TextAlign.Start)
        }
    }
}

private val today = SimpleDateFormat("H:mm", Locale.getDefault())
private val older = SimpleDateFormat("d MMM, H:mm", Locale.getDefault())

fun timeLabel(epochMillis: Long): String {
    val oneDay = 24 * 60 * 60 * 1000L
    val format = if (System.currentTimeMillis() - epochMillis < oneDay) today else older
    return format.format(Date(epochMillis))
}

/** Short time for the chat list: "9:41", "Yesterday", or "12 Oct". */
fun listTimeLabel(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val oneDay = 24 * 60 * 60 * 1000L
    val age = System.currentTimeMillis() - epochMillis
    return when {
        age < oneDay -> today.format(Date(epochMillis))
        age < 2 * oneDay -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMillis))
    }
}
