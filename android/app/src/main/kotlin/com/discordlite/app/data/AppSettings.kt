package com.discordlite.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Device-local preferences (not synced to the server). Each change is saved immediately. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.System })
        private set

    /** Tell friends when you've read their messages. Delivery receipts are always sent. */
    var readReceipts by mutableStateOf(prefs.getBoolean(KEY_READ_RECEIPTS, true))
        private set

    /** Show friends when you're typing (and see when they are). */
    var typingIndicators by mutableStateOf(prefs.getBoolean(KEY_TYPING, true))
        private set

    /** Require fingerprint / face / device PIN to open the app. */
    var appLock by mutableStateOf(prefs.getBoolean(KEY_APP_LOCK, false))
        private set

    /** Notifications say "New message" without the sender or text. */
    var hideNotificationContent by mutableStateOf(prefs.getBoolean(KEY_HIDE_NOTIFICATIONS, false))
        private set

    /** Blocks screenshots and screen recording, and blanks the app in Recents. On by default. */
    var blockScreenshots by mutableStateOf(prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, true))
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit { putInt(KEY_THEME, mode.ordinal) }
    }

    fun updateReadReceipts(on: Boolean) {
        readReceipts = on
        prefs.edit { putBoolean(KEY_READ_RECEIPTS, on) }
    }

    fun updateTypingIndicators(on: Boolean) {
        typingIndicators = on
        prefs.edit { putBoolean(KEY_TYPING, on) }
    }

    fun updateAppLock(on: Boolean) {
        appLock = on
        prefs.edit { putBoolean(KEY_APP_LOCK, on) }
    }

    fun updateHideNotificationContent(on: Boolean) {
        hideNotificationContent = on
        prefs.edit { putBoolean(KEY_HIDE_NOTIFICATIONS, on) }
    }

    fun updateBlockScreenshots(on: Boolean) {
        blockScreenshots = on
        prefs.edit { putBoolean(KEY_BLOCK_SCREENSHOTS, on) }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_READ_RECEIPTS = "read_receipts"
        const val KEY_TYPING = "typing_indicators"
        const val KEY_APP_LOCK = "app_lock"
        const val KEY_HIDE_NOTIFICATIONS = "hide_notification_content"
        const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
    }
}

val LocalAppSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings not provided") }
