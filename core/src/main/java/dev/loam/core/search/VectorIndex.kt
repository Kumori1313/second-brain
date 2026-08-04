package dev.loam.core.search

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory brute-force vector search.
 *
 * The roadmap left "brute force or sqlite-vec" open until Phase 0 could measure
 * it. It measured: 13.81 ms per query over 50,000 chunks on a Pixel 8a, linear
 * at 0.276 µs per chunk with no inflection. Brute force wins and sqlite-vec
 * stays off the dependency list.
 *
 * Vectors live in one flat [FloatArray] rather than an array of arrays — one
 * contiguous block, no pointer chase per candidate. All vectors are already L2
 * normalized by the embedder, so cosine similarity is a dot product.
 *
 * Memory is the real constraint, not time: 50k chunks is ~77 MB of floats.
 * A vault far past that would want the vectors memory-mapped instead of heaped,
 * which is a Phase 3 concern.
 */
class VectorIndex private constructor(
    private val chunkIds: LongArray,
    private val vectors: FloatArray,
    private val dimensions: Int,
) {

    val size: Int get() = chunkIds.size

    data class Hit(val chunkId: Long, val score: Float)

    /**
     * Top [k] chunks by cosine similarity, best first.
     *
     * [minScore] exists so retrieval can admit it found nothing. Forcing k
     * results regardless of quality is what makes a RAG layer confidently cite
     * irrelevant notes, and Phase 2 depends on this being honest.
     */
    fun search(query: FloatArray, k: Int = 10, minScore: Float = 0f): List<Hit> {
        require(query.size == dimensions) {
            "query has ${query.size} dimensions, index has $dimensions"
        }
        if (chunkIds.isEmpty()) return emptyList()

        val topK = minOf(k, chunkIds.size)
        // Tiny insertion-sorted arrays beat a heap at these sizes and allocate
        // nothing per candidate.
        val bestScores = FloatArray(topK) { Float.NEGATIVE_INFINITY }
        val bestIndices = IntArray(topK) { -1 }

        for (i in chunkIds.indices) {
            val base = i * dimensions
            var dot = 0f
            for (d in 0 until dimensions) dot += vectors[base + d] * query[d]
            if (dot <= bestScores[topK - 1]) continue

            var pos = topK - 1
            while (pos > 0 && bestScores[pos - 1] < dot) {
                bestScores[pos] = bestScores[pos - 1]
                bestIndices[pos] = bestIndices[pos - 1]
                pos--
            }
            bestScores[pos] = dot
            bestIndices[pos] = i
        }

        return (0 until topK)
            .filter { bestIndices[it] >= 0 && bestScores[it] >= minScore }
            .map { Hit(chunkIds[bestIndices[it]], bestScores[it]) }
    }

    companion object {
        fun build(
            rows: List<Pair<Long, ByteArray>>,
            dimensions: Int,
        ): VectorIndex {
            val ids = LongArray(rows.size)
            val vectors = FloatArray(rows.size * dimensions)
            rows.forEachIndexed { i, (chunkId, blob) ->
                ids[i] = chunkId
                toFloats(blob, vectors, i * dimensions, dimensions)
            }
            return VectorIndex(ids, vectors, dimensions)
        }

        /** Little-endian, matching [toBytes]. */
        fun toFloats(blob: ByteArray, into: FloatArray, offset: Int, dimensions: Int) {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (d in 0 until dimensions) into[offset + d] = buffer.get(d)
        }

        fun toBytes(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            vector.forEach { buffer.putFloat(it) }
            return buffer.array()
        }
    }
}
