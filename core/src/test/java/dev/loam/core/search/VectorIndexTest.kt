package dev.loam.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VectorIndexTest {

    private fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    private fun indexOf(vararg vectors: Pair<Long, FloatArray>) =
        VectorIndex.build(
            vectors.map { (id, v) -> id to VectorIndex.toBytes(v) },
            vectors.first().second.size,
        )

    @Test
    fun blobRoundTripsExactly() {
        val original = FloatArray(384) { (it - 192) / 193f }
        val restored = FloatArray(384)
        VectorIndex.toFloats(VectorIndex.toBytes(original), restored, 0, 384)

        // Exact equality, not approximate: this is a byte-level round trip, and
        // a wrong endianness or stride would silently corrupt every vector in
        // the store while still producing plausible-looking numbers.
        assertTrue(original.contentEquals(restored))
    }

    @Test
    fun ranksByCosineSimilarity() {
        val query = unit(1f, 0f, 0f)
        val index = indexOf(
            1L to unit(0f, 1f, 0f),      // orthogonal
            2L to unit(1f, 0f, 0f),      // identical
            3L to unit(0.7f, 0.7f, 0f),  // between
        )

        val hits = index.search(query, k = 3)

        assertEquals(listOf(2L, 3L, 1L), hits.map { it.chunkId })
        assertEquals(1.0f, hits[0].score, 1e-5f)
    }

    @Test
    fun minScoreSuppressesWeakMatches() {
        val query = unit(1f, 0f, 0f)
        val index = indexOf(
            1L to unit(1f, 0f, 0f),
            2L to unit(0f, 1f, 0f),
        )

        // "No good matches" has to be reachable, or Phase 2 will cite whatever
        // ranked first no matter how irrelevant it is.
        val hits = index.search(query, k = 10, minScore = 0.5f)

        assertEquals(listOf(1L), hits.map { it.chunkId })
    }

    @Test
    fun emptyIndexReturnsNothing() {
        val index = VectorIndex.build(emptyList(), 384)
        assertEquals(0, index.size)
        assertTrue(index.search(FloatArray(384), k = 5).isEmpty())
    }

    @Test
    fun requestingMoreThanAvailableIsClamped() {
        val index = indexOf(1L to unit(1f, 0f, 0f), 2L to unit(0f, 1f, 0f))
        assertEquals(2, index.search(unit(1f, 1f, 0f), k = 50).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedQueryDimensionsFailLoudly() {
        // Silently padding or truncating here would mean a model change
        // produces garbage rankings instead of an error.
        indexOf(1L to unit(1f, 0f, 0f)).search(FloatArray(8))
    }
}
