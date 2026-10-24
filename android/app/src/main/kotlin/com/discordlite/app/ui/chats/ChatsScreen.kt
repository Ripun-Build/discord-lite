package com.discordlite.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.rounded.PersonAdd
import com.discordlite.app.chat.Conversation
import androidx.compose.foundation.layout.offset
import com.discordlite.app.data.LocalAppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discordlite.app.data.Chat
import com.discordlite.app.data.SampleData
import com.discordlite.app.data.User
import com.discordlite.app.ui.common.entrance
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.Badge
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.LargeTitleScreen
import com.discordlite.designsystem.component.StoryAvatar
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlTheme

@Composable
fun ChatsScreen(onOpenChat: (String) -> Unit, onOpenGroup: (String) -> Unit, onNewChat: () -> Unit, onOpenFriends: () -> Unit) {
    val colors = DlTheme.colors
    val container = LocalAppContainer.current
    val me by container.account.me.collectAsStateWithLifecycle()
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val conversations by container.chat.conversations.collectAsStateWithLifecycle(emptyList())
    LargeTitleScreen(
        title = "Chats",
        actions = {
            Box {
                IconCircleButton(Icons.Rounded.Group, "Friends", onOpenFriends, size = 38.dp)
                if (friends.incoming.isNotEmpty()) {
                    Badge(friends.incoming.size, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                }
            }
            IconCircleButton(Icons.Rounded.Edit, "New message", onNewChat, size = 38.dp)
        },
    ) {
        item(key = "search") { SearchField("Search chats", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        item(key = "stories") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { StoryAvatar(me?.displayName ?: SampleData.me.name, "Your story", seen = true, index = 0, isYou = true, onClick = {}) }
                itemsIndexed(SampleData.stories, key = { _, s -> s.user.id }) { i, story ->
                    StoryAvatar(story.user.name, story.user.name.substringBefore(' '), story.seen, index = i + 1, onClick = {})
                }
            }
        }
        item(key = "divider") { Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(colors.separator)) }
        if (conversations.isEmpty()) {
            item(key = "empty") { EmptyChats(onNewChat) }
        }
        conversations.forEachIndexed { index, conversation ->
            item(key = conversation.peerId) {
                ConversationRow(conversation, Modifier.entrance(index, distance = 14.dp, baseDelayMs = 40L)) {
                    if (conversation.isGroup) onOpenGroup(conversation.peerId) else onOpenChat(conversation.peerId)
                }
            }
        }
    }
}

@Composable
fun SearchField(placeholder: String, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(placeholder, style = DlTheme.type.body, color = colors.textTertiary)
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = DlTheme.colors
    val unread = conversation.unread > 0
    Row(
        modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conversation.isGroup) GroupAvatar(conversation.memberNames.ifEmpty { listOf(conversation.displayName) }) else Avatar(conversation.displayName, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.displayName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(listTimeLabel(conversation.lastAt), style = DlTheme.type.footnote, color = if (unread) colors.accent else colors.textTertiary)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    conversation.lastMessage.ifEmpty { if (conversation.isGroup) "New group — say hi" else "Say hi — messages are end-to-end encrypted" },
                    style = DlTheme.type.subheadline,
                    color = if (unread) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Badge(conversation.unread)
                }
            }
        }
    }
}

@Composable
private fun EmptyChats(onNewChat: () -> Unit) {
    val colors = DlTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = colors.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("No chats yet", style = DlTheme.type.title3, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Message a friend to start. Every chat is end-to-end encrypted — only you two can read it.",
            style = DlTheme.type.subheadline,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .pressable(pressedScale = 0.95f, onClick = onNewChat)
                .background(Brush.horizontalGradient(colors.accentGradient), androidx.compose.foundation.shape.CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text("New message", style = DlTheme.type.headline, color = Color.White) }
    }
}

/** Sheet content: pick a friend to message (or find more friends). */
@Composable
fun NewChatSheetContent(onOpenChat: (String) -> Unit, onFindFriends: () -> Unit, onNewGroup: () -> Unit) {
    val colors = DlTheme.colors
    val friends by LocalAppContainer.current.social.friends.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text("New message", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        SheetAction(Icons.Rounded.PersonAdd, "Find friends", "Search by @username", onFindFriends)
        SheetAction(Icons.Rounded.Group, "New group", "Up to 25 friends, end-to-end encrypted", onNewGroup)
        Spacer(Modifier.height(8.dp))
        Text("FRIENDS", style = DlTheme.type.micro, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (friends.friends.isEmpty()) {
            Text(
                "Add a friend first — you can message anyone on your friends list.",
                style = DlTheme.type.subheadline,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        friends.friends.forEach { friend ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f)) { onOpenChat(friend.id) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(friend.displayName, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.displayName, style = DlTheme.type.headline, color = colors.textPrimary)
                    Text("@${friend.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
                }
                Icon(Icons.Rounded.Lock, "End-to-end encrypted", tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = DlTheme.type.headline, color = colors.textPrimary)
            Text(subtitle, style = DlTheme.type.footnote, color = colors.textSecondary)
        }
    }
}
