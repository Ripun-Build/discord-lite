package com.discordlite.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.FollowState
import com.discordlite.app.data.remote.Relationship
import com.discordlite.app.data.remote.UserProfileResponse
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.app.ui.posts.PostTile
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.SecondaryButton
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** The banner, avatar, name, bio and numbers: the same on your profile and everyone else's. */
@Composable
internal fun ProfileHeader(
    displayName: String,
    username: String,
    bio: String,
    bioPlaceholder: String?,
    badge: String?,
    postCount: Int?,
    followerCount: Int?,
    followingCount: Int?,
    onOpenFollowers: () -> Unit,
    onOpenFollowing: () -> Unit,
    online: Boolean = false,
    topStart: @Composable RowScope.() -> Unit = {},
    topEnd: @Composable RowScope.() -> Unit,
) {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxWidth().height(190.dp)) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(colors.accentGradient)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to colors.background)))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            topStart()
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = topEnd)
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp)
                .offset(y = 44.dp)
                // A background (not a border) so the ring sits behind the presence dot.
                .background(colors.background, CircleShape)
                .padding(4.dp),
        ) { Avatar(displayName, 92.dp, online = online) }
    }
    Spacer(Modifier.height(52.dp))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(displayName, style = DlTheme.type.title1, color = colors.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@$username", style = DlTheme.type.callout, color = colors.textSecondary)
            badge?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = DlTheme.type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.background(colors.textTertiary.copy(alpha = 0.18f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (bio.isNotBlank() || bioPlaceholder != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                bio.ifBlank { bioPlaceholder.orEmpty() },
                style = DlTheme.type.callout,
                color = if (bio.isBlank()) colors.textTertiary else colors.textPrimary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            ProfileStat(postCount, if (postCount == 1) "Post" else "Posts", Modifier.weight(1f))
            ProfileStat(followerCount, if (followerCount == 1) "Follower" else "Followers", Modifier.weight(1f), onOpenFollowers)
            ProfileStat(followingCount, "Following", Modifier.weight(1f), onOpenFollowing)
        }
    }
}

@Composable
private fun ProfileStat(value: Int?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(modifier.pressable(pressedScale = 0.95f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.let(::shortCount) ?: "–", style = DlTheme.type.title3, color = DlTheme.colors.textPrimary)
        Text(label, style = DlTheme.type.footnote, color = DlTheme.colors.textSecondary)
    }
}

/** 950, 1.2K, 3.4M. */
private fun shortCount(value: Int): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> "%.1fK".format(value / 1_000f).replace(".0K", "K")
    else -> "%.1fM".format(value / 1_000_000f).replace(".0M", "M")
}

/** Someone else's profile. Private accounts hide their posts until you're approved. */
@Composable
fun UserProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenFollowList: (userId: String, following: Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val repo = container.posts
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val me by container.account.me.collectAsStateWithLifecycle()
    val cached by repo.posts.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf<UserProfileResponse?>(null) }
    var postIds by remember { mutableStateOf(emptyList<String>()) }
    var missing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ProfileConfirm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val isMe = me?.id == userId

    suspend fun reload() {
        runCatching { repo.profile(userId) }
            .onSuccess { loaded ->
                profile = loaded
                postIds = if (loaded.canSeePosts) runCatching { repo.byUser(userId) }.getOrDefault(emptyList()) else emptyList()
            }
            .onFailure { missing = true }
    }

    /** Runs a change, then shows the profile as it now stands. */
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        haptics.tick()
        scope.launch {
            runCatching { action() }.onFailure {
                haptics.reject()
                error = "That didn't work. Try again."
                errorTick++
            }
            reload()
            repo.refreshFeedSoon()
            busy = false
        }
    }

    LaunchedEffect(userId) { reload() }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        val user = profile
        when {
            user != null -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ProfileHeader(
                            displayName = user.displayName,
                            username = user.username,
                            bio = user.bio,
                            bioPlaceholder = null,
                            badge = when {
                                user.followsYou && !isMe -> "Follows you"
                                else -> null
                            },
                            postCount = user.postCount,
                            followerCount = user.followerCount,
                            followingCount = user.followingCount,
                            onOpenFollowers = { if (user.canSeePosts) onOpenFollowList(userId, false) },
                            onOpenFollowing = { if (user.canSeePosts) onOpenFollowList(userId, true) },
                            topStart = { IconCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 38.dp) },
                            topEnd = { if (!isMe) IconCircleButton(Icons.Rounded.MoreHoriz, "More", { showMenu = true }, size = 38.dp) },
                        )
                        if (!isMe) {
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (user.relationship == Relationship.FRIEND) {
                                    ProfileAction("Friends", filled = false, Modifier.weight(1f)) { showMenu = true }
                                } else {
                                    val (label, filled) = when (user.followState) {
                                        FollowState.NONE -> "Follow" to true
                                        FollowState.REQUESTED -> "Requested" to false
                                        FollowState.FOLLOWING -> "Following" to false
                                    }
                                    ProfileAction(label, filled, Modifier.weight(1f), busy = busy) {
                                        when {
                                            user.followState == FollowState.NONE -> act { repo.follow(userId) }
                                            // Unfollowing a private account can't be undone without asking again.
                                            user.isPrivate && user.followState == FollowState.FOLLOWING -> confirm = ProfileConfirm.Unfollow
                                            else -> act { repo.unfollow(userId) }
                                        }
                                    }
                                    val friendLabel = when (user.relationship) {
                                        Relationship.OUTGOING -> "Requested"
                                        Relationship.INCOMING -> "Accept"
                                        else -> "Add friend"
                                    }
                                    ProfileAction(friendLabel, filled = false, Modifier.weight(1f), busy = busy) {
                                        act {
                                            when (user.relationship) {
                                                Relationship.OUTGOING -> container.social.removeRequest(userId)
                                                Relationship.INCOMING -> container.social.accept(userId)
                                                else -> container.social.sendRequest(userId)
                                            }
                                        }
                                    }
                                }
                                ProfileAction("Message", filled = false, Modifier.weight(1f)) { onMessage(userId) }
                            }
                        }
                        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (user.canSeePosts) {
                    items(postIds, key = { it }) { id ->
                        cached[id]?.let { post -> PostTile(post, onClick = { onOpenPost(id) }) }
                    }
                    if (postIds.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("No posts yet", style = DlTheme.type.callout, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 48.dp))
                        }
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 40.dp, end = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Lock, null, tint = colors.textTertiary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("This account is private", style = DlTheme.type.headline, color = colors.textPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text("Follow them to see their posts.", style = DlTheme.type.callout, color = colors.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            missing -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
                IconCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 38.dp)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("This account isn't available.", style = DlTheme.type.callout, color = colors.textSecondary)
                }
            }
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = colors.textTertiary, strokeWidth = 2.dp)
        }

        SpringSheet(visible = showMenu, onDismiss = { showMenu = false }) {
            profile?.let { user ->
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    if (user.relationship == Relationship.FRIEND) {
                        MenuRow(Icons.Rounded.PersonRemove, "Remove friend", colors.textPrimary) {
                            showMenu = false
                            confirm = ProfileConfirm.Unfriend
                        }
                    } else if (user.followsYou) {
                        MenuRow(Icons.Rounded.PersonRemove, "Remove follower", colors.textPrimary) {
                            showMenu = false
                            act { repo.removeFollower(userId) }
                        }
                    }
                    MenuRow(Icons.Rounded.Block, "Block @${user.username}", colors.danger) {
                        showMenu = false
                        confirm = ProfileConfirm.Block
                    }
                }
            }
        }
        SpringSheet(visible = confirm != null, onDismiss = { confirm = null }) {
            val user = profile
            when (val c = confirm) {
                null -> Unit
                else -> if (user != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(c.title(user.displayName), style = DlTheme.type.title3, color = colors.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(c.detail, style = DlTheme.type.subheadline, color = colors.textSecondary)
                        Spacer(Modifier.height(18.dp))
                        DangerButton(c.action) {
                            confirm = null
                            when (c) {
                                ProfileConfirm.Unfollow -> act { repo.unfollow(userId) }
                                ProfileConfirm.Unfriend -> act { container.social.unfriend(userId) }
                                ProfileConfirm.Block -> scope.launch {
                                    runCatching { container.social.block(userId) }
                                        .onSuccess {
                                            repo.refreshFeedSoon()
                                            onBack()
                                        }
                                        .onFailure {
                                            error = "That didn't work. Try again."
                                            errorTick++
                                        }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Cancel", { confirm = null }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private enum class ProfileConfirm(val action: String, val detail: String) {
    Unfollow("Unfollow", "Their account is private, so you'd have to ask again to see their posts."),
    Unfriend("Remove friend", "You'll stop following each other too."),
    Block("Block", "You won't see each other's profiles, posts or comments, and you'll no longer be friends or follow each other.");

    fun title(name: String) = when (this) {
        Unfollow -> "Unfollow $name?"
        Unfriend -> "Remove $name as a friend?"
        Block -> "Block $name?"
    }
}

@Composable
internal fun ProfileAction(label: String, filled: Boolean, modifier: Modifier = Modifier, busy: Boolean = false, height: Dp = 44.dp, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Box(
        modifier
            .height(height)
            .pressable(enabled = !busy, pressedScale = 0.96f, onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) colors.accent else if (colors.isDark) colors.surfaceRaised else colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DlTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) Color.White else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DangerButton(label: String, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .pressable(enabled = !busy, pressedScale = 0.97f, onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.danger),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, style = DlTheme.type.headline, color = Color.White)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = DlTheme.colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = DlTheme.type.body, color = tint)
    }
}
