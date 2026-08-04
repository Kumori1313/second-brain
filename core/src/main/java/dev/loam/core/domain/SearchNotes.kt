package dev.loam.core.domain

import android.content.Context
import dev.loam.core.embed.Embedder
import dev.loam.core.search.VectorIndex
import dev.loam.core.store.LoamDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Embeds a query with the same model used for indexing, then ranks stored
 * vectors by cosine similarity.
 *
 * Using the same model on both sides is not optional — vectors from two
 * different models are not comparable, and mixing them fails silently by
 * returning confident nonsense.
 *
 * The vector index is loaded once and cached. Rebuilding it per keystroke would
 * dominate the 13.81 ms search itself, since deserializing 50k blobs costs far
 * more than scanning them.
 */
class SearchNotes(
    private val context: Context,
    private val embedderFactory: () -> Embedder,
) {

    data class Result(
        val chunkId: Long,
        val score: Float,
        val displayName: String,
        val relativePath: String,
        val headingPath: String,
        val snippet: String,
        val uri: String,
    )

    private val mutex = Mutex()
    private var cached: VectorIndex? = null

    /** Call after indexing; the next search reloads from the store. */
    suspend fun invalidate() = mutex.withLock { cached = null }

    suspend fun search(
        query: String,
        limit: Int = 20,
        minScore: Float = DEFAULT_MIN_SCORE,
    ): List<Result> {
        if (query.isBlank()) return emptyList()

        val index = mutex.withLock {
            cached ?: load().also { cached = it }
        }
        if (index.size == 0) return emptyList()

        val vector = embedderFactory().use { it.embed(query) }
        val hits = index.search(vector, k = limit, minScore = minScore)
        if (hits.isEmpty()) return emptyList()

        val dao = LoamDatabase.get(context).dao()
        val byId = dao.chunksWithNotes(hits.map { it.chunkId }).associateBy { it.chunkId }

        // Re-apply the ranking: SQL `IN` gives no ordering guarantee, and
        // trusting it would quietly scramble relevance order.
        return hits.mapNotNull { hit ->
            byId[hit.chunkId]?.let { row ->
                Result(
                    chunkId = row.chunkId,
                    score = hit.score,
                    displayName = row.displayName,
                    relativePath = row.relativePath,
                    headingPath = row.headingPath,
                    snippet = row.text.trim().take(SNIPPET_CHARS),
                    uri = row.uri,
                )
            }
        }
    }

    private suspend fun load(): VectorIndex {
        val rows = LoamDatabase.get(context).dao().allEmbeddings()
        return VectorIndex.build(
            rows.map { it.chunkId to it.embedding },
            Embedder.DIMENSIONS,
        )
    }

    companion object {
        /**
         * Below this, a hit is noise rather than a weak match.
         *
         * Calibrated against the real index, not against intuition. Measured on
         * a 3,427-chunk vault of Linux notes:
         *
         *   "how do I set up a virtual machine"  -> 0.68, 0.67  (correct notes)
         *   "banana bread recipe with walnuts"   -> 0.19, 0.18  (pure noise)
         *
         * An earlier value of 0.15 came from the spike's *sentence-to-sentence*
         * scores, where a related pair measured 0.250 against 0.062. Query-to-
         * chunk scores sit much higher: chunks are long, so they carry a bit of
         * everything and score moderately against any query. That threshold let
         * the banana-bread query return confident-looking Linux notes.
         *
         * Surfacing "no good matches" is a stated requirement, and it only
         * works if this is calibrated to how the model scores the text it is
         * actually being asked about. Recalibrate if the model, the chunk size,
         * or the pooling ever changes.
         */
        const val DEFAULT_MIN_SCORE = 0.35f
        private const val SNIPPET_CHARS = 320
    }
}
