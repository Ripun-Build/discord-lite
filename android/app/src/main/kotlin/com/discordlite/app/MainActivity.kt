package com.discordlite.app

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.LocalAppSettings
import com.discordlite.app.data.ThemeMode
import com.discordlite.app.navigation.AppNavHost
import com.discordlite.app.security.AppLock
import com.discordlite.app.security.LockScreen
import com.discordlite.designsystem.theme.DlTheme

/** A FragmentActivity because the system fingerprint/PIN prompt needs one. */
class MainActivity : FragmentActivity() {
    private val container by lazy { (application as DiscordLiteApp).container }

    /** Locked on a cold start (if app lock is on) and after being away longer than [LOCK_GRACE_MS]. */
    private var locked by mutableStateOf(false)
    private var leftAt = 0L
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = container.settings
        locked = settings.appLock && savedInstanceState == null

        setContent {
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status/navigation bar icons follow the app's theme, not just the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // No screenshots or screen recording, and a blank card in Recents.
            DisposableEffect(settings.blockScreenshots) {
                if (settings.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppSettings provides settings, LocalAppContainer provides container) {
                DlTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize()) {
                        AppNavHost()
                        if (locked && settings.appLock) LockScreen(onUnlock = ::unlock)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val away = SystemClock.elapsedRealtime() - leftAt
        if (container.settings.appLock && leftAt != 0L && away > LOCK_GRACE_MS) locked = true
    }

    override fun onStop() {
        super.onStop()
        if (!prompting) leftAt = SystemClock.elapsedRealtime()
    }

    private fun unlock() {
        // If the phone's screen lock was removed, app lock can't be enforced: turn it off.
        if (!AppLock.isAvailable(this)) {
            container.settings.updateAppLock(false)
            locked = false
            return
        }
        if (prompting) return
        prompting = true
        AppLock.authenticate(
            this,
            title = "Unlock discord-lite",
            onSuccess = {
                prompting = false
                locked = false
            },
            onFailure = { prompting = false },
        )
    }

    private companion object {
        /** Quick app switches (e.g. copying a code) don't re-lock. */
        const val LOCK_GRACE_MS = 15_000L
    }
}
