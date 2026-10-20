package com.discordlite.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discordlite.app.data.AccountRepository
import com.discordlite.app.data.remote.ApiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Turns a server error code into words a person can act on. */
fun friendlyMessage(error: Throwable): String = when ((error as? ApiError)?.code) {
    "network" -> "Can't reach discord-lite. Check your connection and try again."
    "invalid_credentials" -> "That email and password don't match."
    "username_taken" -> "That username is taken. Try another."
    "invalid_email" -> "Enter a valid email address."
    "invalid_username" -> "Usernames use 3–20 lowercase letters, numbers, . or _"
    "invalid_password" -> "Passwords need at least 8 characters."
    "invalid_code" -> "That code isn't right. Check the email and try again."
    "code_expired" -> "That code has expired. Send a new one."
    "too_many_attempts" -> "Too many wrong tries. Send a new code."
    "resend_too_soon" -> "A code was just sent. Wait a few seconds."
    "rate_limited" -> "Too many attempts. Wait a minute and try again."
    else -> "Something went wrong. Try again."
}

/** Shared busy/error state. `errorTick` changes on every new error so the UI can shake again. */
abstract class FormViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        protected set
    var error by mutableStateOf<String?>(null)
        protected set
    var errorTick by mutableIntStateOf(0)
        private set

    protected fun run(onError: (Throwable) -> Boolean = { false }, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                if (!onError(e)) showError(friendlyMessage(e))
            } finally {
                busy = false
            }
        }
    }

    protected fun showError(message: String) {
        error = message
        errorTick++
    }

    fun clearError() {
        error = null
    }
}

class SignUpViewModel(private val account: AccountRepository) : FormViewModel() {
    var usernameTaken by mutableStateOf(false)
        private set

    fun submit(email: String, username: String, password: String, onCodeSent: (String) -> Unit) = run(
        onError = { e ->
            if ((e as? ApiError)?.code == "username_taken") {
                usernameTaken = true
                showError(friendlyMessage(e))
                true
            } else {
                false
            }
        },
    ) {
        usernameTaken = false
        account.signUp(email, username, password)
        onCodeSent(email.trim())
    }

    fun usernameEdited() {
        usernameTaken = false
    }
}

class VerifyEmailViewModel(private val account: AccountRepository) : FormViewModel() {
    var resendIn by mutableIntStateOf(30)
        private set
    var verified by mutableStateOf(false)
        private set
    private var countdown: Job? = null

    init {
        startCountdown()
    }

    fun verify(email: String, code: String, onVerified: () -> Unit) = run {
        account.verifyEmail(email, code)
        verified = true
        onVerified()
    }

    fun resend(email: String) {
        if (resendIn > 0) return
        run {
            account.resendCode(email)
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        resendIn = 30
        countdown = viewModelScope.launch {
            while (resendIn > 0) {
                delay(1000)
                resendIn--
            }
        }
    }
}

class SignInViewModel(private val account: AccountRepository) : FormViewModel() {
    fun submit(email: String, password: String, onSignedIn: () -> Unit, onNeedsVerification: (String) -> Unit) = run(
        onError = { e ->
            // The server has just emailed a fresh code, so go straight to the code screen.
            if ((e as? ApiError)?.code == "email_not_verified") {
                onNeedsVerification(email.trim())
                true
            } else {
                false
            }
        },
    ) {
        account.signIn(email, password)
        onSignedIn()
    }
}

class ResetPasswordViewModel(private val account: AccountRepository) : FormViewModel() {
    var codeSent by mutableStateOf(false)
        private set

    fun sendCode(email: String) = run {
        account.requestPasswordReset(email)
        codeSent = true
    }

    fun reset(email: String, code: String, newPassword: String, onDone: () -> Unit) = run {
        account.resetPassword(email, code, newPassword)
        onDone()
    }

    fun changeEmail() {
        codeSent = false
        clearError()
    }
}
