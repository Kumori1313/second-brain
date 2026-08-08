package dev.loam.core.domain

import android.content.Context
import dev.loam.core.store.DatabaseKey
import dev.loam.core.vault.Chunker
import dev.loam.core.store.KeyProtection

/**
 * The constants this project measured its way to, made adjustable.
 *
 * Every default here was fitted against one vault of Linux notes on one phone.
 * That is correct for a personal tool and wrong the moment anyone else installs
 * it, so the values that can be changed without reindexing are exposed rather
 * than compiled in.
 *
 * Deliberately only those three. Chunk size and exclude patterns are a
 * different kind of setting — either one makes what is already stored wrong —
 * so they live in [IndexingRules] and are applied through a rebuild.
 */
data class Tuning(
    /**
     * Chunks fed to the model per answer.
     *
     * The grounding-versus-latency dial, and the one most worth having: six
     * chunks measured ~8–10 s to first token on a Pixel 8a, and each one costs
     * roughly 1.5 s. More context is also not monotonically better — small
     * models attend worse across long inputs.
     */
    val chunksPerAnswer: Int = DEFAULT_CHUNKS_PER_ANSWER,

    /**
     * The model's context window. Prompt and answer share it.
     *
     * Changing this reopens the engine, since it is fixed when the llama.cpp
     * context is built.
     */
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,

    /**
     * Below this cosine score, a hit is called noise rather than a weak match.
     *
     * Re-measured on the 5,297-chunk vault with 30 labelled probe queries; see
     * [SearchNotes.DEFAULT_MIN_SCORE] for the bands. They overlap, so this
     * number picks which error to make rather than avoiding both — which is
     * the argument for a slider, and for "show weak matches" beneath it.
     */
    val relevanceFloor: Float = DEFAULT_RELEVANCE_FLOOR,
) {
    companion object {
        const val DEFAULT_CHUNKS_PER_ANSWER = 6
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_RELEVANCE_FLOOR = SearchNotes.DEFAULT_MIN_SCORE

        /** Ranges the UI offers. Wide enough to be useful, not to be absurd. */
        val CHUNKS_RANGE = 1..12
        val CONTEXT_CHOICES = listOf(2048, 4096, 8192)
        val FLOOR_RANGE = 0.10f..0.60f
    }
}

/**
 * The two settings that invalidate the stored index.
 *
 * Kept apart from [Tuning] because the difference is the whole point: a
 * [Tuning] change takes effect on the next query, while either of these makes
 * what is already stored wrong. Changing them costs a reindex, so they are
 * applied deliberately rather than as you drag a slider.
 */
data class IndexingRules(
    /** One pattern per line. See [dev.loam.core.vault.ExcludeRules]. */
    val excludePatterns: String = "",

    /**
     * The soft size a chunk grows toward, in tokens.
     *
     * Swept against the 392-note vault: 200 gives 5,853 chunks averaging 144
     * tokens, 240 gives 5,297 averaging 156, 254 gives 5,176 averaging 159.
     * Smaller chunks are more precisely targeted and cheaper to embed; larger
     * ones carry more context into an answer and, being longer, score
     * moderately against any query — which is part of why the relevance floor
     * cannot separate the score bands cleanly.
     */
    val chunkTokens: Int = Chunker.DEFAULT_TARGET_TOKENS,
) {
    /**
     * Identifies the settings that change how text is split.
     *
     * Excludes are deliberately absent: a note that stops matching the walk
     * simply stops being found, and the existing stale-note sweep deletes it.
     * Only chunking invalidates rows that are still legitimately there.
     */
    fun chunkingFingerprint(): String = "v1:$chunkTokens"

    companion object {
        /**
         * Floor of 96 because overlap is 32 tokens and a target near it makes
         * every chunk mostly a copy of its neighbour. Ceiling is the model's
         * 256-token window less the two reserved for `[CLS]` and `[SEP]` —
         * above that the embedder truncates, which is the defect that cost 14%
         * of this vault once already.
         */
        val CHUNK_RANGE = 96..(Chunker.DEFAULT_MAX_TOKENS - 2)
    }
}

class Settings(private val context: Context) {

    /**
     * Read-only here on purpose. Changing level re-seals the passphrase under a
     * new key, which may require authentication and therefore an Activity — so
     * it goes through the UI's change flow rather than a property setter that
     * could silently fail.
     */
    val keyProtection: KeyProtection
        get() = DatabaseKey.protection(context)

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Read fresh on every access rather than cached.
     *
     * Callers hold this object for the life of the process, and a cached copy
     * would mean a changed setting only took effect after a restart — which is
     * exactly the kind of thing that gets mistaken for the setting not working.
     */
    var tuning: Tuning
        get() = Tuning(
            chunksPerAnswer = prefs.getInt(KEY_CHUNKS, Tuning.DEFAULT_CHUNKS_PER_ANSWER),
            contextTokens = prefs.getInt(KEY_CONTEXT, Tuning.DEFAULT_CONTEXT_TOKENS),
            relevanceFloor = prefs.getFloat(KEY_FLOOR, Tuning.DEFAULT_RELEVANCE_FLOOR),
        )
        /**
         * A value equal to the current default is stored as *absent*, not as
         * itself.
         *
         * Every default here is a measured constant, and re-measuring is how
         * this project fixes them. Writing the default down turns "I never
         * touched this" into a deliberate-looking choice, and a later
         * recalibration then silently fails to reach anyone who has ever opened
         * Settings — which is exactly what happened when the relevance floor
         * moved from 0.35 to 0.44. Storing only real deviations means a changed
         * measurement reaches the people who never expressed a preference, and
         * leaves alone the people who did.
         */
        set(value) {
            val floor = value.relevanceFloor.coerceIn(
                Tuning.FLOOR_RANGE.start,
                Tuning.FLOOR_RANGE.endInclusive,
            )
            val chunks = value.chunksPerAnswer.coerceIn(Tuning.CHUNKS_RANGE)
            prefs.edit()
                .apply {
                    if (chunks == Tuning.DEFAULT_CHUNKS_PER_ANSWER) remove(KEY_CHUNKS)
                    else putInt(KEY_CHUNKS, chunks)

                    if (value.contextTokens == Tuning.DEFAULT_CONTEXT_TOKENS) remove(KEY_CONTEXT)
                    else putInt(KEY_CONTEXT, value.contextTokens)

                    if (floor == Tuning.DEFAULT_RELEVANCE_FLOOR) remove(KEY_FLOOR)
                    else putFloat(KEY_FLOOR, floor)
                }
                .apply()
        }

    /** Same store-only-deviations rule as [tuning]; same reason. */
    var indexing: IndexingRules
        get() = IndexingRules(
            excludePatterns = prefs.getString(KEY_EXCLUDES, null) ?: "",
            chunkTokens = prefs.getInt(KEY_CHUNK_TOKENS, Chunker.DEFAULT_TARGET_TOKENS),
        )
        set(value) {
            val tokens = value.chunkTokens.coerceIn(IndexingRules.CHUNK_RANGE)
            val excludes = value.excludePatterns.trim()
            prefs.edit()
                .apply {
                    if (excludes.isEmpty()) remove(KEY_EXCLUDES) else putString(KEY_EXCLUDES, excludes)
                    if (tokens == Chunker.DEFAULT_TARGET_TOKENS) remove(KEY_CHUNK_TOKENS)
                    else putInt(KEY_CHUNK_TOKENS, tokens)
                }
                .apply()
        }

    /**
     * The chunking the stored index was actually built with.
     *
     * Written only after a pass completes, so an interrupted rebuild is retried
     * rather than recorded as done — the alternative leaves half the vault
     * chunked one way and half the other, which nothing downstream could detect.
     */
    var indexedChunking: String?
        get() = prefs.getString(KEY_INDEXED_CHUNKING, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_INDEXED_CHUNKING)
                else putString(KEY_INDEXED_CHUNKING, value)
            }.apply()
        }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "loam_settings"
        const val KEY_CHUNKS = "chunks_per_answer"
        const val KEY_CONTEXT = "context_tokens"
        const val KEY_FLOOR = "relevance_floor"
        const val KEY_EXCLUDES = "exclude_patterns"
        const val KEY_CHUNK_TOKENS = "chunk_tokens"
        const val KEY_INDEXED_CHUNKING = "indexed_chunking"
    }
}
