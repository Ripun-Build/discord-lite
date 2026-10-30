package com.discordlite.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.FollowState
import com.discordlite.app.data.remote.FollowUserResponse
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.SegmentedControl
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** A person in a list: tap to open their profile. */
@Composable
internal fun PersonRow(displayName: String, username: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(displayName, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName, style = DlTheme.type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@$username", style = DlTheme.type.footnote, color = colors.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** Followers and Following, on one page with a switch at the top. */
@Composable
fun FollowListScreen(userId: String, startOnFollowing: Boolean, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.posts
    val colors = DlTheme.colors
    val scope = rememberCoroutineScope()
    val me by container.account.me.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(if (startOnFollowing) 1 else 0) }
    var people by remember { mutableStateOf<List<FollowUserResponse>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(emptySet<String>()) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    LaunchedEffect(tab) {
        people = null
        failed = false
        runCatching { if (tab == 1) repo.following(userId) else repo.followers(userId) }
            .onSuccess { people = it }
            .onFailure { failed = true }
    }

    fun toggleFollow(person: FollowUserResponse) {
        pending = pending + person.id
        scope.launch {
            runCatching {
                val state = if (person.followState == FollowState.NONE) repo.follow(person.id) else repo.unfollow(person.id).let { FollowState.NONE }
                people = people?.map { if (it.id == person.id) it.copy(followState = state) else it }
            }
            pending = pending - person.id
        }
    }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text(if (tab == 1) "Following" else "Followers", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        Column(Modifier.fillMaxSize().padding(top = top)) {
            SegmentedControl(listOf("Followers", "Following"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = people
                when {
                    failed -> Text("Couldn't load this list.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.align(Alignment.Center))
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                    list.isEmpty() -> Text(if (tab == 1) "Not following anyone yet." else "No followers yet.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom)) {
                        items(list, key = { it.id }) { person ->
                            PersonRow(person.displayName, person.username, onClick = { onOpenUser(person.id) }) {
                                if (person.id != me?.id) {
                                    val (label, filled) = when (person.followState) {
                                        FollowState.NONE -> "Follow" to true
                                        FollowState.REQUESTED -> "Requested" to false
                                        FollowState.FOLLOWING -> "Following" to false
                                    }
                                    // Friends can't unfollow from here; their profile explains why.
                                    ProfileAction(label, filled, Modifier.width(104.dp), busy = person.id in pending, height = 36.dp) {
                                        if (person.followState == FollowState.FOLLOWING) onOpenUser(person.id) else toggleFollow(person)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** People asking to follow your private account. */
@Composable
fun FollowRequestsScreen(onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val repo = LocalAppContainer.current.posts
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<FollowUserResponse>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    LaunchedEffect(Unit) { people = runCatching { repo.followRequests() }.getOrDefault(emptyList()) }

    fun answer(person: FollowUserResponse, accept: Boolean) {
        scope.launch {
            runCatching { if (accept) repo.acceptFollow(person.id) else repo.declineFollow(person.id) }
                .onSuccess {
                    if (accept) haptics.confirm() else haptics.tick()
                    people = people?.filterNot { it.id == person.id }
                }
                .onFailure {
                    haptics.reject()
                    error = "That didn't work. Try again."
                    errorTick++
                }
        }
    }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Follow requests", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        Column(Modifier.fillMaxSize().padding(top = top)) {
            ErrorBanner(error, errorTick, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = people
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                    list.isEmpty() -> Text("No one's waiting.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                        items(list, key = { it.id }) { person ->
                            PersonRow(person.displayName, person.username, onClick = { onOpenUser(person.id) }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RoundAction(Icons.Rounded.Close, "Decline", colors.textTertiary.copy(alpha = 0.18f), colors.textSecondary) { answer(person, false) }
                                    RoundAction(Icons.Rounded.Check, "Accept", colors.success, Color.White) { answer(person, true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).pressable(pressedScale = 0.88f, onClick = onClick).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}
