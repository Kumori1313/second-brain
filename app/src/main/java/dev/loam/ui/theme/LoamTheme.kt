package dev.loam.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.loam.core.domain.Appearance

/**
 * The app's colours, resolved from the user's [Appearance] preference.
 *
 * Before this the app was hard light-only — `MaterialTheme { }` with no scheme
 * falls back to `lightColorScheme()` no matter what the phone is doing — so a
 * device in dark mode got a white rectangle, which for a notes app you reach
 * for at night is a usability problem rather than a cosmetic one.
 *
 * Dynamic colour is Material You, and despite the branding it is not a Google
 * dependency: [dynamicDarkColorScheme] reads `android.R.color.system_accent1_*`
 * straight from the platform. No Play Services, nothing principle #3 excludes.
 * It does need API 31 against a `minSdk` of 26, so the static schemes below are
 * a required fallback rather than a nicety.
 */
@Composable
fun LoamTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    val context = LocalContext.current

    val scheme = when {
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // The status bar draws over the app because the activity is edge-to-edge,
    // so nothing else adjusts its icons. Left alone, dark grey icons sit on a
    // dark background and the clock disappears.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
