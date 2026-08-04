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
        val bestMillis: Double,
        val iterations: Int,
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

    /**
     * Warmup and measurement are both wall-clock bounded rather than fixed
     * iteration counts.
     *
     * This matters more than it looks. ART decides to JIT-compile on invocation
     * count and on-stack replacement, and a single untimed pass triggers
     * neither — the timed run then measures the interpreter, which is ~50x
     * slower than steady state. That produced a first set of numbers (75ms at
     * 5k, 544ms at 50k) that would have argued for adopting sqlite-vec on the
     * strength of a measurement artifact. The giveaway was cost *per chunk*
     * falling as the store grew, which only happens when a fixed startup cost
     * is being amortized.
     *
     * Best-of is reported alongside the mean because a phone is a noisy
     * environment: other apps, big.LITTLE core migration, and thermal
     * management all inflate the mean. The best sample is the closest thing to
     * the hardware's actual capability.
     */
    fun run(
        store: FloatArray,
        dim: Int,
        topK: Int = 10,
        warmupMs: Long = 1_000,
        measureMs: Long = 1_000,
    ): Result {
        val count = store.size / dim
        val query = synthesize(1, dim, seed = 7)

        val warmEnd = System.nanoTime() + warmupMs * 1_000_000
        while (System.nanoTime() < warmEnd) topK(store, query, dim, count, topK)

        var iterations = 0
        var best = Double.MAX_VALUE
        val measureStart = System.nanoTime()
        val measureEnd = measureStart + measureMs * 1_000_000
        do {
            val t0 = System.nanoTime()
            topK(store, query, dim, count, topK)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            if (ms < best) best = ms
            iterations++
        } while (System.nanoTime() < measureEnd)
        val totalMs = (System.nanoTime() - measureStart) / 1_000_000.0

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        return Result(count, dim, totalMs / iterations, best, iterations, usedMb)
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
