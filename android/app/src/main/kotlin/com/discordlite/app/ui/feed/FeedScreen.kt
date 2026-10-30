package com.discordlite.app.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.PublicUser
import com.discordlite.app.ui.posts.PostCard
import com.discordlite.app.ui.posts.PostSheets
import com.discordlite.app.ui.profile.PersonRow
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.LargeTitleScreen
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.theme.DlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Posts from you and the people you follow, newest first. There's no algorithm. */
@Composable
fun FeedScreen(sheets: PostSheets, onOpenUser: (String) -> Unit, onNewPost: () -> Unit, onSearch: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.posts
    val colors = DlTheme.colors
    val ids by repo.feed.collectAsStateWithLifecycle()
    val posts by repo.posts.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        failed = runCatching { repo.refreshFeed() }.isFailure
        loading = false
    }
    // You just posted: show it.
    LaunchedEffect(ids.firstOrNull()) {
        if (posts[ids.firstOrNull()]?.author?.id == me?.id) listState.animateScrollToItem(0)
    }
    // Load the next page a little before the end.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) scope.launch { runCatching { repo.loadMoreFeed() } }
        }
    }

    LargeTitleScreen(
        title = "Feed",
        listState = listState,
        actions = {
            IconCircleButton(Icons.Rounded.Search, "Find people", onSearch, size = 38.dp)
            IconCircleButton(Icons.Rounded.Add, "New post", onNewPost, size = 38.dp)
        },
    ) {
        items(ids, key = { it }) { id ->
            posts[id]?.let { PostCard(it, sheets, onOpenUser) }
        }
        if (ids.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                        failed -> Text("Couldn't load your feed. Check your connection.", style = DlTheme.type.callout, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                        else -> Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Nothing here yet", style = DlTheme.type.title3, color = colors.textPrimary)
                            Text("Follow people to see their posts here, or share your first one.", style = DlTheme.type.callout, color = colors.textSecondary, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton("Find people", onClick = onSearch, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Find anyone by username. This is the only way to discover people: there's no suggestions list. */
@Composable
fun PeopleSearchScreen(onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val repo = LocalAppContainer.current.social
    val colors = DlTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PublicUser>()) }
    var searched by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    LaunchedEffect(query) {
        searched = false
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250) // wait for a pause in typing
        results = runCatching { repo.search(query) }.getOrDefault(emptyList())
        searched = true
    }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Find people", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding().padding(top = top)) {
            DlTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search by username",
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (searched && results.isEmpty()) {
                Text("Nobody by that username.", style = DlTheme.type.callout, color = colors.textSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                items(results, key = { it.id }) { user ->
                    PersonRow(user.displayName, user.username, onClick = { onOpenUser(user.id) })
                }
            }
        }
    }
}
