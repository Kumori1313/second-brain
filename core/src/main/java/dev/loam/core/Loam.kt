package dev.loam.core

import android.content.Context
import dev.loam.core.domain.IndexStats
import dev.loam.core.domain.IndexVault
import dev.loam.core.domain.SearchNotes
import dev.loam.core.domain.VaultLocation
import dev.loam.core.embed.Embedder
import dev.loam.core.embed.WordPieceTokenizer
import dev.loam.core.vault.TokenCounter
import java.io.File

/**
 * Wires the core together. Hand-rolled rather than a DI framework — there are
 * four objects, and a graph this small does not justify a dependency.
 */
class Loam private constructor(private val context: Context) {

    val vaultLocation by lazy { VaultLocation(context) }
    val indexStats by lazy { IndexStats(context) }
    val indexVault by lazy { IndexVault(context, ::newEmbedder, tokenCounter) }
    val searchNotes by lazy { SearchNotes(context, ::newEmbedder) }

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
