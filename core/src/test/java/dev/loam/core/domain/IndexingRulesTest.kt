package dev.loam.core.domain

import dev.loam.core.vault.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What counts as invalidating the stored index, and what does not.
 *
 * The distinction is the whole reason these two settings were held back until
 * there was a rebuild flow, and getting it wrong is expensive in both
 * directions: too eager re-embeds 5,297 chunks for nothing, too lax leaves the
 * index in two chunk shapes at once with no symptom.
 */
class IndexingRulesTest {

    @Test
    fun changingChunkSizeInvalidatesTheIndex() {
        val a = IndexingRules(chunkTokens = 240)
        val b = IndexingRules(chunkTokens = 200)

        // No note's (mtime, size) changes when this does, so the incremental
        // sweep would revisit nothing. The fingerprint is the only signal.
        assertNotEquals(a.chunkingFingerprint(), b.chunkingFingerprint())
    }

    @Test
    fun changingExcludesDoesNot() {
        val a = IndexingRules(excludePatterns = "")
        val b = IndexingRules(excludePatterns = "templates/")

        // An excluded note stops being found by the walk and the existing stale
        // sweep deletes it. Rebuilding everything for that would be 150 s of
        // re-embedding to achieve a delete.
        assertEquals(a.chunkingFingerprint(), b.chunkingFingerprint())
    }

    @Test
    fun theDefaultsFingerprintIsStable() {
        // Stored and compared across launches, so it cannot depend on anything
        // that varies within a build.
        assertEquals(
            IndexingRules().chunkingFingerprint(),
            IndexingRules().chunkingFingerprint(),
        )
    }

    @Test
    fun anIndexBuiltBeforeThisExistedCountsAsDefaultChunking() {
        // IndexVault substitutes this for a missing record. Back then chunking
        // was the compiled-in default and could not be anything else, so an
        // absent fingerprint is knowledge, not ignorance — and treating it as
        // ignorance costs every existing install a 285 s rebuild to reach the
        // shape it already had.
        assertEquals("v1:240", IndexingRules().chunkingFingerprint())
    }

    @Test
    fun theChunkCeilingLeavesRoomForTheModelsOwnTokens() {
        // Above this the embedder truncates silently — the defect that once
        // cost 14% of this vault while appearing indexed.
        assertEquals(Chunker.DEFAULT_MAX_TOKENS - 2, IndexingRules.CHUNK_RANGE.last)
    }

    @Test
    fun theChunkFloorStaysClearOfTheOverlap() {
        // Chunker requires overlap < target; a target near 32 would also make
        // every chunk mostly a copy of its neighbour.
        assert(IndexingRules.CHUNK_RANGE.first > Chunker.DEFAULT_OVERLAP_TOKENS * 2)
    }
}
