package dev.loam.core.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Padding inputs to a bucket instead of a flat [Embedder.MAX_LEN] is only safe
 * if the bucket always fits the tokens and never exceeds the model's window.
 * Getting either wrong throws at inference time or silently truncates.
 */
class BucketingTest {

    private val vocabFile = File("../models/all-MiniLM-L6-v2/vocab.txt")
    private lateinit var tokenizer: WordPieceTokenizer

    @Before
    fun setUp() {
        assumeTrue(
            "vocab.txt not found — download the model per the roadmap prerequisites",
            vocabFile.exists(),
        )
        tokenizer = WordPieceTokenizer.fromFile(vocabFile)
    }

    @Test
    fun bucketRoundsUpToMultiple() {
        assertEquals(32, Embedder.bucketFor(1, 256))
        assertEquals(32, Embedder.bucketFor(32, 256))
        assertEquals(64, Embedder.bucketFor(33, 256))
        assertEquals(192, Embedder.bucketFor(190, 256))
        assertEquals(256, Embedder.bucketFor(256, 256))
    }

    @Test
    fun bucketNeverExceedsMaxLen() {
        // The graph is built for at most maxLen positions; overshooting would
        // fail at inference rather than degrade.
        for (n in 1..256) {
            assertTrue("bucket for $n exceeded maxLen", Embedder.bucketFor(n, 256) <= 256)
        }
    }

    @Test
    fun bucketAlwaysFitsTheTokens() {
        // Undershooting is the dangerous direction: pad() would throw, or worse
        // a caller could silently drop the tail of a chunk.
        for (n in 1..256) {
            assertTrue("bucket for $n lost tokens", Embedder.bucketFor(n, 256) >= n)
        }
    }

    @Test
    fun shortTextPadsToSmallestBucket() {
        val tokens = tokenizer.tokenize("virtual machine", 256)
        val bucket = Embedder.bucketFor(tokens.size, 256)
        assertEquals("a short query should not pad to 256", 32, bucket)

        val enc = tokenizer.pad(tokens, bucket)
        assertEquals(32, enc.inputIds.size)
        assertEquals(tokens.size, enc.attentionMask.count { it == 1L })
    }

    @Test
    fun longTextStillTruncatesAtMaxLen() {
        val tokens = tokenizer.tokenize("word ".repeat(2000), 256)
        assertEquals("truncation budget unchanged", 256, tokens.size)
        assertEquals(256, Embedder.bucketFor(tokens.size, 256))
    }

    @Test
    fun encodeStillPadsToMaxLenForCallersThatWantIt() {
        // encode() keeps its old fixed-width contract; only Embedder buckets.
        val enc = tokenizer.encode("hello world", maxLen = 128)
        assertEquals(128, enc.inputIds.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun padRejectsTooSmallATarget() {
        val tokens = tokenizer.tokenize("a reasonably long sentence to tokenize", 256)
        tokenizer.pad(tokens, tokens.size - 1)
    }

    @Test
    fun paddingDoesNotChangeTheRealTokens() {
        val tokens = tokenizer.tokenize("encrypting a disk with LUKS", 256)
        val small = tokenizer.pad(tokens, Embedder.bucketFor(tokens.size, 256))
        val large = tokenizer.pad(tokens, 256)

        // Same ids and same mask over the real span — only the tail differs.
        for (i in tokens.indices) {
            assertEquals(large.inputIds[i], small.inputIds[i])
            assertEquals(1L, small.attentionMask[i])
        }
        assertEquals(tokens.size, small.attentionMask.count { it == 1L })
        assertEquals(tokens.size, large.attentionMask.count { it == 1L })
    }
}
