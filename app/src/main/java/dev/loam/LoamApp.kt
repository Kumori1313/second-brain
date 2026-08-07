package dev.loam

import android.app.Application
import dev.loam.core.Loam
import dev.loam.llama.LlamaEngine

/**
 * Installs the pieces `:core` cannot construct for itself.
 *
 * `:llama` depends on `:core` for the `LlmEngine` interface, so `:core` cannot
 * refer to `LlamaEngine` without a dependency cycle. `:app` sits below both and
 * is the only place that can see them at once.
 *
 * Done in [onCreate] rather than lazily, so the factory is present before any
 * ViewModel exists. Installing it is cheap — it captures a lambda and loads
 * nothing; the model is opened on first use, and only if one has been picked.
 */
class LoamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Loam.get(this).engineFactory = { uri -> LlamaEngine.open(this, uri) }
    }
}
