package dev.loam.core.domain

import android.content.Context

/**
 * The constants this project measured its way to, made adjustable.
 *
 * Every default here was fitted against one vault of Linux notes on one phone.
 * That is correct for a personal tool and wrong the moment anyone else installs
 * it, so the values that can be changed without reindexing are exposed rather
 * than compiled in.
 *
 * Deliberately only those three. Chunk size and exclude patterns would each
 * invalidate the stored index, which makes them a different kind of setting —
 * one that costs a reindex — and they are left for when that flow exists.
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
     * Calibrated against a 3,427-chunk vault of Linux notes: real hits measured
     * 0.66–0.68, pure noise 0.18–0.19. It has already misfired in both
     * directions on that same vault — a note answering a question scored under
     * it while an unrelated chunk cleared it at 0.36 — which is the argument
     * for making it adjustable rather than for picking a better number.
     */
    val relevanceFloor: Float = DEFAULT_RELEVANCE_FLOOR,
) {
    companion object {
        const val DEFAULT_CHUNKS_PER_ANSWER = 6
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_RELEVANCE_FLOOR = 0.35f

        /** Ranges the UI offers. Wide enough to be useful, not to be absurd. */
        val CHUNKS_RANGE = 1..12
        val CONTEXT_CHOICES = listOf(2048, 4096, 8192)
        val FLOOR_RANGE = 0.10f..0.60f
    }
}

class Settings(context: Context) {

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
        set(value) {
            prefs.edit()
                .putInt(KEY_CHUNKS, value.chunksPerAnswer.coerceIn(Tuning.CHUNKS_RANGE))
                .putInt(KEY_CONTEXT, value.contextTokens)
                .putFloat(
                    KEY_FLOOR,
                    value.relevanceFloor.coerceIn(
                        Tuning.FLOOR_RANGE.start,
                        Tuning.FLOOR_RANGE.endInclusive,
                    ),
                )
                .apply()
        }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "loam_settings"
        const val KEY_CHUNKS = "chunks_per_answer"
        const val KEY_CONTEXT = "context_tokens"
        const val KEY_FLOOR = "relevance_floor"
    }
}
