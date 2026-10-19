package com.discordlite.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalDlColors = staticCompositionLocalOf { DarkColors }
val LocalDlTypography = staticCompositionLocalOf { DlTypography() }

/** True when the user turned animations off in system settings. Swap slides/springs for cross-fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object DlTheme {
    val colors: DlColors
        @Composable @ReadOnlyComposable get() = LocalDlColors.current
    val type: DlTypography
        @Composable @ReadOnlyComposable get() = LocalDlTypography.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun DlTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val type = remember { DlTypography() }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // Material components (text selection, progress indicators) pick up the brand colors too.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.danger,
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(bodyLarge = type.body, bodyMedium = type.callout, labelLarge = type.headline),
    ) {
        CompositionLocalProvider(
            LocalDlColors provides colors,
            LocalDlTypography provides type,
            LocalReducedMotion provides reducedMotion,
            content = content,
        )
    }
}
