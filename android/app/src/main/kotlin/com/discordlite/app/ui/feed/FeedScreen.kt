package com.discordlite.app.ui.feed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.discordlite.app.data.Post
import com.discordlite.app.data.SampleData
import com.discordlite.app.ui.common.ArtPlaceholder
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.GameTag
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.LargeTitleScreen
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FeedScreen() {
    LargeTitleScreen(
        title = "Feed",
        actions = { IconCircleButton(Icons.Rounded.Add, "New post", {}, size = 38.dp) },
    ) {
        SampleData.posts.forEach { post -> item(key = post.id) { PostCard(post) } }
    }
}

@Composable
private fun PostCard(post: Post) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var liked by rememberSaveable(post.id) { mutableStateOf(false) }
    var saved by rememberSaveable(post.id) { mutableStateOf(false) }
    val heartScale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }
    val pager = rememberPagerState { post.slides }

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

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(post.author.name, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author.username, style = DlTheme.type.headline, color = colors.textPrimary)
                    Spacer(Modifier.width(8.dp))
                    GameTag(post.game)
                }
                Text(post.time, style = DlTheme.type.caption, color = colors.textTertiary)
            }
            Icon(Icons.Rounded.MoreHoriz, "More", tint = colors.textSecondary)
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxSize()
                    // Double-tap exists only on media, so only here do we pay the single-tap delay.
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (!liked) liked = true
                            haptics.confirm()
                            burst()
                        })
                    },
            ) { page -> ArtPlaceholder("${post.id}-$page", Modifier.fillMaxSize()) }

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

            if (post.slides > 1) {
                Text(
                    "${pager.currentPage + 1}/${post.slides}",
                    style = DlTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(post.slides) { i ->
                        val w by animateDpAsState(if (pager.currentPage == i) 18.dp else 6.dp, DlMotion.snappy(), label = "dot")
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(w)
                                .background(Color.White.copy(alpha = if (pager.currentPage == i) 0.95f else 0.5f), CircleShape),
                        )
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeButton(liked) {
                liked = !liked
                if (liked) haptics.confirm() else haptics.tick()
            }
            ActionIcon(Icons.Rounded.ChatBubbleOutline, "Comments")
            ActionIcon(Icons.AutoMirrored.Rounded.Send, "Share to chat")
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).pressable(pressedScale = 0.85f) { saved = !saved; haptics.tick() }, contentAlignment = Alignment.Center) {
                Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Save", tint = colors.textPrimary, modifier = Modifier.size(26.dp))
            }
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RollingCount(post.likes + if (liked) 1 else 0)
            Text(" likes", style = DlTheme.type.headline, color = colors.textPrimary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(post.author.username) }
                append("  ")
                append(post.caption)
            },
            style = DlTheme.type.callout,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text("View all ${post.comments} comments", style = DlTheme.type.footnote, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 16.dp))
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
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Box(Modifier.size(44.dp).pressable(pressedScale = 0.85f) {}, contentAlignment = Alignment.Center) {
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
