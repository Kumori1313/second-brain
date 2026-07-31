package dev.loam.spike

import kotlin.random.Random
import kotlin.math.sqrt

/**
 * Brute-force cosine similarity benchmark.
 *
 * Answers the roadmap's open question directly: at what vault size does linear
 * scan stop being fast enough, and therefore when (if ever) is sqlite-vec worth
 * the extra dependency? Run this on the actual phone — desktop numbers are
 * meaningless here.
 *
 * Vectors are pre-normalized, so cosine similarity reduces to a dot product,
 * which is what a real implementation should do too.
 */
object CosineBench {

    data class Result(
        val chunkCount: Int,
        val dimensions: Int,
        val millisPerQuery: Double,
        val heapUsedMb: Long,
    )

    /** Flat [count * dim] array — one contiguous block, not an array of arrays. */
    fun synthesize(count: Int, dim: Int, seed: Int = 42): FloatArray {
        val rng = Random(seed)
        val store = FloatArray(count * dim)
        for (i in 0 until count) {
            val base = i * dim
            var norm = 0f
            for (d in 0 until dim) {
                val v = rng.nextFloat() * 2f - 1f
                store[base + d] = v
                norm += v * v
            }
            norm = sqrt(norm)
            if (norm > 0f) for (d in 0 until dim) store[base + d] /= norm
        }
        return store
    }

    fun run(store: FloatArray, dim: Int, topK: Int = 10, queries: Int = 20): Result {
        val count = store.size / dim
        val query = synthesize(1, dim, seed = 7)

        // One untimed pass so JIT warmup doesn't get charged to the first result.
        topK(store, query, dim, count, topK)

        val start = System.nanoTime()
        repeat(queries) { topK(store, query, dim, count, topK) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        return Result(count, dim, elapsedMs / queries, usedMb)
    }

    private fun topK(
        store: FloatArray,
        query: FloatArray,
        dim: Int,
        count: Int,
        k: Int,
    ): FloatArray {
        // Keep the k best scores in a tiny insertion-sorted array. For k~10 this
        // beats a heap and allocates nothing per candidate.
        val best = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in 0 until count) {
            val base = i * dim
            var dot = 0f
            for (d in 0 until dim) dot += store[base + d] * query[d]
            if (dot <= best[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && best[pos - 1] < dot) {
                best[pos] = best[pos - 1]
                pos--
            }
            best[pos] = dot
        }
        return best
    }
}
