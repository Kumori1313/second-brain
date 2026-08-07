package dev.loam

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.loam.core.Loam
import dev.loam.llama.LlamaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Installs the pieces `:core` cannot construct for itself, and owns the LLM's
 * memory footprint.
 *
 * `:llama` depends on `:core` for the `LlmEngine` interface, so `:core` cannot
 * refer to `LlamaEngine` without a dependency cycle. `:app` sits below both and
 * is the only place that can see them at once.
 */
class LoamApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val loam = Loam.get(this)

        // Cheap: captures a lambda and loads nothing. The model is opened on
        // first use, and only if one has been picked.
        loam.engineFactory = { uri -> LlamaEngine.open(this, uri) }

        // Release the model whenever the app leaves the foreground.
        //
        // Measured on a Pixel 8a: a loaded model puts the process at ~2.1 GB
        // PSS, of which ~849 MB is the GGUF mapping. Those pages are private
        // *clean*, so the kernel can drop them rather than OOM — but Android's
        // low-memory killer scores on PSS, so holding the model in the
        // background makes Loam one of the first things reaped, and it takes
        // the warm search index with it. Search is the feature used most and
        // the one that should survive a trip to another app.
        //
        // Reopening costs ~1 s, because mmap maps the weights rather than
        // reading them. That is a good trade against being killed.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    scope.launch { loam.closeEngine() }
                }
            }
        )
    }
}
