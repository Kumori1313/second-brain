package dev.loam.core

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.loam.core.domain.AskQuestion
import dev.loam.core.domain.IndexStats
import dev.loam.core.domain.IndexVault
import dev.loam.core.domain.ModelLocation
import dev.loam.core.domain.SearchNotes
import dev.loam.core.domain.Settings
import dev.loam.core.domain.VaultLocation
import dev.loam.core.embed.Embedder
import dev.loam.core.store.DatabaseKey
import dev.loam.core.llm.LlmEngine
import dev.loam.core.embed.WordPieceTokenizer
import dev.loam.core.vault.TokenCounter
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wires the core together. Hand-rolled rather than a DI framework — there are
 * four objects, and a graph this small does not justify a dependency.
 */
class Loam private constructor(private val context: Context) {

    val vaultLocation by lazy { VaultLocation(context) }
    val modelLocation by lazy { ModelLocation(context) }
    val settings by lazy { Settings(context) }

    /** True when the index cannot be opened until the user authenticates. */
    val isIndexLocked: Boolean
        get() = DatabaseKey.isLocked(context)
    val indexStats by lazy { IndexStats(context) }
    val indexVault by lazy { IndexVault(context, ::newEmbedder, tokenCounter, settings) }
    val searchNotes by lazy {
        SearchNotes(context, ::newEmbedder) { settings.tuning.relevanceFloor }
    }
    val askQuestion by lazy {
        AskQuestion(
            retriever = { question, limit -> searchNotes.search(question, limit) },
            maxChunks = { settings.tuning.chunksPerAnswer },
            engine = ::llmEngine,
        )
    }

    /**
     * Opens a GGUF. Supplied by `:app` at startup rather than constructed here,
     * because `:llama` depends on this module for the [LlmEngine] interface and
     * `:core` referencing `LlamaEngine` back would be a dependency cycle.
     *
     * The alternative was moving this class into `:app`, which would churn
     * working Phase 1 wiring to solve a problem one nullable field solves.
     */
    @Volatile
    var engineFactory: ((Uri, Int) -> LlmEngine)? = null

    /**
     * Whether the model is resident right now.
     *
     * Not the same as "a model is configured" — the engine is released when the
     * app is backgrounded, so a picked model can be absent from memory. The UI
     * needs the distinction to avoid claiming Ready for something it would have
     * to reopen.
     */
    val isEngineLoaded: Boolean
        get() = engine != null

    private val engineMutex = Mutex()
    private var engine: LlmEngine? = null
    private var engineUri: Uri? = null
    private var engineContextTokens: Int = 0

    /**
     * The loaded model, opening it on first use.
     *
     * Cached for the process: the weights are mapped, not copied, but building
     * a context still costs seconds, and re-opening per question would put that
     * in front of every answer. Serialized because the native context is not
     * safe for concurrent use and the failure mode is corruption rather than an
     * exception.
     *
     * Returns null when there is nothing to load — no model picked, or no
     * factory installed. Throws [dev.loam.core.llm.ModelLoadException] when
     * there is something to load and it fails, so the UI can tell a setup step
     * apart from a broken file.
     */
    suspend fun llmEngine(): LlmEngine? = engineMutex.withLock {
        val uri = modelLocation.modelUri ?: return@withLock null
        val factory = engineFactory ?: return@withLock null

        val wanted = settings.tuning.contextTokens
        // The window is fixed when the llama.cpp context is built, so a changed
        // setting means reopening rather than reusing.
        engine?.let { if (engineUri == uri && engineContextTokens == wanted) return@withLock it }

        // The picked model changed. Release the old mapping before opening
        // another — two gigabyte-scale mappings at once is how this gets killed
        // by the OOM reaper on a phone with ~1 GB free.
        closeEngineLocked()

        val start = System.nanoTime()
        return@withLock factory(uri, wanted).also {
            engine = it
            engineUri = uri
            engineContextTokens = wanted
            Log.i(
                TAG,
                "model open %s ctx=%d in %.0fms".format(
                    it.info.name, it.info.contextTokens,
                    (System.nanoTime() - start) / 1_000_000.0,
                )
            )
        }
    }

    /** Call when the model selection changes, or the process is winding down. */
    suspend fun closeEngine() = engineMutex.withLock { closeEngineLocked() }

    private fun closeEngineLocked() {
        engine?.let { runCatching { it.close() } }
        engine = null
        engineUri = null
        engineContextTokens = 0
    }

    /**
     * One tokenizer for the process. Parsing the 30,522-line vocabulary is not
     * free, and it was previously repeated per embedder — measurable on the
     * search path, where it dominated a 331 ms query.
     */
    private val tokenizer: WordPieceTokenizer by lazy {
        WordPieceTokenizer.fromFile(stagedAsset(VOCAB_ASSET))
    }

    /** Lets the chunker size chunks by what the model will actually receive. */
    val tokenCounter = TokenCounter { tokenizer.countTokens(it) }

    /**
     * A fresh ORT session per operation.
     *
     * Sessions are not documented as thread-safe for concurrent `run` calls,
     * and indexing and searching can overlap. Cold start measured 35 ms, which
     * is cheap next to the work either operation goes on to do.
     */
    private fun newEmbedder(): Embedder = Embedder(stagedAsset(MODEL_ASSET), tokenizer)

    /**
     * Copies a bundled asset into files/ on first use.
     *
     * ORT wants a real path so it can map the weights instead of loading 22 MB
     * onto the Java heap, and assets inside an APK have no filesystem path.
     * The copy happens once.
     */
    private fun stagedAsset(name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0) return target
        context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    companion object {
        private const val TAG = "Loam"
        private const val MODEL_ASSET = "model_qint8_arm64.onnx"
        private const val VOCAB_ASSET = "vocab.txt"

        @Volatile
        private var instance: Loam? = null

        fun get(context: Context): Loam =
            instance ?: synchronized(this) {
                instance ?: Loam(context.applicationContext).also { instance = it }
            }
    }
}
