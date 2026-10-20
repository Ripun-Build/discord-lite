package com.discordlite.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import com.discordlite.designsystem.component.pressable
import com.discordlite.app.ui.common.ErrorBanner
import com.discordlite.app.data.LocalAppContainer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discordlite.app.ui.common.entrance
import com.discordlite.designsystem.component.DlTextField
import com.discordlite.designsystem.component.IconCircleButton
import com.discordlite.designsystem.component.PrimaryButton
import com.discordlite.designsystem.component.TextAction
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxSize().background(colors.background)) {
        AuroraBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            AppMark(Modifier.entrance(0))
            Spacer(Modifier.height(28.dp))
            Text("discord-lite", style = DlTheme.type.largeTitle, color = colors.textPrimary, modifier = Modifier.entrance(1))
            Spacer(Modifier.height(10.dp))
            Text(
                "Chat, squads and clips.\nPrivate by default.",
                style = DlTheme.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.entrance(2),
            )
            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Feature(Icons.Rounded.Lock, "End-to-end encrypted DMs", "Only you and your friends can read them.", Modifier.entrance(3))
                Feature(Icons.Rounded.Groups, "Servers & invite links", "Channels, roles and one-tap invites.", Modifier.entrance(4))
                Feature(Icons.Rounded.PlayCircle, "Stories, posts & reels", "Share your best gaming moments.", Modifier.entrance(5))
            }
            Spacer(Modifier.weight(1.2f))
            PrimaryButton("Create account", onClick = onCreateAccount, modifier = Modifier.entrance(6))
            Spacer(Modifier.height(14.dp))
            TextAction("I already have an account", onClick = onSignIn, modifier = Modifier.padding(vertical = 8.dp).entrance(7))
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Static colour blooms behind the welcome screen — no looping motion in the background. */
@Composable
private fun AuroraBackdrop() {
    val colors = DlTheme.colors
    val strength = if (colors.isDark) 1f else 0.55f
    Canvas(Modifier.fillMaxSize()) {
        fun bloom(color: Color, x: Float, y: Float, r: Float, a: Float) {
            val c = Offset(size.width * x, size.height * y)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a * strength), Color.Transparent), c, size.width * r), size.width * r, c)
        }
        bloom(colors.accentGradient[0], 0.15f, 0.12f, 0.85f, 0.55f)
        bloom(colors.accentGradient[2], 0.95f, 0.22f, 0.7f, 0.42f)
        bloom(colors.accentGradient[1], 0.5f, 0.45f, 0.75f, 0.28f)
    }
}

@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    Box(
        modifier
            .size(96.dp)
            .shadow(32.dp, RoundedCornerShape(30.dp), ambientColor = colors.accentGradient[1], spotColor = colors.accentGradient[1])
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(colors.accentGradient)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Forum, null, tint = Color.White, modifier = Modifier.size(50.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = DlTheme.type.headline, color = colors.textPrimary)
            Text(subtitle, style = DlTheme.type.subheadline, color = colors.textSecondary)
        }
    }
}

/** Shared frame for the auth forms: back chevron, big title, subtitle, scrolling fields. */
@Composable
private fun AuthFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DlTheme.colors
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            IconCircleButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", onBack, size = 40.dp)
            Spacer(Modifier.height(24.dp))
            Text(title, style = DlTheme.type.title1, color = colors.textPrimary, modifier = Modifier.entrance(0, baseDelayMs = 0))
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = DlTheme.type.body, color = colors.textSecondary, modifier = Modifier.entrance(1, baseDelayMs = 0))
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val EmailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
private val UsernameRegex = Regex("^[a-z0-9._]{3,20}$")

@Composable
fun SignUpScreen(onBack: () -> Unit, onCodeSent: (email: String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SignUpViewModel = viewModel { SignUpViewModel(container.account) }
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val emailValid = EmailRegex.matches(email)
    val usernameValid = UsernameRegex.matches(username)
    val passwordValid = password.length >= 8
    val emailError = if (email.length > 4 && !emailValid && email.contains('@')) "Enter a valid email address" else null
    val usernameError = when {
        vm.usernameTaken -> "@$username is already taken"
        username.isEmpty() -> null
        username.any { !(it.isLowerCase() || it.isDigit() || it == '.' || it == '_') } -> "Use lowercase letters, numbers, . and _"
        username.length < 3 -> "At least 3 characters"
        else -> null
    }

    AuthFrame("Create your account", "Sign up with your email. You can change your display name any time.", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DlTextField(
                value = email, onValueChange = { email = it.trim(); vm.clearError() }, placeholder = "you@example.com", label = "Email",
                leadingIcon = Icons.Rounded.Mail, error = emailError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.entrance(2, baseDelayMs = 0),
            )
            DlTextField(
                value = username,
                onValueChange = { username = it.lowercase().take(20); vm.usernameEdited() },
                placeholder = "username", label = "Username",
                leadingIcon = Icons.Rounded.AlternateEmail, error = usernameError,
                hint = if (usernameValid) "Friends will find you as @$username" else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.entrance(3, baseDelayMs = 0),
            )
            Column(Modifier.entrance(4, baseDelayMs = 0)) {
                DlTextField(
                    value = password, onValueChange = { password = it }, placeholder = "At least 8 characters", label = "Password",
                    leadingIcon = Icons.Rounded.Key, password = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                StrengthMeter(password)
            }
        }
        Spacer(Modifier.height(16.dp))
        ErrorBanner(vm.error.takeUnless { vm.usernameTaken }, vm.errorTick)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = "Continue",
            enabled = emailValid && usernameValid && passwordValid && !vm.usernameTaken,
            loading = vm.busy,
            onClick = { vm.submit(email, username, password, onCodeSent) },
            modifier = Modifier.entrance(5, baseDelayMs = 0),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "By continuing you agree to the Terms and Privacy Policy.",
            style = DlTheme.type.footnote,
            color = DlTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Four-segment strength bar; each segment's colour springs as the password improves. */
@Composable
private fun StrengthMeter(password: String) {
    val colors = DlTheme.colors
    val score = listOf(
        password.length >= 8,
        password.any { it.isUpperCase() } && password.any { it.isLowerCase() },
        password.any { it.isDigit() },
        password.any { !it.isLetterOrDigit() } || password.length >= 14,
    ).count { it }.let { if (password.isEmpty()) 0 else it.coerceAtLeast(1) }
    val tone = when (score) {
        0 -> colors.separator
        1 -> colors.danger
        2 -> Color(0xFFFF9F0A)
        3 -> Color(0xFFFFD60A)
        else -> colors.success
    }
    val label = listOf("", "Weak", "Okay", "Good", "Strong")[score]
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                val c by animateColorAsState(if (i < score) tone else colors.separator, DlMotion.snappy(), label = "seg$i")
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = DlTheme.type.footnote, color = tone, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun VerifyEmailScreen(email: String, onBack: () -> Unit, onVerified: () -> Unit) {
    val colors = DlTheme.colors
    val haptics = rememberHaptics()
    val container = LocalAppContainer.current
    val vm: VerifyEmailViewModel = viewModel { VerifyEmailViewModel(container.account) }
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Six digits typed: check it straight away, no extra button.
    LaunchedEffect(code) {
        if (code.length == 6) {
            haptics.tick()
            vm.verify(email, code) {
                haptics.confirm()
                onVerified()
            }
        }
    }
    // A wrong code clears the boxes so they can type again.
    LaunchedEffect(vm.errorTick) { if (vm.errorTick > 0) code = "" }

    AuthFrame("Check your inbox", "Enter the 6-digit code we sent to $email", onBack) {
        Box(Modifier.entrance(2, baseDelayMs = 0)) {
            BasicTextField(
                value = code,
                onValueChange = { new -> if (!vm.busy) { code = new.filter { it.isDigit() }.take(6); vm.clearError() } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions.Default,
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = DlTheme.type.body.copy(color = Color.Transparent),
                modifier = Modifier.matchParentSize().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { i -> CodeBox(digit = code.getOrNull(i), active = i == code.length && !vm.busy, modifier = Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        ErrorBanner(vm.error, vm.errorTick)
        Spacer(Modifier.height(12.dp))
        AnimatedContent(targetState = vm.busy, label = "verifying") { busy ->
            if (busy) {
                PrimaryButton("Verifying", onClick = {}, loading = true)
            } else {
                val ready = vm.resendIn == 0
                Text(
                    if (ready) "Resend code" else "Resend code in 0:${"%02d".format(vm.resendIn)}",
                    style = DlTheme.type.callout,
                    color = if (ready) colors.accent else colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(enabled = ready, pressedScale = 0.96f) { vm.resend(email) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Can't find it? Check your spam folder.",
            style = DlTheme.type.footnote,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CodeBox(digit: Char?, active: Boolean, modifier: Modifier = Modifier) {
    val colors = DlTheme.colors
    val border by animateColorAsState(if (active) colors.accent else colors.separator, DlMotion.snappy(), label = "codeBorder")
    val lift by animateFloatAsState(if (active) 1.04f else 1f, DlMotion.snappy(), label = "codeLift")
    Box(
        modifier
            .height(58.dp)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(14.dp))
            .background(if (colors.isDark) colors.surfaceRaised else colors.surface)
            .border(if (active) 1.5.dp else 1.dp, border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = { (scaleIn(DlMotion.pop(), initialScale = 0.4f) + fadeIn(DlMotion.snappy())) togetherWith fadeOut(DlMotion.snappy()) },
            label = "digit",
        ) { d ->
            Text(d?.toString() ?: "", style = DlTheme.type.title2, color = colors.textPrimary)
        }
    }
}

@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    onNeedsVerification: (email: String) -> Unit,
    onForgotPassword: (email: String) -> Unit,
    notice: String? = null,
) {
    val container = LocalAppContainer.current
    val vm: SignInViewModel = viewModel { SignInViewModel(container.account) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val valid = EmailRegex.matches(email) && password.isNotEmpty()

    AuthFrame("Welcome back", notice ?: "Sign in with the email you used to sign up.", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DlTextField(
                value = email, onValueChange = { email = it.trim(); vm.clearError() }, placeholder = "you@example.com", label = "Email",
                leadingIcon = Icons.Rounded.Mail,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.entrance(2, baseDelayMs = 0),
            )
            DlTextField(
                value = password, onValueChange = { password = it; vm.clearError() }, placeholder = "Password", label = "Password",
                leadingIcon = Icons.Rounded.Key, password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.entrance(3, baseDelayMs = 0),
            )
        }
        Spacer(Modifier.height(12.dp))
        TextAction("Forgot password?", onClick = { onForgotPassword(email) }, modifier = Modifier.align(Alignment.End).padding(vertical = 6.dp))
        Spacer(Modifier.height(12.dp))
        ErrorBanner(vm.error, vm.errorTick)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = "Sign in",
            enabled = valid,
            loading = vm.busy,
            onClick = { vm.submit(email, password, onSignedIn, onNeedsVerification) },
            modifier = Modifier.entrance(4, baseDelayMs = 0),
        )
    }
}

/**
 * Two steps on one screen: send a code to your email, then enter it with a new password. The
 * second step slides in from the right and slides back out the same way if you change the email.
 */
@Composable
fun ResetPasswordScreen(initialEmail: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: ResetPasswordViewModel = viewModel { ResetPasswordViewModel(container.account) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AuthFrame(
        title = if (vm.codeSent) "Choose a new password" else "Reset your password",
        subtitle = if (vm.codeSent) "Enter the code we sent to $email and a new password." else "We'll email you a 6-digit code.",
        onBack = onBack,
    ) {
        AnimatedContent(
            targetState = vm.codeSent,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (slideInHorizontally(DlMotion.smooth(IntOffset.VisibilityThreshold)) { it * dir } + fadeIn(DlMotion.smooth())) togetherWith
                    (slideOutHorizontally(DlMotion.smooth(IntOffset.VisibilityThreshold)) { -it * dir } + fadeOut(DlMotion.smooth()))
            },
            label = "resetStep",
        ) { codeStep ->
            Column {
                if (!codeStep) {
                    DlTextField(
                        value = email, onValueChange = { email = it.trim(); vm.clearError() }, placeholder = "you@example.com", label = "Email",
                        leadingIcon = Icons.Rounded.Mail,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    )
                    Spacer(Modifier.height(16.dp))
                    ErrorBanner(vm.error, vm.errorTick)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Send code", onClick = { vm.sendCode(email) }, enabled = EmailRegex.matches(email), loading = vm.busy)
                } else {
                    DlTextField(
                        value = code, onValueChange = { new -> code = new.filter { it.isDigit() }.take(6); vm.clearError() },
                        placeholder = "6-digit code", label = "Code", leadingIcon = Icons.Rounded.Pin,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(16.dp))
                    DlTextField(
                        value = password, onValueChange = { password = it; vm.clearError() }, placeholder = "At least 8 characters",
                        label = "New password", leadingIcon = Icons.Rounded.Key, password = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                    StrengthMeter(password)
                    Spacer(Modifier.height(16.dp))
                    ErrorBanner(vm.error, vm.errorTick)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        "Reset password",
                        onClick = { vm.reset(email, code, password, onDone) },
                        enabled = code.length == 6 && password.length >= 8,
                        loading = vm.busy,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextAction(
                        "Use a different email",
                        onClick = { vm.changeEmail(); code = "" },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}
