package com.discordlite.app.ui.posts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.theme.DlTheme

/** One post on its own: from a profile grid, or a card in a chat. */
@Composable
fun PostDetailScreen(postId: String, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val repo = LocalAppContainer.current.posts
    val colors = DlTheme.colors
    val sheets = rememberPostSheets()
    val posts by repo.posts.collectAsStateWithLifecycle()
    val post = posts[postId]
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    LaunchedEffect(postId) {
        runCatching { repo.load(postId) }.onSuccess { loaded = true }.onFailure { failed = true }
    }
    // Deleted from this screen: nothing left to show.
    LaunchedEffect(post == null) { if (post == null && loaded) onBack() }

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Post", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
            PostSheetHost(sheets, onOpenUser)
        },
    ) {
        when {
            post != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = bottom)) {
                item(key = post.id) { PostCard(post, sheets, onOpenUser) }
            }
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This post isn't available.", style = DlTheme.type.callout, color = colors.textSecondary)
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = colors.textTertiary, strokeWidth = 2.dp)
            }
        }
    }
}
