package com.discordlite.app.ui.posts

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.posts.PostsRepository
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.LiquidPageIndicator
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** New post: the photos you picked, a caption and a game tag (pick a suggestion or type any game). */
@Composable
fun PostComposerScreen(uris: List<String>, onBack: () -> Unit, onPosted: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.posts
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val me by container.account.me.collectAsStateWithLifecycle()
    val photos = remember { mutableStateListOf<String>().also { it.addAll(uris) } }
    var caption by rememberSaveable { mutableStateOf("") }
    var game by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val games by produceState(emptyList<String>()) { value = runCatching { repo.games() }.getOrDefault(emptyList()) }
    val suggestions = remember(game, games) {
        val typed = game.trim()
        games.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }.take(8)
    }
    val pager = rememberPagerState { photos.size }
    val scroll = rememberScrollState()
    var gameFocused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    // Typing a game: keep the suggestions above the keyboard, or they can't be seen or tapped.
    LaunchedEffect(gameFocused, keyboard, suggestions.isEmpty()) {
        if (gameFocused) scroll.animateScrollTo(scroll.maxValue)
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("New post", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scroll)
                .padding(top = top)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    photos.getOrNull(page)?.let { LocalPhoto(it, Modifier.fillMaxSize()) }
                }
                if (photos.size > 1) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(34.dp)
                            .pressable(pressedScale = 0.88f) {
                                haptics.tick()
                                photos.getOrNull(pager.currentPage)?.let(photos::remove)
                            }
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Remove this photo", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    LiquidPageIndicator(
                        count = photos.size,
                        position = { pager.currentPage + pager.currentPageOffsetFraction },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            DlTextField(
                value = caption,
                onValueChange = { caption = it.take(PostsRepository.MAX_CAPTION) },
                placeholder = "Say something about it…",
                label = "Caption",
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            DlTextField(
                value = game,
                onValueChange = { game = it.take(PostsRepository.MAX_GAME) },
                placeholder = "Search or type any game",
                label = "Game",
                leadingIcon = Icons.Rounded.SportsEsports,
                modifier = Modifier.onFocusChanged { gameFocused = it.hasFocus },
            )
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { name ->
                        Text(
                            name,
                            style = DlTheme.type.footnote,
                            color = colors.accent,
                            modifier = Modifier
                                .pressable(pressedScale = 0.94f) {
                                    haptics.tick()
                                    game = name
                                }
                                .background(colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.10f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (me?.isPrivate == true) "Only your followers can see this post." else "Anyone on discord-lite can see this post.",
                    style = DlTheme.type.footnote,
                    color = colors.textSecondary,
                )
            }
            ErrorBanner(error, errorTick, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "Share",
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repo.create(photos.map(Uri::parse), caption, game) }
                            .onSuccess {
                                haptics.confirm()
                                onPosted()
                            }
                            .onFailure {
                                haptics.reject()
                                error = when ((it as? ApiError)?.code) {
                                    "network" -> "No connection. Try again."
                                    "too_many_posts" -> "That's 30 posts today. Try again tomorrow."
                                    "invalid_image" -> "One of those photos can't be used."
                                    else -> "Couldn't share that. Try again."
                                }
                                errorTick++
                                busy = false
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = photos.isNotEmpty(),
                loading = busy,
            )
        }
    }
}

/** A photo from the phone, before it's uploaded. */
@Composable
private fun LocalPhoto(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, uri) {
        value = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1200) decoder.setTargetSize(info.size.width * 1200 / longest, info.size.height * 1200 / longest)
            }.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap != null) Image(bitmap, "Your photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
    }
}
