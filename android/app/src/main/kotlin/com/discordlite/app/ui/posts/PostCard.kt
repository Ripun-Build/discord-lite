package com.discordlite.app.ui.posts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.PostResponse
import com.discordlite.app.posts.PostLinks
import com.discordlite.app.ui.stories.ageLabel
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.GameTag
import com.discordlite.designsystem.component.LiquidPageIndicator
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One photo of a post. Shows a quiet tile until it loads. */
@Composable
fun PostPhoto(postId: String, position: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val repo = LocalAppContainer.current.posts
    val colors = DlTheme.colors
    val image by produceState<ImageBitmap?>(null, postId, position) { value = runCatching { repo.image(postId, position) }.getOrNull() }
    Box(modifier.background(if (colors.isDark) colors.surfaceRaised else colors.surface)) {
        image?.let { Image(it, null, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun PostCard(post: PostResponse, sheets: PostSheets, onOpenUser: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val container = LocalAppContainer.current
    val repo = container.posts
    val me by container.account.me.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val heartScale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }
    val pager = rememberPagerState { post.media.size }
    // Follows the first photo's shape (so a gameplay screenshot isn't cropped to a portrait), within limits.
    val aspect = remember(post.id) { post.media.firstOrNull()?.let { it.width.toFloat() / it.height }?.coerceIn(0.8f, 1.91f) ?: 1f }

    fun burst() {
        scope.launch {
            heartAlpha.snapTo(1f)
            heartScale.snapTo(0.3f)
            heartScale.animateTo(1f, DlMotion.pop())
        }
        scope.launch {
            delay(520)
            heartAlpha.animateTo(0f, DlMotion.smooth())
        }
    }

    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).pressable(pressedScale = 0.98f) { onOpenUser(post.author.id) }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author.displayName, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(post.author.username, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        post.game?.let {
                            Spacer(Modifier.width(8.dp))
                            GameTag(it)
                        }
                    }
                    Text(ageLabel(post.createdAt), style = DlTheme.type.caption, color = colors.textTertiary)
                }
            }
            if (post.author.id == me?.id) {
                Box(Modifier.size(44.dp).pressable(pressedScale = 0.85f) { sheets.options = post.id }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, "Post options", tint = colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxSize()
                    // Double-tap exists only on media, so only here do we pay the single-tap delay.
                    .pointerInput(post.id) {
                        detectTapGestures(onDoubleTap = {
                            scope.launch { repo.setLiked(post.id, true) }
                            haptics.confirm()
                            burst()
                        })
                    },
            ) { page -> PostPhoto(post.id, page, Modifier.fillMaxSize()) }

            Icon(
                Icons.Rounded.Favorite,
                null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(110.dp)
                    .graphicsLayer {
                        scaleX = heartScale.value
                        scaleY = heartScale.value
                        alpha = heartAlpha.value
                        shadowElevation = 24f
                    },
            )

            if (post.media.size > 1) {
                Text(
                    "${pager.currentPage + 1}/${post.media.size}",
                    style = DlTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                LiquidPageIndicator(
                    count = post.media.size,
                    position = { pager.currentPage + pager.currentPageOffsetFraction },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                )
            }
        }

        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeButton(post.liked) {
                if (!post.liked) haptics.confirm() else haptics.tick()
                scope.launch { repo.setLiked(post.id, !post.liked) }
            }
            ActionIcon(Icons.Rounded.ChatBubbleOutline, "Comments") { sheets.comments = post.id }
            ActionIcon(Icons.AutoMirrored.Rounded.Send, "Share to chat") { sheets.share = post.id }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).pressable(pressedScale = 0.85f) {
                    haptics.tick()
                    scope.launch { repo.setSaved(post.id, !post.saved) }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (post.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (post.saved) "Saved" else "Save", tint = colors.textPrimary, modifier = Modifier.size(26.dp))
            }
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RollingCount(post.likeCount)
            Text(if (post.likeCount == 1) " like" else " likes", style = DlTheme.type.headline, color = colors.textPrimary)
        }
        post.caption?.let { caption ->
            Spacer(Modifier.height(4.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(post.author.username) }
                    append("  ")
                    append(caption)
                },
                style = DlTheme.type.callout,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (post.commentCount) {
                0 -> "Add a comment"
                1 -> "View 1 comment"
                else -> "View all ${post.commentCount} comments"
            },
            style = DlTheme.type.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp).pressable(pressedScale = 0.98f) { sheets.comments = post.id },
        )
    }
}

/** A square tile in a profile grid. */
@Composable
fun PostTile(post: PostResponse, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).pressable(pressedScale = 0.96f, onClick = onClick)) {
        PostPhoto(post.id, 0, Modifier.fillMaxSize())
        if (post.media.size > 1) {
            Icon(Icons.Rounded.Collections, "${post.media.size} photos", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp))
        }
    }
}

/** A post shared into a chat: a card that opens it. If you can't see it, the card says so. */
@Composable
fun PostLinkCard(text: String, modifier: Modifier = Modifier) {
    val postId = remember(text) { PostLinks.idIn(text) } ?: return
    val container = LocalAppContainer.current
    val colors = DlTheme.colors
    val background = if (colors.isDark) colors.surfaceRaised else colors.surface
    val cached by container.posts.posts.collectAsStateWithLifecycle()
    val missing by produceState(false, postId) { value = runCatching { container.posts.load(postId) }.isFailure }
    val post = cached[postId]

    if (post == null) {
        Text(
            if (missing) "This post isn't available" else "Loading post…",
            style = DlTheme.type.footnote,
            color = colors.textSecondary,
            modifier = modifier.width(260.dp).clip(RoundedCornerShape(20.dp)).background(background).padding(16.dp),
        )
        return
    }
    Column(
        modifier
            .width(260.dp)
            .pressable(pressedScale = 0.97f) { container.openChatRequests.value = "post:$postId" }
            .clip(RoundedCornerShape(20.dp))
            .background(background),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(post.author.displayName, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(post.author.username, style = DlTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            post.game?.let {
                Spacer(Modifier.width(6.dp))
                GameTag(it)
            }
        }
        PostPhoto(post.id, 0, Modifier.fillMaxWidth().aspectRatio(1.25f))
        post.caption?.let {
            Text(it, style = DlTheme.type.footnote, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
    }
}

/** Heart that pops when liked; the pop starts from its current scale so rapid toggles stay smooth. */
@Composable
private fun LikeButton(liked: Boolean, onToggle: () -> Unit) {
    val colors = DlTheme.colors
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(44.dp).pressable(pressedScale = 0.85f) {
            onToggle()
            scope.launch {
                scale.snapTo(if (!liked) 0.6f else 0.85f)
                scale.animateTo(1f, DlMotion.pop())
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) Color(0xFFFF3B5C) else colors.textPrimary,
            modifier = Modifier.size(27.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).pressable(pressedScale = 0.85f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = DlTheme.colors.textPrimary, modifier = Modifier.size(25.dp))
    }
}

/** Numbers roll up or down like an odometer when they change. */
@Composable
private fun RollingCount(value: Int) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(DlMotion.snappy()) { if (up) it else -it } + fadeIn(DlMotion.snappy())) togetherWith
                (slideOutVertically(DlMotion.snappy()) { if (up) -it else it } + fadeOut(DlMotion.snappy()))
        },
        label = "count",
    ) { v ->
        Text("%,d".format(v), style = DlTheme.type.headline, color = DlTheme.colors.textPrimary)
    }
}
