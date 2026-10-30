package com.discordlite.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.runtime.Composable
import com.discordlite.app.data.LocalAppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.discordlite.app.data.SampleData
import com.discordlite.app.ui.chats.ChatsScreen
import com.discordlite.app.ui.chats.NewChatSheetContent
import com.discordlite.app.ui.chats.NewGroupSheetContent
import com.discordlite.app.posts.PostsRepository
import com.discordlite.app.ui.feed.FeedScreen
import com.discordlite.app.ui.posts.PostSheetHost
import com.discordlite.app.ui.posts.rememberPostSheets
import com.discordlite.app.ui.profile.ProfileScreen
import com.discordlite.app.ui.servers.AddToServerSheetContent
import com.discordlite.app.ui.stories.AddStorySheetContent
import com.discordlite.app.ui.servers.CreateServerSheetContent
import com.discordlite.app.ui.servers.InviteSheetContent
import com.discordlite.app.ui.servers.JoinWithInviteSheetContent
import com.discordlite.app.ui.servers.ServerActionsSheetContent
import com.discordlite.app.ui.servers.ServersScreen
import com.discordlite.designsystem.component.FloatingTabBar
import com.discordlite.designsystem.component.LocalHazeState
import com.discordlite.designsystem.component.SpringSheet
import com.discordlite.designsystem.component.TabItem
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

private sealed interface HomeSheet {
    data object NewChat : HomeSheet
    data object NewGroup : HomeSheet
    data object ServerActions : HomeSheet
    data object CreateServer : HomeSheet
    data object JoinWithInvite : HomeSheet
    data class Invite(val serverId: String) : HomeSheet
    data class AddToServer(val serverId: String) : HomeSheet
    data object AddStory : HomeSheet
}

private fun tabs(unread: Int) = listOf(
    TabItem("Chats", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble, badge = unread),
    TabItem("Servers", Icons.Outlined.Tag, Icons.Rounded.Tag),
    TabItem("Feed", Icons.Outlined.Explore, Icons.Rounded.Explore),
    TabItem("Profile", Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
)

/**
 * The four tabs. Switching is instant (as on iOS) while each tab keeps its own scroll position;
 * only the tab bar's selection pill moves. Sheets are hosted here so they cover the tab bar.
 */
@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenChannel: (String, String) -> Unit,
    onOpenServerSettings: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFriends: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenStories: (userId: String) -> Unit,
    onComposeStory: (uri: String) -> Unit,
    onOpenStoryArchive: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenFollowList: (userId: String, following: Boolean) -> Unit,
    onOpenFollowRequests: () -> Unit,
    onSearchPeople: () -> Unit,
    onComposePost: (uris: List<String>) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val conversations by LocalAppContainer.current.chat.conversations.collectAsStateWithLifecycle(emptyList())
    val tabItems = tabs(conversations.sumOf { it.unread })
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val context = LocalContext.current
    // Photo picker (no storage permission needed) and camera, for a new story.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onComposeStory(it.toString()) } }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> if (saved) captureUri?.let(onComposeStory) }
    val postSheets = rememberPostSheets()
    val pickPostPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PostsRepository.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) onComposePost(uris.map { it.toString() })
    }
    // Keep the last sheet's content while it animates away.
    var lastSheet by remember { mutableStateOf<HomeSheet?>(null) }
    if (sheet != null) lastSheet = sheet
    val holder = rememberSaveableStateHolder()
    val hazeState = remember { HazeState() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> ChatsScreen(
                        onOpenChat = onOpenChat,
                        onOpenGroup = onOpenGroup,
                        onNewChat = { sheet = HomeSheet.NewChat },
                        onOpenFriends = onOpenFriends,
                        onOpenStories = onOpenStories,
                        onAddStory = { sheet = HomeSheet.AddStory },
                    )
                    1 -> ServersScreen(
                        onOpenChannel = onOpenChannel,
                        onOpenSettings = onOpenServerSettings,
                        onCreateServer = { sheet = HomeSheet.ServerActions },
                        onAddMembers = { sheet = HomeSheet.AddToServer(it) },
                        onInvite = { sheet = HomeSheet.Invite(it) },
                    )
                    2 -> FeedScreen(sheets = postSheets, onOpenUser = onOpenUser, onNewPost = { pickPostPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onSearch = onSearchPeople)
                    else -> ProfileScreen(
                        onOpenSettings = onOpenSettings,
                        onEditProfile = onEditProfile,
                        onOpenStoryArchive = onOpenStoryArchive,
                        onOpenPost = onOpenPost,
                        onOpenFollowList = onOpenFollowList,
                        onOpenFollowRequests = onOpenFollowRequests,
                    )
                }
            }
        }
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            FloatingTabBar(tabItems, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
        PostSheetHost(postSheets, onOpenUser)
        SpringSheet(visible = sheet != null, onDismiss = { sheet = null }) {
            when (val s = lastSheet) {
                HomeSheet.NewChat -> NewChatSheetContent(
                    onOpenChat = {
                        sheet = null
                        onOpenChat(it)
                    },
                    onFindFriends = {
                        sheet = null
                        onOpenFriends()
                    },
                    onNewGroup = { sheet = HomeSheet.NewGroup },
                )
                HomeSheet.NewGroup -> NewGroupSheetContent(
                    onCancel = { sheet = null },
                    onCreated = {
                        sheet = null
                        onOpenGroup(it)
                    },
                )
                HomeSheet.ServerActions -> ServerActionsSheetContent(
                    onCreate = { sheet = HomeSheet.CreateServer },
                    onJoin = { sheet = HomeSheet.JoinWithInvite },
                )
                HomeSheet.CreateServer -> CreateServerSheetContent(onCancel = { sheet = null }, onCreated = { sheet = null })
                HomeSheet.JoinWithInvite -> JoinWithInviteSheetContent(onDone = { sheet = null })
                is HomeSheet.Invite -> InviteSheetContent(s.serverId)
                is HomeSheet.AddToServer -> AddToServerSheetContent(s.serverId, onDone = { sheet = null })
                HomeSheet.AddStory -> AddStorySheetContent(
                    onPick = {
                        sheet = null
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onCamera = {
                        sheet = null
                        val file = java.io.File(context.cacheDir, "captures").apply { mkdirs() }.resolve("story-${System.currentTimeMillis()}.jpg")
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        captureUri = uri.toString()
                        takePhoto.launch(uri)
                    },
                )
                null -> Unit
            }
        }
    }
}
