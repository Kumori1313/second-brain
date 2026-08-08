package dev.loam

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.loam.core.Loam
import dev.loam.ui.IndexLocked
import dev.loam.ui.LoamScreen
import dev.loam.ui.SearchViewModel
import dev.loam.ui.unlockIndex

/**
 * A [FragmentActivity] rather than a ComponentActivity, because BiometricPrompt
 * needs one. That is the only reason.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
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
                    LoamScreen(viewModel = viewModel<SearchViewModel>())
                }
            }
        }
    }
}
