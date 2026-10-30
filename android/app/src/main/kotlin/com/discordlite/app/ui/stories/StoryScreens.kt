package com.discordlite.app.ui.stories

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.StoryResponse
import com.discordlite.app.data.remote.StoryViewerResponse
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.SecondaryButton
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Each photo plays for this long. */
private const val STORY_MS = 5_000

/** One person's stories in the viewer. [name] is shown ("Your story" for yours); [avatarName] draws the avatar. */
private data class StoryGroup(val userId: String, val name: String, val stories: List<StoryResponse>, val avatarName: String = name)

/** "now", "12m", "3h", "2d". */
internal fun ageLabel(createdAt: Long): String {
    val minutes = (System.currentTimeMillis() - createdAt) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 48 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

// --- Viewer ---

/**
 * Full-screen stories, Instagram-style: progress bars across the top; tap the right side for the
 * next photo, the left for the previous; hold to pause; swipe sideways between people; swipe down
 * to close (the photo follows your finger and springs back if you let go early).
 *
 * [archiveStoryId] set = your archive (only your own stories, starting at that one).
 */
@Composable
fun StoryViewerScreen(startUserId: String?, archiveStoryId: String?, onBack: () -> Unit, onAddStory: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.stories
    val me by container.account.me.collectAsStateWithLifecycle()
    // A snapshot: the order shouldn't shuffle while you're watching.
    var groups by remember { mutableStateOf<List<StoryGroup>?>(null) }
    var startStory by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (archiveStoryId != null) {
            val mine = runCatching { repo.archive() }.getOrNull().orEmpty().sortedBy { it.createdAt }
            startStory = mine.indexOfFirst { it.id == archiveStoryId }.coerceAtLeast(0)
            groups = listOf(StoryGroup(me?.id.orEmpty(), "Your story", mine, avatarName = me?.displayName ?: "You"))
        } else {
            // Fresh counts ("Seen by") and any stories posted since the list was loaded.
            runCatching { repo.refresh() }
            groups = repo.feed.value.map {
                val mine = it.userId == me?.id
                StoryGroup(it.userId, if (mine) "Your story" else it.displayName, it.stories, avatarName = it.displayName)
            }
        }
    }
    val list = groups
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (list == null) return@Box
        if (list.isEmpty() || list.all { it.stories.isEmpty() }) {
            LaunchedEffect(Unit) { onBack() }
            return@Box
        }
        val startPage = list.indexOfFirst { it.userId == startUserId }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = startPage) { list.size }
        val scope = rememberCoroutineScope()

        // Swipe down to close.
        val density = LocalDensity.current
        val dragY = remember { Animatable(0f) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heightPx = with(density) { maxHeight.toPx() }
            val drag = rememberDraggableState { delta -> scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) } }
            Box(
                Modifier
                    .fillMaxSize()
                    .draggable(
                        drag,
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dragY.value + DlMotion.project(velocity) > heightPx * 0.25f) onBack()
                            else dragY.animateTo(0f, DlMotion.spring(0.8f, 0.35f), initialVelocity = velocity)
                        },
                    )
                    .graphicsLayer {
                        val progress = (dragY.value / heightPx).coerceIn(0f, 1f)
                        translationY = dragY.value
                        scaleX = 1f - progress * 0.15f
                        scaleY = 1f - progress * 0.15f
                        alpha = 1f - progress * 0.4f
                    },
            ) {
                HorizontalPager(state = pager, beyondViewportPageCount = 1) { page ->
                    val group = list[page]
                    StoryGroupPage(
                        group = group,
                        isMine = group.userId == me?.id,
                        active = pager.currentPage == page && !pager.isScrollInProgress && dragY.value == 0f,
                        startIndex = if (page == startPage) startStory.takeIf { archiveStoryId != null }
                            ?: group.stories.indexOfFirst { !it.seen }.takeIf { it >= 0 && group.userId != me?.id } ?: 0 else 0,
                        archive = archiveStoryId != null,
                        onNextGroup = {
                            if (page < list.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else onBack()
                        },
                        onPreviousGroup = { if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } },
                        onClose = onBack,
                        onAddStory = onAddStory,
                        onDeleted = { remaining -> if (remaining == 0) onBack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryGroupPage(
    group: StoryGroup,
    isMine: Boolean,
    active: Boolean,
    startIndex: Int,
    archive: Boolean,
    onNextGroup: () -> Unit,
    onPreviousGroup: () -> Unit,
    onClose: () -> Unit,
    onAddStory: () -> Unit,
    onDeleted: (remaining: Int) -> Unit,
) {
    val container = LocalAppContainer.current
    val repo = container.stories
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var stories by remember(group.userId) { mutableStateOf(group.stories) }
    var index by rememberSaveable(group.userId) { mutableIntStateOf(startIndex.coerceIn(0, (group.stories.size - 1).coerceAtLeast(0))) }
    var holding by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reply by rememberSaveable(group.userId) { mutableStateOf("") }
    var sentNote by remember { mutableStateOf<String?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (stories.isEmpty()) return
    val story = stories[index.coerceIn(0, stories.lastIndex)]
    val progress = remember(story.id) { Animatable(0f) }
    val image by produceState<ImageBitmap?>(null, story.id) { value = runCatching { repo.image(story.id) }.getOrNull() }
    // Not resumed = something covers us (the photo picker) or the app is in the background.
    val onScreen by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val paused = holding || typing || showViewers || confirmDelete || !active || !onScreen.isAtLeast(Lifecycle.State.RESUMED)

    fun next() {
        if (index < stories.lastIndex) index++ else onNextGroup()
    }
    fun previous() {
        if (index > 0) index-- else onPreviousGroup()
    }

    // Play: count it as seen, run the bar, move on when it fills. Pausing keeps the bar where it is.
    LaunchedEffect(story.id, paused, image != null) {
        if (paused || image == null) return@LaunchedEffect
        if (!isMine) scope.launch { repo.markSeen(story) }
        val remaining = ((1f - progress.value) * STORY_MS).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        next()
    }
    LaunchedEffect(sentNote) {
        if (sentNote != null) {
            delay(1500)
            sentNote = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(story.id) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    },
                    onTap = { offset ->
                        if (typing) {
                            focus.clearFocus()
                        } else if (offset.x < size.width / 3f) {
                            previous()
                        } else {
                            next()
                        }
                    },
                )
            },
    ) {
        // The photo, fitted to the screen.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
            } else {
                Image(bitmap, contentDescription = story.caption ?: "Story photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        // Shade so the bars and buttons stay readable on bright photos.
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stories.forEachIndexed { i, _ ->
                    val fill = when {
                        i < index -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(fill).height(2.5.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(group.avatarName, 34.dp)
                Spacer(Modifier.width(8.dp))
                Text(group.name, style = DlTheme.type.headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
                Spacer(Modifier.width(8.dp))
                Text(ageLabel(story.createdAt), style = DlTheme.type.footnote, color = Color.White.copy(alpha = 0.75f))
                if (archive && System.currentTimeMillis() > story.expiresAt) {
                    Spacer(Modifier.width(8.dp))
                    Text("Only you", style = DlTheme.type.caption, color = Color.White, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).pressable(pressedScale = 0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
            story.caption?.let { caption ->
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        caption,
                        style = DlTheme.type.body,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            AnimatedVisibility(sentNote != null, enter = fadeIn(DlMotion.snappy()), exit = fadeOut(DlMotion.snappy())) {
                Text(sentNote.orEmpty(), style = DlTheme.type.footnote, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            if (isMine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StoryButton(Icons.Rounded.Visibility, "Seen by ${story.viewCount ?: 0}") { showViewers = true }
                    Spacer(Modifier.weight(1f))
                    if (!archive) StoryIconButton(Icons.Rounded.Add, "Add to your story", onAddStory)
                    Spacer(Modifier.width(10.dp))
                    StoryIconButton(Icons.Rounded.Delete, "Delete") { confirmDelete = true }
                }
            } else {
                // Replies are sent as an end-to-end encrypted DM.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (reply.isEmpty()) Text("Reply to ${group.name.substringBefore(' ')}…", style = DlTheme.type.body, color = Color.White.copy(alpha = 0.7f))
                        BasicTextField(
                            value = reply,
                            onValueChange = { reply = it.take(1000) },
                            textStyle = DlTheme.type.body.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val text = reply.trim()
                    StoryIconButton(if (text.isEmpty()) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.Send, if (text.isEmpty()) "Send a heart" else "Send reply") {
                        val message = text.ifEmpty { "❤️" }
                        haptics.confirm()
                        reply = ""
                        focus.clearFocus()
                        sentNote = "Sent to ${group.name.substringBefore(' ')}"
                        scope.launch { container.chat.send(group.userId, message, storyQuote = story.caption ?: "Photo") }
                    }
                }
            }
        }

        SpringSheet(visible = showViewers, onDismiss = { showViewers = false }) {
            ViewersSheet(story.id)
        }
        SpringSheet(visible = confirmDelete, onDismiss = { confirmDelete = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Delete this story?", style = DlTheme.type.title3, color = colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text("It's removed for everyone, and from your archive.", style = DlTheme.type.subheadline, color = colors.textSecondary)
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .pressable(pressedScale = 0.97f) {
                            confirmDelete = false
                            val gone = story
                            scope.launch {
                                runCatching { repo.delete(gone.id) }.onSuccess {
                                    haptics.confirm()
                                    val left = stories.filterNot { it.id == gone.id }
                                    stories = left
                                    index = index.coerceAtMost((left.size - 1).coerceAtLeast(0))
                                    onDeleted(left.size)
                                }
                            }
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.danger),
                    contentAlignment = Alignment.Center,
                ) { Text("Delete", style = DlTheme.type.headline, color = Color.White) }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Cancel", { confirmDelete = false }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StoryButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).pressable(pressedScale = 0.94f, onClick = onClick).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = DlTheme.type.footnote, color = Color.White)
    }
}

@Composable
private fun StoryIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).pressable(pressedScale = 0.88f, onClick = onClick).background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** "Seen by" — friends who opened your story, most recent first. */
@Composable
private fun ViewersSheet(storyId: String) {
    val colors = DlTheme.colors
    val repo = LocalAppContainer.current.stories
    val viewers by produceState<List<StoryViewerResponse>?>(null, storyId) { value = runCatching { repo.viewers(storyId) }.getOrNull().orEmpty() }
    Column(Modifier.fillMaxWidth()) {
        Text("Seen by ${viewers?.size ?: 0}", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            when {
                viewers == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                }
                viewers!!.isEmpty() -> Text("Nobody yet.", style = DlTheme.type.subheadline, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                else -> viewers!!.forEach { viewer ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(viewer.displayName, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(viewer.displayName, style = DlTheme.type.body, color = colors.textPrimary)
                            Text("@${viewer.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
                        }
                        Text(ageLabel(viewer.viewedAt), style = DlTheme.type.footnote, color = colors.textTertiary)
                    }
                }
            }
        }
        Text(
            "Only you see this list.",
            style = DlTheme.type.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

// --- Adding a story ---

/** Sheet content: pick a photo or take one. */
@Composable
fun AddStorySheetContent(onPick: () -> Unit, onCamera: () -> Unit) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("Add to your story", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(4.dp))
        Text("Your friends can see it for 24 hours. After that it's in your archive, just for you.", style = DlTheme.type.subheadline, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        SheetRow(Icons.Rounded.PhotoLibrary, "Choose a photo", onPick)
        SheetRow(Icons.Rounded.PhotoCamera, "Take a photo", onCamera)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(colors.storyGradient)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = DlTheme.type.headline, color = colors.textPrimary)
    }
}

/** The picked photo, full screen, with a caption and "Share to your story". */
@Composable
fun StoryComposerScreen(uri: String, onBack: () -> Unit, onPosted: () -> Unit) {
    val context = LocalContext.current
    val repo = LocalAppContainer.current.stories
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val preview by produceState<ImageBitmap?>(null, uri) {
        value = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1600) decoder.setTargetSize(info.size.width * 1600 / longest, info.size.height * 1600 / longest)
            }.asImageBitmap()
        }.getOrNull()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val bitmap = preview
        if (bitmap != null) Image(bitmap, "Your photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.5.dp)

        Row(Modifier.statusBarsPadding().padding(10.dp)) {
            StoryIconButton(Icons.Rounded.Close, "Discard", onBack)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
            ErrorBanner(error, errorTick, Modifier.padding(bottom = 8.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (caption.isEmpty()) Text("Add a caption…", style = DlTheme.type.body, color = Color.White.copy(alpha = 0.7f))
                BasicTextField(
                    value = caption,
                    onValueChange = { caption = it.take(200) },
                    textStyle = DlTheme.type.body.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Friends · 24 h", style = DlTheme.type.footnote, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .height(46.dp)
                        .pressable(enabled = !busy && preview != null, pressedScale = 0.95f) {
                            busy = true
                            scope.launch {
                                runCatching { repo.post(Uri.parse(uri), caption) }
                                    .onSuccess {
                                        haptics.confirm()
                                        onPosted()
                                    }
                                    .onFailure {
                                        haptics.reject()
                                        error = (it as? com.discordlite.app.data.remote.ApiError)?.code.let { code ->
                                            when (code) {
                                                "network" -> "No connection. Try again."
                                                "too_many_stories" -> "That's a lot of stories today. Try again later."
                                                else -> "Couldn't share that photo. Try another."
                                            }
                                        }
                                        errorTick++
                                    }
                                busy = false
                            }
                        }
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(colors.storyGradient))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Share to your story", style = DlTheme.type.headline, color = Color.White)
                }
            }
        }
    }
}

// --- Archive ---

/** Every story you've posted, newest first. Only you can see this. */
@Composable
fun StoryArchiveScreen(onBack: () -> Unit, onOpen: (storyId: String) -> Unit) {
    val colors = DlTheme.colors
    val repo = LocalAppContainer.current.stories
    val stories by produceState<List<StoryResponse>?>(null) { value = runCatching { repo.archive() }.getOrNull().orEmpty() }
    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Story archive", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        val list = stories
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.5.dp)
            }
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No stories yet", style = DlTheme.type.title3, color = colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text("Stories you share stay here after 24 hours. Only you can see them.", style = DlTheme.type.subheadline, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(top = top, bottom = 40.dp, start = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { story -> ArchiveTile(story) { onOpen(story.id) } }
            }
        }
    }
}

@Composable
private fun ArchiveTile(story: StoryResponse, onClick: () -> Unit) {
    val colors = DlTheme.colors
    val repo = LocalAppContainer.current.stories
    val image by produceState<ImageBitmap?>(null, story.id) { value = runCatching { repo.image(story.id) }.getOrNull() }
    val live = System.currentTimeMillis() < story.expiresAt
    Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(6.dp)).background(colors.surface).pressable(pressedScale = 0.96f, onClick = onClick)) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(story.createdAt)),
            style = DlTheme.type.caption,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (live) {
            Text(
                "Live",
                style = DlTheme.type.caption,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape).background(Brush.horizontalGradient(colors.storyGradient)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
