package com.discordlite.app.ui.posts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.CommentResponse
import com.discordlite.app.posts.PostLinks
import com.discordlite.app.posts.PostsRepository
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.app.ui.profile.DangerButton
import com.discordlite.app.ui.stories.ageLabel
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.SecondaryButton
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** Which post's comments, share list or options are open. Sheets sit above everything, so the host owns them. */
@Stable
class PostSheets {
    var comments by mutableStateOf<String?>(null)
    var share by mutableStateOf<String?>(null)
    var options by mutableStateOf<String?>(null)
}

@Composable
fun rememberPostSheets() = remember { PostSheets() }

/** Put this at the top of a screen that shows post cards. */
@Composable
fun BoxScope.PostSheetHost(sheets: PostSheets, onOpenUser: (String) -> Unit) {
    // Keep the last post while the sheet animates away.
    var comments by remember { mutableStateOf<String?>(null) }
    var share by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<String?>(null) }
    sheets.comments?.let { comments = it }
    sheets.share?.let { share = it }
    sheets.options?.let { options = it }

    SpringSheet(visible = sheets.comments != null, onDismiss = { sheets.comments = null }, modifier = Modifier.imePadding()) {
        comments?.let { postId ->
            CommentsSheetContent(postId, onOpenUser = {
                sheets.comments = null
                onOpenUser(it)
            })
        }
    }
    SpringSheet(visible = sheets.share != null, onDismiss = { sheets.share = null }) {
        share?.let { ShareSheetContent(it) }
    }
    SpringSheet(visible = sheets.options != null, onDismiss = { sheets.options = null }) {
        options?.let { PostOptionsContent(it, onDone = { sheets.options = null }) }
    }
}

// --- Comments ---

/** Comments, each followed by its replies. */
@Composable
private fun CommentsSheetContent(postId: String, onOpenUser: (String) -> Unit) {
    val repo = LocalAppContainer.current.posts
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val comments = remember(postId) { mutableStateListOf<CommentResponse>() }
    var loading by remember(postId) { mutableStateOf(true) }
    var text by remember(postId) { mutableStateOf("") }
    var replyingTo by remember(postId) { mutableStateOf<CommentResponse?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    fun fail(message: String) {
        haptics.reject()
        error = message
        errorTick++
    }

    LaunchedEffect(postId) {
        runCatching { repo.comments(postId) }
            .onSuccess {
                comments.clear()
                comments.addAll(it)
            }
            .onFailure { fail("Couldn't load comments.") }
        loading = false
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true
        scope.launch {
            runCatching { repo.addComment(postId, body, replyingTo?.id) }
                .onSuccess {
                    comments.add(it)
                    text = ""
                    replyingTo = null
                    haptics.confirm()
                }
                .onFailure {
                    fail(if ((it as? ApiError)?.code == "rate_limited") "You're commenting very quickly. Wait a moment." else "Couldn't post that. Try again.")
                }
            sending = false
        }
    }

    fun delete(comment: CommentResponse) {
        scope.launch {
            val replies = comments.filter { it.parentId == comment.id }
            runCatching { repo.deleteComment(postId, comment.id, removed = 1 + replies.size) }
                .onSuccess {
                    comments.remove(comment)
                    comments.removeAll(replies)
                    if (replyingTo?.id == comment.id) replyingTo = null
                    haptics.tick()
                }
                .onFailure { fail("Couldn't delete that. Try again.") }
        }
    }

    val threads = remember(comments.toList()) {
        val replies = comments.filter { it.parentId != null }.groupBy { it.parentId }
        comments.filter { it.parentId == null }.flatMap { listOf(it) + replies[it.id].orEmpty() }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
        Text("Comments", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                threads.isEmpty() -> Text("No comments yet. Be the first.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(threads, key = { it.id }) { comment ->
                        CommentRow(
                            comment,
                            onOpenUser = { onOpenUser(comment.author.id) },
                            onReply = { replyingTo = comment },
                            onDelete = { delete(comment) },
                        )
                    }
                }
            }
        }
        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        replyingTo?.let { target ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to @${target.author.username}", style = DlTheme.type.footnote, color = colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(32.dp).pressable(pressedScale = 0.85f) { replyingTo = null }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Cancel reply", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (colors.isDark) colors.surfaceRaised else colors.background).padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(if (replyingTo != null) "Write a reply…" else "Add a comment…", style = DlTheme.type.body, color = colors.textTertiary)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(PostsRepository.MAX_COMMENT) },
                    textStyle = DlTheme.type.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .pressable(enabled = text.isNotBlank() && !sending, pressedScale = 0.85f, onClick = ::send)
                    .background(if (text.isBlank()) colors.textTertiary.copy(alpha = 0.3f) else colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Post comment", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun CommentRow(comment: CommentResponse, onOpenUser: () -> Unit, onReply: () -> Unit, onDelete: () -> Unit) {
    val colors = DlTheme.colors
    val isReply = comment.parentId != null
    Row(Modifier.fillMaxWidth().padding(start = if (isReply) 56.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Box(Modifier.pressable(pressedScale = 0.92f, onClick = onOpenUser)) { Avatar(comment.author.displayName, if (isReply) 28.dp else 36.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author.username, style = DlTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).pressable(pressedScale = 0.97f, onClick = onOpenUser))
                Spacer(Modifier.width(6.dp))
                Text(ageLabel(comment.createdAt), style = DlTheme.type.caption, color = colors.textTertiary)
            }
            Text(comment.body, style = DlTheme.type.callout, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Reply", style = DlTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textSecondary, modifier = Modifier.pressable(pressedScale = 0.92f, onClick = onReply))
                if (comment.canDelete) {
                    Text("Delete", style = DlTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textSecondary, modifier = Modifier.pressable(pressedScale = 0.92f, onClick = onDelete))
                }
            }
        }
    }
}

// --- Share to chat ---

/** Your recent chats, then any friends you haven't talked to yet. The message is end-to-end encrypted as usual. */
@Composable
private fun ShareSheetContent(postId: String) {
    val container = LocalAppContainer.current
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val conversations by container.chat.conversations.collectAsStateWithLifecycle(emptyList())
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val sent = remember(postId) { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val targets = remember(conversations, friends) {
        val chats = conversations.map { ShareTarget(it.peerId, it.displayName, it.isGroup) }
        val known = chats.mapTo(HashSet()) { it.id }
        chats + friends.friends.filter { it.id !in known }.map { ShareTarget(it.id, it.displayName, false) }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Share to", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
        Text("Sent end-to-end encrypted", style = DlTheme.type.footnote, color = colors.textSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp))
        if (targets.isEmpty()) {
            Text("Add a friend to share posts with them.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(targets, key = { it.id }) { target ->
                val done = target.id in sent
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(target.name, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(target.name, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (done) "Sent" else "Send",
                        style = DlTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = if (done) colors.textSecondary else Color.White,
                        modifier = Modifier
                            .pressable(enabled = !done, pressedScale = 0.93f) {
                                sent.add(target.id)
                                haptics.confirm()
                                scope.launch {
                                    runCatching {
                                        val link = PostLinks.link(postId)
                                        if (target.isGroup) container.groups.send(target.id, link) else container.chat.send(target.id, link)
                                    }.onFailure {
                                        sent.remove(target.id)
                                        haptics.reject()
                                        error = "Couldn't send to ${target.name}."
                                        errorTick++
                                    }
                                }
                            }
                            .background(if (done) colors.textTertiary.copy(alpha = 0.18f) else colors.accent, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

private data class ShareTarget(val id: String, val name: String, val isGroup: Boolean)

// --- Your own post ---

@Composable
private fun PostOptionsContent(postId: String, onDone: () -> Unit) {
    val repo = LocalAppContainer.current.posts
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Delete this post?", style = DlTheme.type.title3, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Its photos, likes and comments are removed for everyone.", style = DlTheme.type.subheadline, color = colors.textSecondary)
        ErrorBanner(error, errorTick, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        DangerButton("Delete", busy = busy) {
            busy = true
            scope.launch {
                runCatching { repo.delete(postId) }
                    .onSuccess {
                        haptics.confirm()
                        onDone()
                    }
                    .onFailure {
                        haptics.reject()
                        error = "Couldn't delete it. Try again."
                        errorTick++
                    }
                busy = false
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Cancel", onDone, Modifier.fillMaxWidth())
    }
}
