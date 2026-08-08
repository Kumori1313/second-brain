package dev.loam.core.domain

import android.content.Context
import android.util.Log
import dev.loam.core.embed.Embedder
import dev.loam.core.search.VectorIndex
import dev.loam.core.store.LoamDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /**
     * Read per query rather than captured, so changing the floor in settings
     * takes effect on the next search instead of the next launch.
     */
    private val minScoreProvider: () -> Float = { DEFAULT_MIN_SCORE },
) {

    /**
     * @param snippet trimmed for display in the results list.
     * @param text the whole chunk, as indexed. Carried alongside the snippet
     *   because [dev.loam.core.domain.AskQuestion] feeds chunks to a model and
     *   must not send it a truncated one — an answer grounded in the first 320
     *   characters of its evidence would look sound and be wrong. Cheap to keep:
     *   a full result page is a few tens of kB, against a second query and a
     *   second decrypt to fetch the same rows again.
     */
    data class Result(
        val chunkId: Long,
        val score: Float,
        val displayName: String,
        val relativePath: String,
        val headingPath: String,
        val snippet: String,
        val text: String,
        val uri: String,
    )

    private val mutex = Mutex()
    private var cached: VectorIndex? = null

    /**
     * One long-lived session for querying.
     *
     * Building an [Embedder] per search measured ~314 ms — an ONNX session
     * plus re-parsing a 30,522-line vocabulary — against 17 ms to actually
     * embed the query. Rebuilding it per keystroke made every other
     * optimization invisible. Kept alive for the process and serialized by
     * [mutex], since ORT sessions are not documented as safe for concurrent
     * `run` calls. Indexing builds its own, so a long index never blocks a
     * search.
     */
    private var embedder: Embedder? = null

    /** Call after indexing; the next search reloads from the store. */
    suspend fun invalidate() = mutex.withLock { cached = null }

    /**
     * Pays the first-query cost up front, off the critical path.
     *
     * Three things are lazy and only one of them is obvious: creating the ONNX
     * session, reading every vector out of the encrypted store, and ONNX
     * Runtime's own graph and arena setup, which happens on the first `run`
     * rather than at session creation. Together they measured ~750 ms — enough
     * that the first search after opening the app felt broken while every
     * subsequent one took 12 ms.
     *
     * Safe to call repeatedly and safe to fail: a vault with no index yet, or a
     * model that cannot be read, should degrade to a slow first search rather
     * than take the app down at startup.
     */
    suspend fun warmUp(): Unit = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        try {
            mutex.withLock {
                val index = cached ?: load().also { cached = it }
                val emb = embedder ?: embedderFactory().also { embedder = it }
                // A short string warms the small bucket that real queries hit;
                // warming at 256 would prime a shape searches never use.
                emb.embed(WARMUP_TEXT)
                Log.i(
                    TAG,
                    "warm chunks=${index.size} in %.0fms"
                        .format((System.nanoTime() - start) / 1_000_000.0)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "warm-up skipped: ${e.message}")
        }
    }

    suspend fun search(
        query: String,
        limit: Int = 20,
        minScore: Float = minScoreProvider(),
    ): List<Result> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()

        val embedStart = System.nanoTime()
        val (index, vector) = mutex.withLock {
            val idx = cached ?: load().also { cached = it }
            val emb = embedder ?: embedderFactory().also { embedder = it }
            idx to (if (idx.size == 0) FloatArray(0) else emb.embed(query))
        }
        val embedMs = (System.nanoTime() - embedStart) / 1_000_000.0
        if (index.size == 0) return@withContext emptyList()

        val scanStart = System.nanoTime()
        val hits = index.search(vector, k = limit, minScore = minScore)
        val scanMs = (System.nanoTime() - scanStart) / 1_000_000.0

        Log.i(
            TAG,
            "query chunks=${index.size} embed=%.1fms scan=%.1fms hits=%d"
                .format(embedMs, scanMs, hits.size)
        )
        if (hits.isEmpty()) return@withContext emptyList()

        val dao = LoamDatabase.get(context).dao()
        val byId = dao.chunksWithNotes(hits.map { it.chunkId }).associateBy { it.chunkId }

        // Re-apply the ranking: SQL `IN` gives no ordering guarantee, and
        // trusting it would quietly scramble relevance order.
        hits.mapNotNull { hit ->
            byId[hit.chunkId]?.let { row ->
                Result(
                    chunkId = row.chunkId,
                    score = hit.score,
                    displayName = row.displayName,
                    relativePath = row.relativePath,
                    headingPath = row.headingPath,
                    snippet = row.text.trim().take(SNIPPET_CHARS),
                    text = row.text,
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
        private const val TAG = "LoamSearch"

        /**
         * Below this, a hit is noise rather than a weak match.
         *
         * Calibrated against the real index, not against intuition. Re-measured
         * on the 5,297-chunk vault with 30 probe queries, scoring the top hit of
         * each and labelling it by hand:
         *
         *   direct on-topic questions   0.520 – 0.820   (12 queries)
         *   conversational phrasings    0.328 – 0.525   ( 4 with useful hits)
         *   nothing useful to show      0.166 – 0.413   (12 queries)
         *
         * Those bands overlap, so no threshold separates them and the number is
         * a choice about which error to make. This one is the midpoint of the
         * gap between the highest wrong top hit (0.413, "I want to run Windows
         * without rebooting" matching an NTFS repair note) and the lowest right
         * one (0.465, "the laptop battery drains too fast"). It halves the
         * error count of the previous 0.35, which sat only 0.022 above the
         * worst pure noise and let three unrelated top hits through.
         *
         * What it costs is real and is why [WEAK_SCORE_RATIO] exists: two
         * genuinely answerable questions score 0.328 and 0.353 and are now
         * below the line. They are reachable on request rather than lost.
         *
         * An earlier value of 0.15 came from the spike's *sentence-to-sentence*
         * scores, where a related pair measured 0.250 against 0.062. Query-to-
         * chunk scores sit much higher: chunks are long, so they carry a bit of
         * everything and score moderately against any query.
         *
         * Thirty queries against one topically narrow vault does not justify
         * three digits of confidence. Anything in 0.42–0.45 scored the same;
         * this is why the value is a slider.
         */
        const val DEFAULT_MIN_SCORE = 0.44f

        /**
         * How far below the floor "show weak matches" reaches.
         *
         * Relative rather than fixed, so it tracks a floor the user has moved
         * instead of quietly becoming a second, unrelated setting. At the
         * default floor this is 0.31, which is below both of the answerable
         * questions the floor gives up and above all but one of the measured
         * noise scores.
         */
        const val WEAK_SCORE_RATIO = 0.7f
        private const val SNIPPET_CHARS = 320
        private const val WARMUP_TEXT = "warm up the embedding session"
    }
}
