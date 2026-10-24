package com.discordlite.app.ui.chats

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.chat.GatewayClient
import com.discordlite.app.chat.GroupInfo
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.PublicUser
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.SecondaryButton
import com.discordlite.designsystem.component.SectionCard
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.TextAction
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Most people a group can hold, you included (the server enforces the same). */
private const val MAX_GROUP = 25

// --- New group ---

/**
 * Sheet content: name the group (optional), pick friends, create. Picked friends gather as
 * avatar chips at the top, like iMessage.
 */
@Composable
fun NewGroupSheetContent(onCancel: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalAppContainer.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            title = "New Group",
            onCancel = onCancel,
            confirm = "Create",
            confirmEnabled = selected.isNotEmpty() && !busy,
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching { container.groups.create(name, selected) }
                        .onSuccess {
                            haptics.confirm()
                            onCreated(it)
                        }
                        .onFailure {
                            haptics.reject()
                            error = groupError(it)
                            errorTick++
                        }
                    busy = false
                }
            },
        )
        DlTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            placeholder = "Group name (optional)",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        PeoplePicker(
            selected = selected,
            onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
            limit = MAX_GROUP - 1,
        )
    }
}

/** "Cancel · Title · Action" row at the top of a sheet. */
@Composable
internal fun SheetHeader(title: String, onCancel: () -> Unit, confirm: String, confirmEnabled: Boolean, onConfirm: () -> Unit) {
    val colors = DlTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction("Cancel", onCancel, Modifier.weight(1f).padding(vertical = 8.dp), color = colors.accent)
        Text(title, style = DlTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextAction(
                confirm,
                { if (confirmEnabled) onConfirm() },
                Modifier.alpha(if (confirmEnabled) 1f else 0.35f).padding(vertical = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Chips for who's picked, a search box, and your friends with check circles. [alreadyIn] are
 * shown as "In group" and can't be picked; at most [limit] can be picked.
 */
@Composable
internal fun PeoplePicker(selected: List<String>, onToggle: (String) -> Unit, limit: Int, alreadyIn: Set<String> = emptySet()) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val friends by LocalAppContainer.current.social.friends.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val byId = friends.friends.associateBy { it.id }
    val shown = friends.friends.filter {
        query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) || it.username.contains(query.trim().removePrefix("@"), ignoreCase = true)
    }

    AnimatedVisibility(
        visible = selected.isNotEmpty(),
        enter = fadeIn(DlMotion.snappy()) + androidx.compose.animation.expandVertically(DlMotion.smooth()),
        exit = fadeOut(DlMotion.snappy()) + androidx.compose.animation.shrinkVertically(DlMotion.smooth()),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(selected, key = { it }) { id ->
                val friend = byId[id] ?: return@items
                Column(
                    Modifier
                        .animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold))
                        .width(56.dp)
                        .pressable(pressedScale = 0.9f) {
                            haptics.selection()
                            onToggle(id)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(friend.displayName, 48.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(friend.displayName.substringBefore(' '), style = DlTheme.type.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    DlTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Search friends",
        leadingIcon = Icons.Rounded.Search,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Text(
        "FRIENDS · ${selected.size} of $limit picked".takeIf { selected.isNotEmpty() } ?: "FRIENDS",
        style = DlTheme.type.micro,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        if (friends.friends.isEmpty()) {
            Text(
                "Add friends first — you can put anyone on your friends list in a group.",
                style = DlTheme.type.subheadline,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        shown.forEach { friend ->
            val inGroup = friend.id in alreadyIn
            val picked = friend.id in selected
            val canPick = !inGroup && (picked || selected.size < limit)
            PickerRow(friend, picked = picked, inGroup = inGroup, enabled = canPick) {
                haptics.selection()
                onToggle(friend.id)
            }
        }
    }
}

@Composable
private fun PickerRow(friend: PublicUser, picked: Boolean, inGroup: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .alpha(if (enabled || picked) 1f else 0.45f)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(friend.displayName, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(friend.displayName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${friend.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        if (inGroup) {
            Text("In group", style = DlTheme.type.footnote, color = colors.textTertiary)
        } else {
            CheckCircle(picked)
        }
    }
}

/** Empty ring, or a filled accent circle whose check pops in on a spring. */
@Composable
internal fun CheckCircle(checked: Boolean) {
    val colors = DlTheme.colors
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).border(1.5.dp, colors.textTertiary.copy(alpha = 0.6f), CircleShape))
        AnimatedVisibility(
            visible = checked,
            enter = scaleIn(DlMotion.pop(), initialScale = 0.3f) + fadeIn(DlMotion.snappy()),
            exit = scaleOut(DlMotion.snappy(), targetScale = 0.3f) + fadeOut(DlMotion.snappy()),
        ) {
            Box(Modifier.size(26.dp).background(Brush.linearGradient(colors.accentGradient), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
    }
}

// --- Group chat ---

/**
 * A group chat. Looks like a DM, with each person's name and avatar on their messages. Tapping the
 * title opens Group Info.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupChatScreen(groupId: String, onBack: () -> Unit, onOpenInfo: () -> Unit) {
    val container = LocalAppContainer.current
    val chat = container.chat
    val groups = container.groups
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val group by remember(groupId) { groups.group(groupId) }.collectAsStateWithLifecycle(null)
    val messages by remember(groupId) { chat.messages(groupId) }.collectAsStateWithLifecycle(emptyList())
    val connection by container.gateway.state.collectAsStateWithLifecycle()
    val typingByGroup by groups.typing.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LifecycleResumeEffect(groupId) {
        scope.launch { groups.open(groupId) }
        onPauseOrDispose { chat.close(groupId) }
    }

    fun nameOf(id: String) = group?.members?.firstOrNull { it.id == id }?.displayName ?: "Former member"
    val typingNames = typingByGroup[groupId].orEmpty().map(::nameOf)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = if (WindowInsets.isImeVisible) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = group?.title ?: "…"
    val active = group?.active != false

    GlassScaffold(
        modifier = Modifier.imePadding(),
        overlay = {
            DetailTopBar(
                onBack = onBack,
                title = {
                    Row(
                        Modifier.weight(1f).pressable(pressedScale = 0.97f, onClick = onOpenInfo),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupAvatar(group.otherNames(me?.id), 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Group info", tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, null, tint = colors.success, modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    when {
                                        typingNames.size == 1 -> "${typingNames[0].substringBefore(' ')} is typing…"
                                        typingNames.size == 2 -> "${typingNames[0].substringBefore(' ')} and ${typingNames[1].substringBefore(' ')} are typing…"
                                        typingNames.size > 2 -> "${typingNames.size} people are typing…"
                                        !active -> "You're no longer in this group"
                                        connection == GatewayClient.State.Connecting -> "Connecting…"
                                        connection == GatewayClient.State.Offline -> "Waiting for network…"
                                        else -> "${group?.members?.size ?: 0} members · End-to-end encrypted"
                                    },
                                    style = DlTheme.type.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
            )
            if (active) {
                Composer(
                    draft = draft,
                    onDraftChange = {
                        draft = it
                        groups.onTyping(groupId, it.isNotBlank())
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
                        scope.launch { groups.send(groupId, text) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colors.surface)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("You're no longer in this group", style = DlTheme.type.subheadline, color = colors.textSecondary)
                }
            }
        },
    ) {
        ChatMessageList(
            key = groupId,
            messages = messages,
            typingName = typingNames.firstOrNull(),
            contentPadding = PaddingValues(top = statusTop + TopBarHeight + 18.dp, bottom = 74.dp + navBottom),
            onRetry = { scope.launch { groups.retry(it) } },
            senderName = ::nameOf,
        )
    }
}

private fun GroupInfo?.otherNames(me: String?): List<String> =
    this?.members?.filter { it.id != me }?.map { it.displayName }?.ifEmpty { null } ?: listOf(this?.title ?: "Group")

/** Two overlapping avatars for a group, from its members' names. */
@Composable
fun GroupAvatar(names: List<String>, size: Dp = 56.dp) {
    val colors = DlTheme.colors
    val first = names.firstOrNull() ?: "Group"
    if (names.size < 2) {
        Avatar(first, size)
        return
    }
    Box(Modifier.size(size)) {
        Avatar(first, size * 0.68f, Modifier.align(Alignment.TopEnd))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(size * 0.68f + 3.dp)
                .background(colors.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(names[1], size * 0.68f)
        }
    }
}

// --- Group info ---

private enum class InfoSheet { AddPeople, Leave }

/**
 * Name, members and actions. Only the owner renames, adds and removes (swipe a member left);
 * anyone can leave.
 */
@Composable
fun GroupInfoScreen(groupId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val container = LocalAppContainer.current
    val groups = container.groups
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val group by remember(groupId) { groups.group(groupId) }.collectAsStateWithLifecycle(null)
    val me by container.account.me.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<InfoSheet?>(null) }
    var lastSheet by remember { mutableStateOf<InfoSheet?>(null) }
    if (sheet != null) lastSheet = sheet
    var picked by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    val g = group
    val isOwner = g != null && g.ownerId == me?.id
    val canManage = isOwner && g?.active == true
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp

    fun act(block: suspend () -> Unit, onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    haptics.confirm()
                    onDone()
                }
                .onFailure {
                    haptics.reject()
                    error = groupError(it)
                    errorTick++
                }
            busy = false
        }
    }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Group Info", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
            SpringSheet(visible = sheet != null, onDismiss = { sheet = null }) {
                when (lastSheet) {
                    InfoSheet.AddPeople -> Column(Modifier.fillMaxWidth()) {
                        SheetHeader(
                            title = "Add People",
                            onCancel = { sheet = null },
                            confirm = "Add",
                            confirmEnabled = picked.isNotEmpty() && !busy,
                            onConfirm = { act({ groups.addMembers(groupId, picked) }) { sheet = null } },
                        )
                        PeoplePicker(
                            selected = picked,
                            onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                            limit = (MAX_GROUP - (g?.members?.size ?: 0)).coerceAtLeast(0),
                            alreadyIn = g?.members?.map { it.id }?.toSet().orEmpty(),
                        )
                    }
                    InfoSheet.Leave -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text("Leave “${g?.title ?: "group"}”?", style = DlTheme.type.title3, color = colors.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isOwner && (g?.members?.size ?: 0) > 1) {
                                "You won't get new messages. The person who's been here longest becomes the owner. Your chat history stays on this phone."
                            } else {
                                "You won't get new messages from this group. Your chat history stays on this phone."
                            },
                            style = DlTheme.type.subheadline,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .pressable(enabled = !busy, pressedScale = 0.97f) {
                                    act({ groups.leave(groupId) }) {
                                        sheet = null
                                        onLeft()
                                    }
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.danger),
                            contentAlignment = Alignment.Center,
                        ) { Text("Leave group", style = DlTheme.type.headline, color = Color.White) }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Cancel", { sheet = null }, Modifier.fillMaxWidth())
                    }
                    null -> Unit
                }
            }
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = 40.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupAvatar(g.otherNames(me?.id), 88.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(g?.title ?: "…", style = DlTheme.type.title2, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = colors.success, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${g?.members?.size ?: 0} members · End-to-end encrypted", style = DlTheme.type.footnote, color = colors.textSecondary)
                    }
                }
            }
            item { ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            item { InfoHeader("Name") }
            item {
                SectionCard {
                    if (editing) {
                        Column(Modifier.padding(12.dp)) {
                            DlTextField(nameDraft, { nameDraft = it.take(100) }, placeholder = "Group name (leave empty to use names)")
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End)) {
                                TextAction("Cancel", { editing = false }, color = colors.textSecondary)
                                TextAction("Save", { act({ groups.rename(groupId, nameDraft) }) { editing = false } })
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g?.name ?: "No name — showing members' names",
                                style = DlTheme.type.body,
                                color = if (g?.name != null) colors.textPrimary else colors.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            if (canManage) {
                                TextAction("Edit", {
                                    nameDraft = g?.name.orEmpty()
                                    editing = true
                                })
                            }
                        }
                    }
                }
            }

            item { InfoHeader("Members · ${g?.members?.size ?: 0}") }
            item {
                SectionCard {
                    Column {
                        g?.members?.forEachIndexed { i, member ->
                            val isMe = member.id == me?.id
                            SwipeToReveal(enabled = canManage && !isMe, action = "Remove", onAction = { act({ groups.removeMember(groupId, member.id) }) }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(member.displayName, 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (isMe) "${member.displayName} (You)" else member.displayName, style = DlTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${member.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
                                    }
                                    if (member.id == g.ownerId) {
                                        Text(
                                            "Owner",
                                            style = DlTheme.type.caption,
                                            color = colors.accent,
                                            modifier = Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            if (i < g.members.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(colors.separator))
                        }
                        if (canManage && (g?.members?.size ?: 0) < MAX_GROUP) {
                            Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(colors.separator))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f)) {
                                        picked = emptyList()
                                        sheet = InfoSheet.AddPeople
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PersonAdd, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("Add people", style = DlTheme.type.body, color = colors.accent)
                            }
                        }
                    }
                }
            }
            item {
                val owner = g?.members?.firstOrNull { it.id == g.ownerId }?.displayName
                InfoFooter(
                    when {
                        g?.active == false -> "You're no longer in this group."
                        canManage -> "Swipe left on someone to remove them. Groups can have up to $MAX_GROUP people."
                        else -> "Only the owner${owner?.let { " ($it)" }.orEmpty()} can rename the group or add and remove people."
                    },
                )
            }

            if (g?.active == true) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    SectionCard {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable(pressedScale = 0.99f, highlight = colors.danger.copy(alpha = 0.08f)) { sheet = InfoSheet.Leave }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = colors.danger, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Leave group", style = DlTheme.type.headline, color = colors.danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InfoHeader(text: String) {
    Text(
        text.uppercase(),
        style = DlTheme.type.micro,
        color = DlTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
internal fun InfoFooter(text: String) {
    Text(text, style = DlTheme.type.footnote, color = DlTheme.colors.textTertiary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
}

/**
 * Swipe the row left to reveal a red [action] button, as in iOS lists. The row follows the finger,
 * and on release the flick's projected end decides open vs closed; a spring carries the velocity.
 */
@Composable
internal fun SwipeToReveal(enabled: Boolean, action: String, onAction: () -> Unit, content: @Composable () -> Unit) {
    val colors = DlTheme.colors
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { 92.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val drag = rememberDraggableState { delta ->
        val next = offset.value + delta
        // Past fully open it rubber-bands instead of stopping dead.
        val shown = if (next < -revealPx) -revealPx - DlMotion.rubberband(-revealPx - next, revealPx) else next.coerceAtMost(0f)
        scope.launch { offset.snapTo(shown) }
    }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (offset.value < 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(92.dp)
                    .background(colors.danger)
                    .pressable(pressedScale = 0.96f) {
                        scope.launch { offset.animateTo(0f, DlMotion.smooth()) }
                        onAction()
                    },
                contentAlignment = Alignment.Center,
            ) { Text(action, style = DlTheme.type.headline, color = Color.White) }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(colors.surface)
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStarted = { offset.stop() },
                    onDragStopped = { velocity ->
                        val open = offset.value + DlMotion.project(velocity) < -revealPx / 2
                        offset.animateTo(if (open) -revealPx else 0f, DlMotion.spring(1f, 0.35f), initialVelocity = velocity)
                    },
                ),
        ) { content() }
    }
}

private fun groupError(error: Throwable): String = when ((error as? ApiError)?.code) {
    "network" -> "No connection. Check your internet and try again."
    "group_add_not_allowed" -> "Someone you picked only accepts messages from some people, so they can't be added."
    "group_full" -> "Groups can have up to $MAX_GROUP people."
    "not_group_owner" -> "Only the group owner can do that."
    "user_not_found" -> "One of those people can't be added."
    "group_not_found" -> "This group no longer exists."
    else -> "Something went wrong. Try again."
}
