package com.discordlite.app.ui.friends

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.PublicUser
import com.discordlite.app.data.remote.Relationship
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics

/**
 * Find people by @username, answer requests, and manage friends. Rows glide between sections
 * (Requests → Friends) when their state changes, instead of popping.
 */
@Composable
fun FriendsScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: FriendsViewModel = viewModel { FriendsViewModel(container.social) }
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    var selected by remember { mutableStateOf<PublicUser?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    LaunchedEffect(Unit) { vm.refresh() }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Friends", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
            SpringSheet(visible = selected != null, onDismiss = { selected = null }) {
                selected?.let { user ->
                    FriendOptions(
                        user = user,
                        onMessage = {
                            selected = null
                            onMessage(user.id)
                        },
                        onUnfriend = {
                            vm.unfriend(user.id)
                            selected = null
                        },
                        onBlock = {
                            haptics.confirm()
                            vm.block(user.id)
                            selected = null
                        },
                    )
                }
            }
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = bottom)) {
            item(key = "search") {
                DlTextField(
                    value = vm.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = "Search by @username",
                    leadingIcon = Icons.Rounded.Search,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "error") { ErrorBanner(vm.error, vm.errorTick, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }

            if (vm.query.trim().removePrefix("@").length >= 2) {
                header("Results", if (vm.searching) null else vm.results.size)
                if (!vm.searching && vm.results.isEmpty()) {
                    item(key = "no-results") { EmptyNote("No one found for \"${vm.query.trim()}\"") }
                }
                items(vm.results, key = { "r-${it.id}" }) { user ->
                    UserRow(user, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold))) {
                        RelationshipAction(user.relationship, busy = user.id in vm.pending, onClick = {
                            haptics.tick()
                            vm.act(user)
                        })
                    }
                }
            } else {
                if (friends.incoming.isNotEmpty()) {
                    header("Requests", friends.incoming.size)
                    items(friends.incoming, key = { "in-${it.id}" }) { user ->
                        UserRow(user, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold))) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircleAction(Icons.Rounded.Close, "Decline", tint = colors.textSecondary, busy = false) { vm.decline(user.id) }
                                CircleAction(Icons.Rounded.Check, "Accept", tint = Color.White, filled = colors.success, busy = user.id in vm.pending) {
                                    haptics.confirm()
                                    vm.accept(user.id)
                                }
                            }
                        }
                    }
                }
                if (friends.outgoing.isNotEmpty()) {
                    header("Sent", friends.outgoing.size)
                    items(friends.outgoing, key = { "out-${it.id}" }) { user ->
                        UserRow(user, Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold))) {
                            RelationshipAction(Relationship.OUTGOING, busy = user.id in vm.pending) { vm.cancel(user.id) }
                        }
                    }
                }
                header("Friends", friends.friends.size)
                if (friends.friends.isEmpty()) {
                    item(key = "no-friends") { EmptyNote("No friends yet. Search for someone's @username to add them.") }
                }
                items(friends.friends, key = { "f-${it.id}" }) { user ->
                    UserRow(
                        user,
                        Modifier.animateItem(placementSpec = DlMotion.smooth(IntOffset.VisibilityThreshold)),
                        onClick = { selected = user },
                    ) {
                        Text("Friends", style = DlTheme.type.footnote, color = colors.textTertiary)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.header(title: String, count: Int?) {
    item(key = "h-$title") {
        Text(
            if (count != null && count > 0) "${title.uppercase()} · $count" else title.uppercase(),
            style = DlTheme.type.micro,
            color = DlTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = DlTheme.type.subheadline,
        color = DlTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun UserRow(
    user: PublicUser,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = DlTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.displayName, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${user.username}", style = DlTheme.type.footnote, color = colors.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** One pill that morphs between Add → Requested → Friends; the width change springs. */
@Composable
private fun RelationshipAction(relationship: Relationship, busy: Boolean, onClick: () -> Unit) {
    val colors = DlTheme.colors
    val (label, filled) = when (relationship) {
        Relationship.NONE -> "Add" to true
        Relationship.OUTGOING -> "Requested" to false
        Relationship.INCOMING -> "Accept" to true
        Relationship.FRIEND -> "Friends" to false
    }
    Box(
        Modifier
            .height(34.dp)
            .pressable(enabled = !busy && relationship != Relationship.FRIEND, pressedScale = 0.92f, onClick = onClick)
            .background(
                if (filled) Brush.horizontalGradient(colors.accentGradient) else Brush.linearGradient(listOf(colors.bubbleOther, colors.bubbleOther)),
                CircleShape,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = if (busy) "…" else label,
            transitionSpec = {
                (scaleIn(DlMotion.pop(), initialScale = 0.6f) + fadeIn(DlMotion.snappy())) togetherWith
                    fadeOut(DlMotion.snappy()) using SizeTransform(clip = false) { _, _ -> DlMotion.snappy() }
            },
            label = "relationship",
        ) { text ->
            if (text == "…") {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = if (filled) Color.White else colors.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (relationship == Relationship.NONE) {
                        Icon(Icons.Rounded.PersonAdd, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(text, style = DlTheme.type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (filled) Color.White else colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    busy: Boolean,
    filled: Color = DlTheme.colors.bubbleOther,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .pressable(enabled = !busy, pressedScale = 0.88f, onClick = onClick)
            .background(filled, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(icon, label, tint = tint, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun FriendOptions(user: PublicUser, onMessage: () -> Unit, onUnfriend: () -> Unit, onBlock: () -> Unit) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(user.displayName, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(user.displayName, style = DlTheme.type.title3, color = colors.textPrimary)
                Text("@${user.username}", style = DlTheme.type.footnote, color = colors.textSecondary)
            }
        }
        if (user.bio.isNotBlank()) {
            Text(user.bio, style = DlTheme.type.callout, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(8.dp))
        OptionRow(Icons.Rounded.ChatBubble, "Message", colors.accent, onMessage)
        OptionRow(Icons.Rounded.PersonRemove, "Remove friend", colors.textPrimary, onUnfriend)
        OptionRow(Icons.Rounded.Block, "Block @${user.username}", colors.danger, onBlock)
        Text(
            "Blocked people can't find you, message you or send you requests. They aren't told.",
            style = DlTheme.type.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
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
