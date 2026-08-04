package dev.loam.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Each of the first three groups pins a defect measured on a real vault, so a
 * regression shows up as a failing test rather than as quietly worse retrieval.
 */
class ChunkerTest {

    private val vault = File("../Documents/pensive")

    // --- rule 1: headings must not emit slivers -----------------------------

    @Test
    fun consecutiveHeadingsDoNotEmitSlivers() {
        val md = """
            # Title

            ## Section A

            ### Subsection

            #### Deeper

            Finally some actual body text that belongs to the deepest heading.
        """.trimIndent()

        val chunks = Chunker.chunkText(md)

        // Previously each heading flushed, yielding four chunks holding nothing
        // but a heading line. Headings with no body between them must coalesce.
        assertEquals("headings with no body should coalesce", 1, chunks.size)
        assertTrue("body must survive", chunks[0].contains("actual body text"))
    }

    @Test
    fun headingBreaksOnceChunkIsSubstantial() {
        val body = "word ".repeat(120) // ~600 chars, comfortably over minChars
        val md = "# First\n\n$body\n\n# Second\n\n$body"

        val chunks = Chunker.chunkText(md)

        assertTrue("a heading past minChars should start a new chunk", chunks.size >= 2)
        assertTrue("second heading opens a chunk", chunks.any { it.trimStart().startsWith("# Second") })
    }

    // --- rule 2: hard ceiling ----------------------------------------------

    @Test
    fun oversizedBlockIsSplitBelowCeiling() {
        // A table with no blank line anywhere — the 77k-char shape from the
        // real vault, which used to pass through as a single chunk and then be
        // silently truncated at embed time.
        val row = "| cell value here | another column | third column |\n"
        val chunks = Chunker.chunkText(row.repeat(2000))

        assertTrue("must split into many chunks", chunks.size > 10)
        assertTrue(
            "no chunk may exceed maxChars, got ${chunks.maxOf { it.length }}",
            chunks.all { it.length <= Chunker.DEFAULT_MAX_CHARS },
        )
    }

    @Test
    fun ceilingHoldsWithNoWhitespaceToBreakOn() {
        // Degenerate input: a single unbroken token far past the ceiling. The
        // splitter must fall back to a hard cut rather than loop or overflow.
        val chunks = Chunker.chunkText("x".repeat(20_000))

        assertTrue(chunks.isNotEmpty())
        assertTrue(
            "hard cut must still respect the ceiling",
            chunks.all { it.length <= Chunker.DEFAULT_MAX_CHARS },
        )
        assertEquals("no content may be dropped", 20_000, chunks.sumOf { it.length })
    }

    // --- rule 3: fenced code is one block ----------------------------------

    @Test
    fun fencedCodeSurvivesBlankLines() {
        val md = """
            Intro paragraph.

            ```python
            def one():
                return 1

            def two():
                return 2
            ```

            Trailing paragraph.
        """.trimIndent()

        val chunks = Chunker.chunkText(md)
        val fenceChunk = chunks.single { it.contains("def one") }

        assertTrue(
            "a blank line inside a fence must not split it",
            fenceChunk.contains("def two"),
        )
    }

    @Test
    fun hashInsideFenceIsNotTreatedAsHeading() {
        val body = "word ".repeat(120)
        val md = "$body\n\n```bash\n# this is a shell comment, not a heading\necho hi\n```"

        val chunks = Chunker.chunkText(md)

        assertEquals("a comment inside a fence must not break the chunk", 1, chunks.size)
    }

    // --- overlap ------------------------------------------------------------

    @Test
    fun overlapCarriesContextAcrossSizeBreak() {
        val md = (1..40).joinToString("\n\n") { "Paragraph number $it with enough words to add length." }

        val chunks = Chunker.chunkText(md)
        assumeTrue("needs at least two chunks to compare", chunks.size >= 2)

        val tailOfFirst = chunks[0].takeLast(60)
        assertTrue(
            "chunk 2 should begin with context carried from chunk 1",
            tailOfFirst.split(" ").filter { it.length > 3 }.any { chunks[1].startsWith(it, ignoreCase = true) || chunks[1].take(200).contains(it) },
        )
    }

    @Test
    fun headingBreakCarriesNoOverlap() {
        val body = "word ".repeat(120)
        val md = "# First\n\n$body\n\n# Second\n\nmore text here"

        val chunks = Chunker.chunkText(md)
        val second = chunks.first { it.contains("# Second") }

        assertTrue(
            "a topic change should not drag the previous topic along",
            second.trimStart().startsWith("# Second"),
        )
    }

    // --- heading breadcrumbs ---

    @Test
    fun headingPathRecordsTheTrail() {
        val body = "word ".repeat(120)
        val md = """
            # Arch Install

            $body

            ## Networking

            $body

            ### Static IP

            $body
        """.trimIndent()

        val paths = Chunker.chunk(md).map { it.headingPath }

        assertTrue("expected a nested trail, got $paths",
            paths.any { it == "Arch Install > Networking > Static IP" })
        assertTrue("expected a top-level trail, got $paths",
            paths.any { it == "Arch Install" })
    }

    @Test
    fun siblingHeadingReplacesRatherThanNests() {
        val body = "word ".repeat(120)
        val md = "# Top\n\n## First\n\n$body\n\n## Second\n\n$body"

        val paths = Chunker.chunk(md).map { it.headingPath }

        assertTrue("a sibling must not nest under its sibling, got $paths",
            paths.none { it.contains("First > Second") })
        assertTrue("expected Top > Second, got $paths", paths.any { it == "Top > Second" })
    }

    @Test
    fun chunkOrdinalsAreSequential() {
        val md = (1..40).joinToString("\n\n") { "Paragraph $it with enough words to add real length here." }
        val ordinals = Chunker.chunk(md).map { it.ordinal }
        assertEquals(ordinals.indices.toList(), ordinals)
    }

    // --- degenerate inputs --------------------------------------------------

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(Chunker.chunk("").isEmpty())
        assertTrue(Chunker.chunk("   \n\n  \t \n").isEmpty())
    }

    @Test
    fun shortDocumentStaysWhole() {
        val chunks = Chunker.chunkText("# Note\n\nOne short paragraph.")
        assertEquals(1, chunks.size)
    }

    // --- the real vault -----------------------------------------------------

    /**
     * The distribution check that motivated the rewrite. Hand-written cases
     * confirm the rules fire; only real notes show what they add up to.
     */
    @Test
    fun realVaultProducesRetrievableChunkSizes() {
        assumeTrue(
            "test vault absent at ${vault.absolutePath} — see .gitignore for the clone command",
            vault.isDirectory,
        )

        val sizes = ArrayList<Int>()
        var files = 0
        vault.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .forEach { f ->
                files++
                sizes += Chunker.chunkText(f.readText()).map { it.length }
            }

        assumeTrue("vault has no markdown", sizes.isNotEmpty())

        val mean = sizes.average()
        val tiny = sizes.count { it < 200 }
        val tinyPct = 100.0 * tiny / sizes.size
        println(
            "vault: $files files, ${sizes.size} chunks, mean ${"%.0f".format(mean)} chars " +
                "(~${"%.0f".format(mean / 4)} tokens), ${"%.1f".format(tinyPct)}% under 200, " +
                "max ${sizes.max()}"
        )

        assertTrue(
            "no chunk may exceed the ceiling, got ${sizes.max()}",
            sizes.max() <= Chunker.DEFAULT_MAX_CHARS,
        )
        // 200-400 tokens at ~4 chars/token; allow a wide band so this asserts
        // the shape of the distribution, not one exact tuning.
        assertTrue("mean $mean chars is outside the useful band", mean in 600.0..1600.0)
        assertTrue("$tinyPct% of chunks are too small to retrieve well", tinyPct < 10.0)
    }
}
