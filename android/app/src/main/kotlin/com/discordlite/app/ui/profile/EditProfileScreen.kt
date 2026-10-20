package com.discordlite.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.ui.auth.friendlyMessage
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.designsystem.component.Avatar
import com.discordlite.designsystem.component.DetailTopBar
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.GlassScaffold
import com.discordlite.designsystem.component.TextAction
import com.discordlite.designsystem.component.TopBarHeight
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch

/** Display name and bio. The avatar's initials update live as you type. */
@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val me by container.account.me.collectAsStateWithLifecycle()
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var displayName by rememberSaveable(me?.id) { mutableStateOf(me?.displayName.orEmpty()) }
    var bio by rememberSaveable(me?.id) { mutableStateOf(me?.bio.orEmpty()) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorTick by rememberSaveable { mutableIntStateOf(0) }
    val changed = me != null && (displayName.trim() != me?.displayName || bio.trim() != me?.bio)
    val valid = displayName.trim().length in 1..32 && bio.length <= 160
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 20.dp

    fun save() {
        saving = true
        error = null
        scope.launch {
            runCatching { container.account.updateProfile(displayName = displayName.trim(), bio = bio.trim()) }
                .onSuccess {
                    haptics.confirm()
                    onBack()
                }
                .onFailure {
                    error = friendlyMessage(it)
                    errorTick++
                }
            saving = false
        }
    }

    GlassScaffold(
        modifier = Modifier.imePadding(),
        overlay = {
            DetailTopBar(
                onBack = onBack,
                title = {
                    Text("Edit profile", style = DlTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                },
                actions = {
                    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.padding(4.dp).width(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextAction(
                                "Save",
                                onClick = { if (changed && valid) save() },
                                color = if (changed && valid) colors.accent else colors.textTertiary,
                            )
                        }
                    }
                },
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = top, start = 20.dp, end = 20.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(displayName.ifBlank { me?.username.orEmpty() }, 96.dp)
            Text("@${me?.username.orEmpty()}", style = DlTheme.type.callout, color = colors.textSecondary)
            Spacer(Modifier.height(4.dp))
            DlTextField(
                value = displayName,
                onValueChange = { displayName = it.take(32) },
                placeholder = "Your name",
                label = "Display name",
                leadingIcon = Icons.Rounded.Badge,
                hint = "${displayName.length}/32 · Shown to friends and in servers",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            DlTextField(
                value = bio,
                onValueChange = { bio = it.take(160) },
                placeholder = "Games you play, your main, anything",
                label = "Bio",
                singleLine = false,
                minLines = 3,
                hint = "${bio.length}/160",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            DlTextField(
                value = me?.username.orEmpty(),
                onValueChange = {},
                placeholder = "",
                label = "Username",
                leadingIcon = Icons.Rounded.AlternateEmail,
                readOnly = true,
                hint = "Usernames can't be changed yet.",
            )
            ErrorBanner(error, errorTick, Modifier.fillMaxWidth())
        }
    }
}
