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
fun ChatsScreen(onOpenChat: (String) -> Unit, onNewChat: () -> Unit) {
    val colors = DlTheme.colors
    LargeTitleScreen(
        title = "Chats",
        actions = { IconCircleButton(Icons.Rounded.Edit, "New message", onNewChat, size = 38.dp) },
    ) {
        item(key = "search") { SearchField("Search chats", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        item(key = "stories") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { StoryAvatar(SampleData.me.name, "Your story", seen = true, index = 0, isYou = true, onClick = {}) }
                itemsIndexed(SampleData.stories, key = { _, s -> s.user.id }) { i, story ->
                    StoryAvatar(story.user.name, story.user.name.substringBefore(' '), story.seen, index = i + 1, onClick = {})
                }
            }
        }
        item(key = "divider") { Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(colors.separator)) }
        SampleData.chats.forEachIndexed { index, chat ->
            item(key = chat.id) { ChatRow(chat, Modifier.entrance(index, distance = 14.dp, baseDelayMs = 40L)) { onOpenChat(chat.id) } }
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
private fun ChatRow(chat: Chat, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.isGroup) GroupAvatar(chat.members) else Avatar(chat.title, 56.dp, online = chat.online)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.title, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(chat.time, style = DlTheme.type.footnote, color = if (chat.unread > 0) colors.accent else colors.textTertiary)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastMessage,
                    style = DlTheme.type.subheadline,
                    color = if (chat.unread > 0) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(chat.unread)
                }
            }
        }
    }
}

/** Two overlapping avatars for group chats. */
@Composable
fun GroupAvatar(members: List<User>, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val colors = DlTheme.colors
    Box(Modifier.size(size)) {
        Avatar(members[0].name, size * 0.68f, Modifier.align(Alignment.TopEnd))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(size * 0.68f + 3.dp)
                .background(colors.background, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(members.getOrElse(1) { members[0] }.name, size * 0.68f)
        }
    }
}

/** Sheet content: start a DM, a group or join a server. */
@Composable
fun NewChatSheetContent(onOpenChat: (String) -> Unit) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("New message", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        SearchField("Search friends by name or @username", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        SheetAction(Icons.Rounded.Group, "New group", "Up to 25 friends, end-to-end encrypted")
        SheetAction(Icons.Rounded.Tag, "Join a server", "Paste an invite link")
        Spacer(Modifier.height(8.dp))
        Text("FRIENDS", style = DlTheme.type.micro, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SampleData.friends.take(5).forEach { friend ->
            val chat = SampleData.chats.firstOrNull { !it.isGroup && it.members.first().id == friend.id }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f)) { chat?.let { onOpenChat(it.id) } }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(friend.name, 44.dp, online = friend.online)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.name, style = DlTheme.type.headline, color = colors.textPrimary)
                    Text("@${friend.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
                }
                Icon(Icons.Rounded.Lock, "End-to-end encrypted", tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f)) {}
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
