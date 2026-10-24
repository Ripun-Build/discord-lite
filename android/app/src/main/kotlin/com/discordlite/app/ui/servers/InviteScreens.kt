package com.discordlite.app.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LinkOff
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.remote.ServerResponse
import com.discordlite.app.servers.InviteLinks
import com.discordlite.app.servers.InviteState
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.InviteCard
import com.discordlite.designsystem.component.InvitePreview
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.component.SegmentedControl
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opens a server you're in at its first channel (through the same path a notification uses). */
@Composable
private fun rememberOpenServer(): (ServerResponse) -> Unit {
    val container = LocalAppContainer.current
    return remember {
        { server ->
            val channel = server.channels.firstOrNull { it.categoryId == null } ?: server.channels.firstOrNull()
            if (channel != null) container.openChatRequests.value = "channel:${server.id}:${channel.id}"
        }
    }
}

// --- Cards in chats ---

/** Every invite link in a message becomes a live card under it (at most two). */
@Composable
internal fun InviteCards(text: String, modifier: Modifier = Modifier) {
    val codes = remember(text) { InviteLinks.codesIn(text).take(2) }
    if (codes.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        codes.forEach { InviteLinkCard(it) }
    }
}

/**
 * The invite card for one code: name and member count fetched from the server, Join to join (then
 * it springs to "Joined" and opens the server). Dead links say so instead.
 */
@Composable
internal fun InviteLinkCard(code: String, modifier: Modifier = Modifier, onJoined: (ServerResponse) -> Unit = {}) {
    val repo = LocalAppContainer.current.servers
    val colors = DlTheme.colors
    val scope = rememberCoroutineScope()
    val openServer = rememberOpenServer()
    val state by remember(code) { repo.invite(code) }.collectAsStateWithLifecycle(InviteState.Loading)
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    when (val s = state) {
        InviteState.Loading -> Box(
            modifier.width(300.dp).height(150.dp).clip(RoundedCornerShape(20.dp)).background(if (colors.isDark) colors.surfaceRaised else colors.surface),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(22.dp), color = colors.textTertiary, strokeWidth = 2.dp) }
        InviteState.Invalid -> Row(
            modifier
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (colors.isDark) colors.surfaceRaised else colors.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.danger.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LinkOff, null, tint = colors.danger, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Invite invalid", style = DlTheme.type.headline, color = colors.textPrimary)
                Text("This link has expired, been used up, or deleted.", style = DlTheme.type.footnote, color = colors.textSecondary)
            }
        }
        is InviteState.Valid -> Column(modifier) {
            val preview = s.preview
            InviteCard(
                invite = InvitePreview(preview.code, preview.serverName, online = -1, members = preview.memberCount),
                joined = preview.joined,
                onJoin = {
                    scope.launch {
                        runCatching { repo.join(code) }
                            .onSuccess {
                                onJoined(it)
                                delay(450) // let "Joined" land before moving on
                                openServer(it)
                            }
                            .onFailure {
                                error = serverError(it)
                                errorTick++
                            }
                    }
                },
                // Already in: tapping the card opens the server.
                modifier = if (preview.joined) Modifier.pressable(pressedScale = 0.98f) {
                    scope.launch { runCatching { repo.load(preview.serverId) }.onSuccess(openServer) }
                } else Modifier,
            )
            ErrorBanner(error, errorTick, Modifier.width(300.dp).padding(top = 6.dp))
        }
    }
}

// --- Opening a link ---

/** Where a tapped invite link lands: the card, big, with Join. */
@Composable
fun JoinInviteScreen(code: String, onBack: () -> Unit) {
    val colors = DlTheme.colors
    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Invite", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            InviteLinkCard(code)
            Spacer(Modifier.height(16.dp))
            Text(
                "Server channels aren't end-to-end encrypted. Everyone in the server can read them.",
                style = DlTheme.type.footnote,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

// --- The rail's "+" ---

/** Sheet content: make a server, or join one with a link. */
@Composable
fun ServerActionsSheetContent(onCreate: () -> Unit, onJoin: () -> Unit) {
    val colors = DlTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("Add a server", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        ActionTile(Icons.Rounded.Add, colors.success, "Create a server", "Start one for your squad", onCreate)
        ActionTile(Icons.AutoMirrored.Rounded.Login, colors.accent, "Join with an invite", "Paste a link or code from a friend", onJoin)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionTile(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, highlight = colors.textPrimary.copy(alpha = 0.06f), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = DlTheme.type.headline, color = colors.textPrimary)
            Text(subtitle, style = DlTheme.type.footnote, color = colors.textSecondary)
        }
    }
}

/** Sheet content: paste a link or code; the card appears as soon as it's recognised. */
@Composable
fun JoinWithInviteSheetContent(onDone: () -> Unit) {
    val colors = DlTheme.colors
    var input by rememberSaveable { mutableStateOf("") }
    val code = InviteLinks.parse(input)
    Column(Modifier.fillMaxWidth()) {
        Text("Join with an invite", style = DlTheme.type.title3, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(4.dp))
        Text("Paste the link or the 8-character code.", style = DlTheme.type.subheadline, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        DlTextField(input, { input = it.take(200) }, placeholder = "discordlite://invite/… or code", modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 170.dp).padding(bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
            if (code != null) {
                InviteLinkCard(code, onJoined = { onDone() })
            } else if (input.isNotBlank()) {
                Text("That doesn't look like an invite yet.", style = DlTheme.type.footnote, color = colors.textTertiary)
            }
        }
    }
}

// --- Making a link ---

private val ExpiryOptions = listOf("30 min", "1 hour", "1 day", "7 days", "Never")
private val ExpirySeconds = listOf<Long?>(1800, 3600, 86_400, 604_800, null)
private val UseOptions = listOf("No limit", "1", "5", "10", "25")
private val UseCounts = listOf<Int?>(null, 1, 5, 10, 25)

/**
 * Sheet content: a link for this server, made as the sheet opens (7 days, no limit). Changing
 * the expiry or uses makes a fresh link and deletes the one this sheet just made, if nobody used it.
 */
@Composable
fun InviteSheetContent(serverId: String) {
    val colors = DlTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val repo = LocalAppContainer.current.servers
    val server by remember(serverId) { repo.server(serverId) }.collectAsStateWithLifecycle(null)
    var expiry by rememberSaveable { mutableIntStateOf(3) }
    var uses by rememberSaveable { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(expiry, uses) {
        delay(250) // settle on a choice before making a link
        val previous = code
        runCatching { repo.createInvite(serverId, ExpirySeconds[expiry], UseCounts[uses]) }
            .onSuccess { made ->
                code = made.code
                if (previous != null) runCatching { repo.deleteInvite(previous) }
            }
            .onFailure {
                error = serverError(it)
                errorTick++
            }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    val link = code?.let(InviteLinks::link)
    val name = server?.name ?: "this server"

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Invite people to $name", style = DlTheme.type.title3, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("Anyone with this link can join the server.", style = DlTheme.type.subheadline, color = colors.textSecondary)
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (colors.isDark) colors.surfaceRaised else colors.bubbleOther)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(link ?: "Making a link…", style = DlTheme.type.callout, color = if (link != null) colors.textPrimary else colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .height(38.dp)
                    .pressable(enabled = link != null, pressedScale = 0.94f) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link))
                        haptics.confirm()
                        copied = true
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (copied) colors.success else colors.accent)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = { (scaleIn(DlMotion.pop(), initialScale = 0.6f) + fadeIn(DlMotion.snappy())) togetherWith fadeOut(DlMotion.snappy()) },
                    label = "copy",
                ) { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (done) "Copied" else "Copy", style = DlTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                    }
                }
            }
        }
        ErrorBanner(error, errorTick, Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(18.dp))
        Text("EXPIRE AFTER", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        SegmentedControl(ExpiryOptions, expiry, { expiry = it })
        Spacer(Modifier.height(14.dp))
        Text("MAX NUMBER OF USES", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        SegmentedControl(UseOptions, uses, { uses = it })

        Spacer(Modifier.height(18.dp))
        Text("PREVIEW", style = DlTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // What friends will see (the Join button does nothing here).
            InviteCard(InvitePreview(code.orEmpty(), name, online = -1, members = server?.memberCount ?: 0), joined = false, onJoin = {})
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Share invite link", enabled = link != null, onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Join $name on discord-lite: $link")
            }
            context.startActivity(Intent.createChooser(send, "Share invite"))
        })
    }
}
