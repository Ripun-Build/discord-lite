package com.discordlite.app.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.discordlite.app.data.LocalAppSettings
import com.discordlite.app.data.ThemeMode
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.app.security.findFragmentActivity
import com.discordlite.app.security.AppLock
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.DoneAll
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.discordlite.app.data.remote.DmPolicy
import com.discordlite.app.data.LocalAppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discordlite.app.data.SampleData
import com.discordlite.app.ui.common.ArtPlaceholder
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlSwitch
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.SecondaryButton
import com.discordlite.designsystem.component.SectionCard
import com.discordlite.designsystem.component.SegmentedControl
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.component.pressable
import com.discordlite.designsystem.component.tabBarClearance
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics

@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, onEditProfile: () -> Unit) {
    val colors = DlTheme.colors
    val container = LocalAppContainer.current
    val account by container.account.me.collectAsStateWithLifecycle()
    val friends by container.social.friends.collectAsStateWithLifecycle()
    val displayName = account?.displayName ?: SampleData.me.name
    val username = account?.username ?: SampleData.me.username
    val bio = account?.bio.orEmpty()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(bottom = tabBarClearance()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Box(Modifier.fillMaxWidth().height(190.dp)) {
                    Box(Modifier.matchParentSize().background(Brush.linearGradient(colors.accentGradient)))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to colors.background)))
                    Row(Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeToggle()
                        IconCircleButton(Icons.Rounded.Share, "Share profile", {}, size = 38.dp)
                        IconCircleButton(Icons.Rounded.Settings, "Settings and privacy", onOpenSettings, size = 38.dp)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 18.dp)
                            .offset(y = 44.dp)
                            // A background (not a border) so the ring sits behind the presence dot.
                            .background(colors.background, CircleShape)
                            .padding(4.dp),
                    ) { Avatar(displayName, 92.dp, online = true) }
                }
                Spacer(Modifier.height(52.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(displayName, style = DlTheme.type.title1, color = colors.textPrimary)
                    Text("@$username", style = DlTheme.type.callout, color = colors.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        bio.ifBlank { "Add a bio so friends know what you play." },
                        style = DlTheme.type.callout,
                        color = if (bio.isBlank()) colors.textTertiary else colors.textPrimary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Stat("24", "Posts", Modifier.weight(1f))
                        Stat(friends.friends.size.toString(), "Friends", Modifier.weight(1f))
                        Stat("1.2K", "Followers", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(18.dp))
                    SecondaryButton("Edit profile", onClick = onEditProfile, modifier = Modifier.fillMaxWidth(), height = 44.dp)
                    Spacer(Modifier.height(18.dp))
                    SegmentedControl(listOf("Posts", "Reels", "Tagged"), tab, { tab = it })
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        itemsIndexed(SampleData.myPosts) { i, game ->
            Box(Modifier.aspectRatio(if (tab == 1) 0.62f else 1f).pressable(pressedScale = 0.96f) {}) {
                ArtPlaceholder("me-$tab-$i-$game", Modifier.fillMaxSize(), showIcon = false)
                if (tab == 1) {
                    Row(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("%.1fK".format((i + 3) * 1.3f), style = DlTheme.type.caption, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.pressable(pressedScale = 0.95f) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = DlTheme.type.title3, color = DlTheme.colors.textPrimary)
        Text(label, style = DlTheme.type.footnote, color = DlTheme.colors.textSecondary)
    }
}

private val DmOptions = listOf(
    "Everyone" to "Anyone on discord-lite",
    "Friends & server members" to "People you share a server with",
    "Friends only" to "Only people you've added",
    "Nobody" to "Turn off new DMs",
)

/** Settings & privacy. UI only for now — values are wired to the server in Phases 1–2. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    val colors = DlTheme.colors
    val settings = LocalAppSettings.current
    val haptics = rememberHaptics()
    val container = LocalAppContainer.current
    val me by container.account.me.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Shown immediately on tap; saved to the server in the background (reverts if that fails).
    var dmSetting by rememberSaveable(me?.dmPolicy) { mutableIntStateOf(me?.dmPolicy?.ordinal ?: 1) }
    val appSettings = LocalAppSettings.current
    val context = LocalContext.current
    var lockError by rememberSaveable { mutableStateOf<String?>(null) }

    // Turning app lock on or off needs the phone's own unlock first.
    fun toggleAppLock(on: Boolean) {
        val activity = context.findFragmentActivity() ?: return
        if (!AppLock.isAvailable(context)) {
            lockError = "Set a screen lock (PIN, pattern or fingerprint) in your phone's settings first."
            return
        }
        lockError = null
        AppLock.authenticate(activity, title = if (on) "Turn on app lock" else "Turn off app lock", onSuccess = {
            appSettings.updateAppLock(on)
            haptics.confirm()
        })
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp

    GlassScaffold(
        overlay = {
            DetailTopBar(onBack = onBack, title = {
                Text("Settings & privacy", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            })
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = 40.dp)) {
            item { SectionHeader("Appearance") }
            item {
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("Theme", style = DlTheme.type.body, color = colors.textPrimary)
                        Spacer(Modifier.height(10.dp))
                        SegmentedControl(
                            options = ThemeMode.entries.map { it.label },
                            selected = settings.themeMode.ordinal,
                            onSelect = { settings.updateThemeMode(ThemeMode.entries[it]) },
                        )
                    }
                }
            }
            item { SectionFooter("System follows your phone's dark theme setting.") }

            item { SectionHeader("Who can send you DMs") }
            item {
                SectionCard {
                    Column {
                        DmOptions.forEachIndexed { i, (title, subtitle) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f)) {
                                        if (dmSetting != i) {
                                            haptics.selection()
                                            val previous = dmSetting
                                            dmSetting = i
                                            scope.launch {
                                                runCatching { container.account.updateProfile(dmPolicy = DmPolicy.entries[i]) }
                                                    .onFailure { dmSetting = previous }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = DlTheme.type.body, color = colors.textPrimary)
                                    Text(subtitle, style = DlTheme.type.footnote, color = colors.textSecondary)
                                }
                                AnimatedVisibility(
                                    visible = dmSetting == i,
                                    enter = scaleIn(DlMotion.pop(), initialScale = 0.3f) + fadeIn(DlMotion.snappy()),
                                    exit = scaleOut(DlMotion.snappy(), targetScale = 0.3f) + fadeOut(DlMotion.snappy()),
                                ) { Icon(Icons.Rounded.Check, "Selected", tint = colors.accent) }
                            }
                            if (i < DmOptions.lastIndex) Divider()
                        }
                    }
                }
            }
            item { SectionFooter("People who can't DM you can still send a friend request.") }

            item { SectionHeader("Security") }
            item {
                SectionCard {
                    Column {
                        ToggleRow(Icons.Rounded.Fingerprint, Color(0xFF30B0C7), "App lock", "Fingerprint, face or PIN to open the app", appSettings.appLock) { toggleAppLock(it) }
                        Divider(inset = 60.dp)
                        ToggleRow(Icons.Rounded.NotificationsOff, Color(0xFFFF9F0A), "Hide notification content", "Show \"New message\" only", appSettings.hideNotificationContent) { appSettings.updateHideNotificationContent(it) }
                        Divider(inset = 60.dp)
                        ToggleRow(Icons.Rounded.ScreenshotMonitor, Color(0xFF5E5CE6), "Block screenshots", "Also hides the app in Recents", appSettings.blockScreenshots) { appSettings.updateBlockScreenshots(it) }
                    }
                }
            }
            item { ErrorBanner(lockError, if (lockError != null) 1 else 0, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            item { SectionHeader("Chats") }
            item {
                SectionCard {
                    Column {
                        ToggleRow(Icons.Rounded.DoneAll, Color(0xFF34C759), "Read receipts", "Let friends see when you've read their messages", appSettings.readReceipts) { appSettings.updateReadReceipts(it) }
                        Divider(inset = 60.dp)
                        ToggleRow(Icons.Rounded.MoreHoriz, Color(0xFF0A84FF), "Typing indicators", "Show when you're typing, and see when friends are", appSettings.typingIndicators) { appSettings.updateTypingIndicators(it) }
                    }
                }
            }
            item { SectionFooter("Delivery receipts are always sent so friends know a message reached you. Receipts and typing are end-to-end encrypted too.") }

            item { SectionHeader("Encryption") }
            item {
                SectionCard {
                    Column {
                        NavRow(Icons.Rounded.Lock, colors.success, "Your security code", "DMs and group chats are end-to-end encrypted")
                    }
                }
            }

            item { SectionHeader("Account") }
            item {
                SectionCard {
                    Column {
                        NavRow(Icons.Rounded.Mail, Color(0xFF0A84FF), "Email", me?.email ?: "—")
                        Divider(inset = 60.dp)
                        NavRow(Icons.Rounded.Key, Color(0xFF8E8E93), "Change password", null)
                        Divider(inset = 60.dp)
                        NavRow(Icons.Rounded.Flag, Color(0xFFFF453A), "Reports you've made", null)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                SectionCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(pressedScale = 0.99f, highlight = colors.danger.copy(alpha = 0.08f), onClick = onSignOut)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = colors.danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign out", style = DlTheme.type.headline, color = colors.danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = DlTheme.type.micro,
        color = DlTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(text, style = DlTheme.type.footnote, color = DlTheme.colors.textTertiary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(DlTheme.colors.separator))
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, tint: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = DlTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = DlTheme.type.body, color = colors.textPrimary)
            Text(subtitle, style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        Spacer(Modifier.width(10.dp))
        DlSwitch(checked, onChange)
    }
}

@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, subtitle: String?) {
    val colors = DlTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, highlight = colors.textPrimary.copy(alpha = 0.06f)) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = DlTheme.type.body, color = colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = DlTheme.type.footnote, color = colors.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.textTertiary)
    }
}

/**
 * Quick light/dark switch. The sun and moon swap with a spin and a pop from the button's centre,
 * and the whole app eases into the new palette.
 */
@Composable
private fun ThemeToggle() {
    val settings = LocalAppSettings.current
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val dark = colors.isDark
    val spin by animateFloatAsState(if (dark) 0f else 180f, DlMotion.spring(0.7f, 0.55f), label = "spin")
    Box(
        Modifier
            .size(38.dp)
            .pressable(pressedScale = 0.88f) {
                haptics.confirm()
                settings.updateThemeMode(if (dark) ThemeMode.Light else ThemeMode.Dark)
            }
            .clip(CircleShape)
            .background(if (dark) colors.surfaceRaised.copy(alpha = 0.9f) else colors.bubbleOther)
            .semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = dark,
            transitionSpec = {
                (scaleIn(DlMotion.pop(), initialScale = 0.4f) + fadeIn(DlMotion.snappy())) togetherWith
                    (scaleOut(DlMotion.snappy(), targetScale = 0.4f) + fadeOut(DlMotion.snappy()))
            },
            label = "themeIcon",
        ) { isDark ->
            Icon(
                if (isDark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                contentDescription = null,
                tint = if (isDark) Color(0xFFB9B4FF) else Color(0xFFFF9F0A),
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = spin },
            )
        }
    }
}
