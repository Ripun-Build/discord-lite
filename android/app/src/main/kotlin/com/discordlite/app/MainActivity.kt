package com.discordlite.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.discordlite.app.data.LocalAppContainer
import com.discordlite.app.data.LocalAppSettings
import com.discordlite.app.data.ThemeMode
import com.discordlite.app.navigation.AppNavHost
import com.discordlite.designsystem.theme.DlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DiscordLiteApp).container
        val settings = container.settings
        setContent {
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status/navigation bar icons follow the app's theme, not just the system's.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppSettings provides settings, LocalAppContainer provides container) {
                DlTheme(darkTheme = dark) {
                    AppNavHost()
                }
            }
        }
    }
}
