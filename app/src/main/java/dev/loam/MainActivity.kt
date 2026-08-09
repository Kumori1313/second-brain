package dev.loam

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.loam.core.Loam
import dev.loam.core.domain.ThemeMode
import dev.loam.ui.IndexLocked
import dev.loam.ui.LoamScreen
import dev.loam.ui.SearchViewModel
import dev.loam.ui.theme.LoamTheme
import dev.loam.ui.SharedQuery
import dev.loam.ui.unlockIndex

/**
 * A [FragmentActivity] rather than a ComponentActivity, because BiometricPrompt
 * needs one. That is the only reason.
 */
class MainActivity : FragmentActivity() {

    /**
     * Text handed over by another app, waiting to become a query.
     *
     * Held as state rather than acted on directly because the ViewModel does
     * not exist yet at [onCreate], and because a share can arrive at
     * [onNewIntent] long after the screen is composed.
     */
    private var shared by mutableStateOf<String?>(null)

    /** Set when the home-screen widget launched us. See [dev.loam.widget.SearchWidget]. */
    private var focusSearch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shared = SharedQuery.from(intent)
        focusSearch = intent.getBooleanExtra(EXTRA_FOCUS_SEARCH, false)
        enableEdgeToEdge()
        paintWindowBackground()
        setContent {
            // Read once here rather than inside the theme: the locked branch
            // below must not construct the ViewModel, since everything it
            // touches on init needs the index open.
            val stored = remember { Loam.get(this).settings.appearance }
            LoamTheme(appearance = stored) {
                // Gate the whole app rather than showing a dialog over it: with
                // the key locked nothing behind this can load — not the note
                // count, not a search, not the model — so the screen underneath
                // would be empty and half-broken.
                var locked by remember { mutableStateOf(Loam.get(this).isIndexLocked) }
                var error by remember { mutableStateOf<String?>(null) }

                if (locked) {
                    IndexLocked(error = error) {
                        unlockIndex(this) { failure ->
                            error = failure?.message
                            locked = Loam.get(this).isIndexLocked
                        }
                    }
                } else {
                    val model = viewModel<SearchViewModel>()
                    val state by model.state.collectAsStateWithLifecycle()
                    // Delivered here rather than read from `intent` inside the
                    // composable: with singleTask a share arrives at
                    // onNewIntent, which no recomposition would notice.
                    LaunchedEffect(shared) {
                        shared?.let { model.onSharedQuery(it) }
                        shared = null
                    }
                    LaunchedEffect(focusSearch) {
                        if (focusSearch) model.onFocusSearch()
                        focusSearch = false
                    }
                    // Nested rather than hoisted for the same reason: only
                    // this branch has a ViewModel to read a live setting from.
                    // Cheap — the outer theme resolves to the same scheme until
                    // the moment the setting changes.
                    LoamTheme(appearance = state.appearance) {
                        LoamScreen(viewModel = model)
                    }
                }
            }
        }
    }

    /**
     * Where a share lands while Loam is already open, which `singleTask` makes
     * the normal case rather than the exception.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedQuery.from(intent)?.let { shared = it }
        if (intent.getBooleanExtra(EXTRA_FOCUS_SEARCH, false)) focusSearch = true
    }

    /**
     * Paints the window before Compose draws, so launching does not flash the
     * wrong colour for a frame or two.
     *
     * `values-night` handles this for the common case, but a resource
     * qualifier can only see the *system* setting. Someone running the app
     * dark on a light phone would still get the flash, so the override is
     * applied here where the preference is readable.
     */
    private fun paintWindowBackground() {
        val appearance = Loam.get(this).settings.appearance
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (appearance.mode == ThemeMode.SYSTEM) return // values-night already agrees
        val dark = appearance.mode.isDark(systemDark)
        if (dark == systemDark) return
        window.setBackgroundDrawable(
            ColorDrawable(if (dark) WINDOW_DARK else WINDOW_LIGHT)
        )
    }

    companion object {
        /** Widget → "open Search with the field ready", not merely "open". */
        const val EXTRA_FOCUS_SEARCH = "dev.loam.FOCUS_SEARCH"

        // Only ever seen for the frame before Compose draws, so these match
        // the Material baseline surfaces rather than the resolved scheme —
        // which is not available this early, dynamic colour least of all.
        private const val WINDOW_LIGHT = 0xFFFFFBFE.toInt()
        private const val WINDOW_DARK = 0xFF1C1B1F.toInt()
    }
}
