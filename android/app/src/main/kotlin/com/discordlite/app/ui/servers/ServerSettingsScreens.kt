package com.discordlite.app.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ChannelResponse
import com.discordlite.app.data.remote.Permissions
import com.discordlite.app.data.remote.RoleResponse
import com.discordlite.app.data.remote.ServerMemberResponse
import com.discordlite.app.data.remote.ServerResponse
import com.discordlite.app.ui.chats.CheckCircle
import com.discordlite.app.ui.chats.InfoFooter
import com.discordlite.app.ui.chats.InfoHeader
import com.discordlite.app.ui.chats.SheetHeader
import com.discordlite.app.ui.chats.SwipeToReveal
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlSwitch
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

/** Runs a server action with haptics and an error message (shared by the settings pages). */
private class Action(private val scope: kotlinx.coroutines.CoroutineScope, private val haptics: com.discordlite.designsystem.theme.Haptics) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tick by mutableIntStateOf(0)

    fun run(block: suspend () -> Unit, onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    haptics.confirm()
                    error = null
                    onDone()
                }
                .onFailure {
                    haptics.reject()
                    error = serverError(it)
                    tick++
                }
            busy = false
        }
    }
}

@Composable
private fun rememberAction(): Action {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    return remember { Action(scope, haptics) }
}

/** Glass top bar with a centred title (and optional trailing action), like Settings. */
@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null, sheets: @Composable () -> Unit = {}, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val colors = DlTheme.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp
    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text(title, style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (action != null) Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { action() } else Spacer(Modifier.width(44.dp))
            })
            sheets()
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = 40.dp), content = content)
    }
}

@Composable
private fun Separator(inset: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(DlTheme.colors.separator))
}

@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, value: String?, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = DlTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = DlTheme.type.body, color = colors.textTertiary)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.textTertiary)
    }
}

@Composable
private fun DangerRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val colors = DlTheme.colors
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.99f, highlight = colors.danger.copy(alpha = 0.08f), onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = colors.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = DlTheme.type.headline, color = colors.danger)
        }
    }
}

/** "Are you sure?" sheet with a red confirm button. */
@Composable
private fun ConfirmSheetContent(title: String, detail: String, confirm: String, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, style = DlTheme.type.title3, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = DlTheme.type.subheadline, color = colors.textSecondary)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .pressable(enabled = !busy, pressedScale = 0.97f, onClick = onConfirm)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.danger),
            contentAlignment = Alignment.Center,
        ) { Text(confirm, style = DlTheme.type.headline, color = Color.White) }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
}

/** A sheet with one text field (create / rename things), and an optional red action. */
@Composable
private fun TextSheetContent(
    title: String,
    initial: String,
    placeholder: String,
    confirm: String,
    action: Action,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    destructive: Pair<String, () -> Unit>? = null,
    extra: @Composable () -> Unit = {},
) {
    var value by rememberSaveable(title, initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(title, onCancel, confirm, value.isNotBlank() && !action.busy) { onConfirm(value) }
        DlTextField(value, { value = it.take(100) }, placeholder = placeholder, modifier = Modifier.padding(horizontal = 16.dp))
        ErrorBanner(action.error, action.tick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        extra()
        destructive?.let { (label, onClick) ->
            Spacer(Modifier.height(8.dp))
            TextAction(label, onClick, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = DlTheme.colors.danger)
        }
    }
}

// --- Server Settings (the hub) ---

private enum class HubSheet { Leave, Delete }

@Composable
fun ServerSettingsScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenRoles: () -> Unit,
    onOpenMembers: () -> Unit,
    onGone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val repo = container.servers
    val colors = DlTheme.colors
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val me by container.account.me.collectAsStateWithLifecycle()
    val action = rememberAction()
    var editing by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<HubSheet?>(null) }
    var lastSheet by remember { mutableStateOf<HubSheet?>(null) }
    if (sheet != null) lastSheet = sheet
    LaunchedEffect(serverId) { runCatching { repo.load(serverId) } }

    val s = server
    val isOwner = s?.ownerId == me?.id
    SettingsScaffold(
        title = "Server Settings",
        onBack = onBack,
        sheets = {
            SpringSheet(visible = sheet != null, onDismiss = { sheet = null }) {
                when (lastSheet) {
                    HubSheet.Leave -> ConfirmSheetContent(
                        "Leave “${s?.name}”?", "You won't see its channels any more. A friend in it can add you back.", "Leave server", action.busy,
                        onConfirm = { action.run({ repo.leave(serverId) }) { sheet = null; onGone() } },
                        onCancel = { sheet = null },
                    )
                    HubSheet.Delete -> ConfirmSheetContent(
                        "Delete “${s?.name}”?", "Every channel and message is deleted for everyone. This can't be undone.", "Delete server", action.busy,
                        onConfirm = { action.run({ repo.delete(serverId) }) { sheet = null; onGone() } },
                        onCancel = { sheet = null },
                    )
                    null -> Unit
                }
            }
        },
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(s?.name ?: "…", 88.dp, shape = RoundedCornerShape(26.dp))
                Spacer(Modifier.height(12.dp))
                Text(s?.name ?: "…", style = DlTheme.type.title2, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(Modifier.height(4.dp))
                Text("${s?.memberCount ?: 0} members", style = DlTheme.type.footnote, color = colors.textSecondary)
            }
        }
        item { ErrorBanner(action.error, action.tick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

        item { InfoHeader("Overview") }
        item {
            SectionCard {
                if (editing) {
                    Column(Modifier.padding(12.dp)) {
                        DlTextField(nameDraft, { nameDraft = it.take(100) }, placeholder = "Server name")
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End)) {
                            TextAction("Cancel", { editing = false }, color = colors.textSecondary)
                            TextAction("Save", { action.run({ repo.rename(serverId, nameDraft) }) { editing = false } })
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s?.name ?: "…", style = DlTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        if (s?.can(Permissions.MANAGE_SERVER) == true) {
                            TextAction("Edit", {
                                nameDraft = s.name
                                editing = true
                            })
                        }
                    }
                }
            }
        }

        item { InfoHeader("Manage") }
        item {
            SectionCard {
                Column {
                    if (s?.can(Permissions.MANAGE_CHANNELS) == true) {
                        NavRow(Icons.Rounded.Tag, Color(0xFF0A84FF), "Channels", "${s.channels.size}", onOpenChannels)
                        Separator(60.dp)
                    }
                    if (s?.can(Permissions.MANAGE_ROLES) == true) {
                        NavRow(Icons.Rounded.Shield, Color(0xFF5E5CE6), "Roles", "${s.roles.size}", onOpenRoles)
                        Separator(60.dp)
                    }
                    NavRow(Icons.Rounded.Group, Color(0xFF34C759), "Members", "${s?.memberCount ?: 0}", onOpenMembers)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, tint = colors.textTertiary, modifier = Modifier.size(13.dp).padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Server channels aren't end-to-end encrypted, so people who join later can read the history. DMs and group chats are.",
                    style = DlTheme.type.footnote,
                    color = colors.textTertiary,
                )
            }
        }
        if (s != null) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                if (isOwner) DangerRow(Icons.Rounded.Delete, "Delete server") { sheet = HubSheet.Delete }
                else DangerRow(Icons.AutoMirrored.Rounded.Logout, "Leave server") { sheet = HubSheet.Leave }
            }
        }
    }
}

// --- Channels ---

private sealed interface ChannelSheet {
    data class NewChannel(val categoryId: String?) : ChannelSheet
    data object NewCategory : ChannelSheet
    data class EditChannel(val channel: ChannelResponse) : ChannelSheet
    data class EditCategory(val id: String, val name: String) : ChannelSheet
}

/** Add, rename and delete channels and categories. Tap one to edit it. */
@Composable
fun ServerChannelsScreen(serverId: String, onBack: () -> Unit) {
    val repo = LocalAppContainer.current.servers
    val colors = DlTheme.colors
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val action = rememberAction()
    var sheet by remember { mutableStateOf<ChannelSheet?>(null) }
    var lastSheet by remember { mutableStateOf<ChannelSheet?>(null) }
    if (sheet != null) lastSheet = sheet
    val s = server

    SettingsScaffold(
        title = "Channels",
        onBack = onBack,
        sheets = {
            SpringSheet(visible = sheet != null, onDismiss = { sheet = null }) {
                when (val current = lastSheet) {
                    is ChannelSheet.NewChannel -> {
                        var category by remember(current) { mutableStateOf(current.categoryId) }
                        TextSheetContent(
                            "New Channel", "", "channel-name", "Create", action,
                            onConfirm = { action.run({ repo.createChannel(serverId, it, category) }) { sheet = null } },
                            onCancel = { sheet = null },
                            extra = {
                                Text("CATEGORY", style = DlTheme.type.micro, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                                (listOf<Pair<String?, String>>(null to "No category") + s?.categories.orEmpty().map { it.id to it.name }).forEach { (id, name) ->
                                    Row(
                                        Modifier.fillMaxWidth().pressable(pressedScale = 0.99f) { category = id }.padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(name, style = DlTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                                        CheckCircle(category == id)
                                    }
                                }
                            },
                        )
                    }
                    ChannelSheet.NewCategory -> TextSheetContent(
                        "New Category", "", "Category name", "Create", action,
                        onConfirm = { action.run({ repo.createCategory(serverId, it) }) { sheet = null } },
                        onCancel = { sheet = null },
                    )
                    is ChannelSheet.EditChannel -> TextSheetContent(
                        "Edit Channel", current.channel.name, "channel-name", "Save", action,
                        onConfirm = { action.run({ repo.renameChannel(current.channel.id, it) }) { sheet = null } },
                        onCancel = { sheet = null },
                        destructive = "Delete #${current.channel.name}" to { action.run({ repo.deleteChannel(current.channel.id) }) { sheet = null } },
                    )
                    is ChannelSheet.EditCategory -> TextSheetContent(
                        "Edit Category", current.name, "Category name", "Save", action,
                        onConfirm = { action.run({ repo.renameCategory(serverId, current.id, it) }) { sheet = null } },
                        onCancel = { sheet = null },
                        destructive = "Delete category (keeps its channels)" to { action.run({ repo.deleteCategory(serverId, current.id) }) { sheet = null } },
                    )
                    null -> Unit
                }
            }
        },
    ) {
        if (s == null) return@SettingsScaffold
        val loose = s.channels.filter { it.categoryId == null }
        if (loose.isNotEmpty()) {
            item { InfoHeader("No category") }
            item { ChannelList(loose) { sheet = ChannelSheet.EditChannel(it) } }
        }
        s.categories.forEach { category ->
            item(key = category.id) {
                Row(Modifier.fillMaxWidth().padding(end = 24.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { InfoHeader(category.name) }
                    TextAction("Edit", { sheet = ChannelSheet.EditCategory(category.id, category.name) }, Modifier.padding(bottom = 6.dp))
                }
            }
            item(key = "${category.id}-channels") {
                ChannelList(s.channels.filter { it.categoryId == category.id }, onAdd = { sheet = ChannelSheet.NewChannel(category.id) }) { sheet = ChannelSheet.EditChannel(it) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            SectionCard {
                Column {
                    AddRow("Create channel") { sheet = ChannelSheet.NewChannel(s.categories.firstOrNull()?.id) }
                    Separator(60.dp)
                    AddRow("Create category") { sheet = ChannelSheet.NewCategory }
                }
            }
        }
        item { InfoFooter("Tap a channel or category to rename or delete it. Deleting a channel deletes its messages.") }
    }
}

@Composable
private fun ChannelList(channels: List<ChannelResponse>, onAdd: (() -> Unit)? = null, onEdit: (ChannelResponse) -> Unit) {
    val colors = DlTheme.colors
    SectionCard {
        Column {
            channels.forEachIndexed { i, channel ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f)) { onEdit(channel) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Tag, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(channel.name, style = DlTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.textTertiary)
                }
                if (i < channels.lastIndex || onAdd != null) Separator(46.dp)
            }
            if (onAdd != null) AddRow("Add channel here", onAdd)
        }
    }
}

@Composable
private fun AddRow(title: String, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = DlTheme.type.body, color = colors.accent)
    }
}

// --- Roles ---

@Composable
fun ServerRolesScreen(serverId: String, onBack: () -> Unit, onOpenRole: (String?) -> Unit) {
    val repo = LocalAppContainer.current.servers
    val colors = DlTheme.colors
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val s = server
    SettingsScaffold(title = "Roles", onBack = onBack) {
        if (s == null) return@SettingsScaffold
        val custom = s.roles.filter { !it.isDefault }
        val everyone = s.roles.firstOrNull { it.isDefault }
        item { InfoHeader("Roles · ${custom.size}") }
        item {
            SectionCard {
                Column {
                    custom.forEach { role ->
                        RoleRow(role) { onOpenRole(role.id) }
                        Separator(52.dp)
                    }
                    AddRow("Create role") { onOpenRole(null) }
                }
            }
        }
        item { InfoFooter("Higher roles are listed first. A member's name takes the colour of their highest role, and their powers are all their roles combined.") }
        if (everyone != null) {
            item { InfoHeader("Default") }
            item { SectionCard { RoleRow(everyone, subtitle = "What everyone in the server can do") { onOpenRole(everyone.id) } } }
        }
    }
}

@Composable
private fun RoleRow(role: RoleResponse, subtitle: String? = null, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (role.color != 0) Color(role.color) else colors.textTertiary))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (role.isDefault) "@everyone" else role.name, style = DlTheme.type.body, color = colors.textPrimary)
            Text(subtitle ?: if (role.memberCount == 1) "1 member" else "${role.memberCount} members", style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.textTertiary)
    }
}

private val RoleColors = listOf(0, 0xFF5865F2, 0xFF3BA55C, 0xFFFAA61A, 0xFFED4245, 0xFFEB459E, 0xFF9B59B6, 0xFF1ABC9C, 0xFF95A5A6).map { it.toInt() }

private val PermissionInfo = listOf(
    Triple(Permissions.MANAGE_SERVER, "Manage server", "Rename the server"),
    Triple(Permissions.MANAGE_CHANNELS, "Manage channels", "Create, rename and delete channels and categories"),
    Triple(Permissions.MANAGE_ROLES, "Manage roles", "Create roles and give them to people (only powers they have themselves)"),
    Triple(Permissions.ADD_MEMBERS, "Add members", "Add their friends to the server"),
    Triple(Permissions.KICK_MEMBERS, "Kick members", "Remove people who have no management powers"),
    Triple(Permissions.MANAGE_MESSAGES, "Delete others' messages", "Remove anyone's messages in any channel"),
)

/** Create ([roleId] null) or edit a role: name, colour, and what it lets people do. */
@Composable
fun RoleEditScreen(serverId: String, roleId: String?, onBack: () -> Unit) {
    val repo = LocalAppContainer.current.servers
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val role = server?.roles?.firstOrNull { it.id == roleId }
    val action = rememberAction()
    var name by rememberSaveable(role?.id) { mutableStateOf(role?.name.orEmpty()) }
    var color by rememberSaveable(role?.id) { mutableIntStateOf(role?.color ?: RoleColors[1]) }
    var permissions by rememberSaveable(role?.id) { mutableLongStateOf(role?.permissions ?: 0L) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mine = server?.myPermissions ?: 0L
    val isDefault = role?.isDefault == true
    // You can only edit roles within your own powers.
    val editable = role == null || role.permissions and mine.inv() == 0L

    SettingsScaffold(
        title = when {
            roleId == null -> "New Role"
            isDefault -> "@everyone"
            else -> "Edit Role"
        },
        onBack = onBack,
        action = {
            TextAction(
                if (roleId == null) "Create" else "Save",
                {
                    if (!editable || (!isDefault && name.isBlank())) return@TextAction
                    action.run({
                        if (role == null) repo.createRole(serverId, name, color, permissions) else repo.updateRole(serverId, role.id, name, color, permissions)
                    }) { onBack() }
                },
                Modifier.alpha(if (editable && (isDefault || name.isNotBlank()) && !action.busy) 1f else 0.35f),
            )
        },
        sheets = {
            SpringSheet(visible = confirmDelete, onDismiss = { confirmDelete = false }) {
                ConfirmSheetContent(
                    "Delete “${role?.name}”?", "People with this role lose what it lets them do.", "Delete role", action.busy,
                    onConfirm = { action.run({ repo.deleteRole(serverId, roleId!!) }) { confirmDelete = false; onBack() } },
                    onCancel = { confirmDelete = false },
                )
            }
        },
    ) {
        item { ErrorBanner(action.error, action.tick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (!isDefault) {
            item { InfoHeader("Name") }
            item {
                SectionCard {
                    DlTextField(name, { name = it.take(32) }, placeholder = "e.g. Mods", modifier = Modifier.padding(12.dp), readOnly = !editable)
                }
            }
            item { InfoHeader("Colour") }
            item {
                SectionCard {
                    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(RoleColors) { option ->
                            val chosen = option == color
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .pressable(enabled = editable, pressedScale = 0.88f) {
                                        haptics.selection()
                                        color = option
                                    }
                                    .clip(CircleShape)
                                    .background(if (option == 0) colors.textTertiary.copy(alpha = 0.3f) else Color(option))
                                    .border(if (chosen) 3.dp else 0.dp, colors.textPrimary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedVisibility(chosen, enter = scaleIn(DlMotion.pop()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { InfoHeader("What it allows") }
        item {
            SectionCard {
                Column {
                    PermissionInfo.forEachIndexed { i, (bit, title, detail) ->
                        // Powers you don't have can't be switched on (or off) by you.
                        val allowed = editable && mine and bit != 0L
                        Row(Modifier.fillMaxWidth().alpha(if (allowed) 1f else 0.45f).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = DlTheme.type.body, color = colors.textPrimary)
                                Text(detail, style = DlTheme.type.footnote, color = colors.textSecondary)
                            }
                            Spacer(Modifier.width(10.dp))
                            DlSwitch(permissions and bit != 0L, { on -> if (allowed) permissions = if (on) permissions or bit else permissions and bit.inv() })
                        }
                        if (i < PermissionInfo.lastIndex) Separator()
                    }
                }
            }
        }
        item {
            InfoFooter(
                when {
                    !editable -> "This role has powers you don't have, so only someone with them (or the owner) can change it."
                    isDefault -> "Everyone in the server gets these, on top of any roles they have."
                    else -> "You can only switch on powers you have yourself."
                },
            )
        }
        if (role != null && !isDefault && editable) {
            item { Spacer(Modifier.height(16.dp)) }
            item { DangerRow(Icons.Rounded.Delete, "Delete role") { confirmDelete = true } }
        }
    }
}

// --- Members ---

/** Everyone in the server. Tap someone to change their roles or kick them (if you're allowed). */
@Composable
fun ServerMembersScreen(serverId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.servers
    val colors = DlTheme.colors
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    val members by remember(serverId) { repo.members(serverId) }.collectAsStateWithLifecycle(null)
    val me by container.account.me.collectAsStateWithLifecycle()
    val action = rememberAction()
    var selected by remember { mutableStateOf<ServerMemberResponse?>(null) }
    var lastSelected by remember { mutableStateOf<ServerMemberResponse?>(null) }
    if (selected != null) lastSelected = selected
    LaunchedEffect(serverId) { runCatching { repo.loadMembers(serverId) } }
    val s = server

    fun canManage(target: ServerMemberResponse): Boolean {
        if (s == null || target.id == s.ownerId) return false
        if (s.ownerId == me?.id) return true
        return powersOf(s, target) == 0L || target.id == me?.id
    }

    SettingsScaffold(
        title = "Members",
        onBack = onBack,
        sheets = {
            SpringSheet(visible = selected != null, onDismiss = { selected = null }) {
                val target = lastSelected
                if (s != null && target != null) MemberSheetContent(s, target, canManage(target), target.id == me?.id, action) { selected = null }
            }
        },
    ) {
        item { ErrorBanner(action.error, action.tick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { InfoHeader("Members · ${members?.size ?: s?.memberCount ?: 0}") }
        item {
            SectionCard {
                Column {
                    val list = members.orEmpty()
                    list.forEachIndexed { i, member ->
                        val kickable = s != null && s.can(Permissions.KICK_MEMBERS) && member.id != me?.id && canManage(member)
                        SwipeToReveal(enabled = kickable, action = "Kick", onAction = { action.run({ repo.kick(serverId, member.id) }) }) {
                            Box(Modifier.pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f)) { selected = member }) {
                                MemberRow(member, s?.let { topRole(it, member) }, isOwner = member.id == s?.ownerId) {
                                    val names = s?.roles?.filter { !it.isDefault && it.id in member.roleIds }?.map { it.name }.orEmpty()
                                    if (names.isNotEmpty()) {
                                        Text(names.joinToString(", "), style = DlTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
                                    }
                                }
                            }
                        }
                        if (i < list.lastIndex) Separator(72.dp)
                    }
                }
            }
        }
        item {
            InfoFooter(
                if (s?.can(Permissions.KICK_MEMBERS) == true) "Tap someone to change their roles. Swipe left to kick." else "Tap someone to see their roles.",
            )
        }
    }
}

/** Powers from the member's roles plus @everyone. */
private fun powersOf(server: ServerResponse, member: ServerMemberResponse): Long =
    server.roles.filter { it.isDefault || it.id in member.roleIds }.fold(0L) { all, role -> all or role.permissions }

@Composable
private fun MemberSheetContent(server: ServerResponse, member: ServerMemberResponse, manageable: Boolean, isMe: Boolean, action: Action, onDone: () -> Unit) {
    val repo = LocalAppContainer.current.servers
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    var roles by remember(member.id, member.roleIds) { mutableStateOf(member.roleIds.toSet()) }
    val canRole = server.can(Permissions.MANAGE_ROLES) && (manageable || isMe)
    val custom = server.roles.filter { !it.isDefault }
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(member.displayName, onDone, "Save", canRole && roles != member.roleIds.toSet() && !action.busy) {
            action.run({ repo.setMemberRoles(server.id, member.id, roles.toList()) }, onDone)
        }
        Text("@${member.username}", style = DlTheme.type.footnote, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
        ErrorBanner(action.error, action.tick, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Text("ROLES", style = DlTheme.type.micro, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (custom.isEmpty()) {
            Text("This server has no roles yet.", style = DlTheme.type.subheadline, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        custom.forEach { role ->
            // Only roles within your own powers can be given or taken away.
            val allowed = canRole && role.permissions and server.myPermissions.inv() == 0L
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(enabled = allowed, pressedScale = 0.99f) {
                        haptics.selection()
                        roles = if (role.id in roles) roles - role.id else roles + role.id
                    }
                    .alpha(if (allowed) 1f else 0.45f)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (role.color != 0) Color(role.color) else colors.textTertiary))
                Spacer(Modifier.width(12.dp))
                Text(role.name, style = DlTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                CheckCircle(role.id in roles)
            }
        }
        if (member.id == server.ownerId) {
            InfoFooter("The owner can do everything, whatever their roles.")
        } else if (!manageable && !isMe) {
            InfoFooter("Only the owner can change roles for someone with management powers.")
        }
    }
}
