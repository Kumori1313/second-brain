package dev.loam.spike

/**
 * Splits markdown into embedding-sized chunks.
 *
 * Deliberately free of Android imports so it can be tested on the JVM against a
 * real vault. The previous version lived on [VaultReader], which takes a
 * `Context`, so its output could only be checked by porting the algorithm to
 * Python — and a port drifts from the thing it claims to describe.
 *
 * Three rules, each of which exists because breaking it produced a measured
 * defect on a 392-note vault:
 *
 *  1. **Headings break a chunk only once it's worth keeping.** Breaking at
 *     every heading regardless of size produced 6,027 chunks averaging ~108
 *     tokens, 39% of them under 200 characters — against a 200-400 token
 *     target. Consecutive headings (`##` immediately followed by `###`) each
 *     flushed, emitting chunks holding nothing but a heading line.
 *  2. **No chunk may exceed [maxChars].** Splitting only *between* blank-line
 *     blocks means a table or fenced code block containing no blank line stays
 *     whole; one such block in the test vault was 77k characters. Anything past
 *     the tokenizer's `maxLen` is silently dropped at embed time, so most of
 *     that note was unretrievable while appearing indexed.
 *  3. **Fenced code is one block.** Splitting on blank lines alone tears fences
 *     apart, which both mangles the code and lets a `# comment` inside a fence
 *     read as a markdown heading. 293 of the 392 test notes contain fences.
 *
 * Chunks carry a small overlap so a passage spanning a boundary is still
 * retrievable from either side, per the roadmap's "slight overlap". Overlap is
 * skipped after a heading break, where the author has signalled a topic change
 * and carrying the previous topic forward would only blur it.
 */
object Chunker {

    /** Soft size a chunk grows toward: ~300 tokens at ~4 chars/token. */
    const val DEFAULT_TARGET_CHARS = 1200

    /** A heading won't break a chunk smaller than this. */
    const val DEFAULT_MIN_CHARS = 400

    /** Hard ceiling. Nothing emitted may exceed it. */
    const val DEFAULT_MAX_CHARS = 2000

    /** Context carried from the previous chunk across a size break. */
    const val DEFAULT_OVERLAP_CHARS = 150

    fun chunk(
        markdown: String,
        targetChars: Int = DEFAULT_TARGET_CHARS,
        minChars: Int = DEFAULT_MIN_CHARS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<String> {
        require(targetChars in 1..maxChars) { "targetChars must be in 1..maxChars" }
        require(overlapChars in 0 until targetChars) { "overlapChars must be < targetChars" }

        // Cap each piece so that overlap + piece can never breach maxChars,
        // which is what makes rule 2 an actual guarantee rather than a hope.
        val pieceLimit = maxChars - overlapChars
        require(pieceLimit > 0) { "overlapChars must leave room under maxChars" }

        val out = ArrayList<String>()
        val current = StringBuilder()

        fun flush(carryOverlap: Boolean) {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isEmpty()) return
            out.add(text)
            if (carryOverlap && overlapChars > 0) {
                val tail = overlapTail(text, overlapChars)
                if (tail.isNotEmpty()) current.append(tail).append("\n\n")
            }
        }

        for (block in splitBlocks(markdown)) {
            splitOversized(block.text, pieceLimit).forEachIndexed { i, piece ->
                // Only the first piece inherits the block's heading status; the
                // remainder of a split block is body text, not a new topic.
                val isHeading = block.isHeading && i == 0
                val len = current.length
                when {
                    isHeading && len >= minChars -> flush(carryOverlap = false)
                    len > 0 && len + piece.length > targetChars -> flush(carryOverlap = true)
                }
                current.append(piece).append("\n\n")
            }
        }
        flush(carryOverlap = false)

        return out
    }

    private data class Block(val text: String, val isHeading: Boolean)

    /**
     * Group lines into blocks on blank lines, except inside fenced code.
     */
    private fun splitBlocks(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        val buf = StringBuilder()
        var inFence = false
        var fence = ""
        var isHeading = false
        var empty = true

        fun flush() {
            val text = buf.toString().trim()
            buf.setLength(0)
            if (text.isNotEmpty()) blocks.add(Block(text, isHeading))
            isHeading = false
            empty = true
        }

        for (line in markdown.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inFence) {
                    inFence = true
                    fence = trimmed.take(3)
                } else if (trimmed.startsWith(fence)) {
                    inFence = false
                }
            } else if (!inFence && trimmed.isEmpty()) {
                flush()
                continue
            }
            if (empty) {
                // A '#' only opens a heading outside a fence — inside one it's
                // far more likely to be a shell or Python comment.
                isHeading = !inFence && trimmed.startsWith("#")
                empty = false
            }
            buf.append(line).append('\n')
        }
        flush()
        return blocks
    }

    /**
     * Break a block that exceeds [limit], preferring the cleanest boundary
     * available: a line end, then a sentence end, then a space, then a hard cut.
     */
    private fun splitOversized(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)

        val parts = ArrayList<String>()
        var rest = text
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            // Require the cut past the halfway mark, otherwise a boundary near
            // the start would emit a sliver and make no real progress.
            val floor = limit / 2
            var cut = window.lastIndexOf('\n')
            if (cut < floor) cut = lastSentenceEnd(window)
            if (cut < floor) cut = window.lastIndexOf(' ')
            if (cut < floor) cut = limit
            parts.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts.add(rest.trim())
        return parts
    }

    private fun lastSentenceEnd(s: String): Int {
        for (i in s.length - 2 downTo 0) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?') && s[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }

    /** Trailing context, advanced to a word boundary so it can't start mid-word. */
    private fun overlapTail(text: String, overlapChars: Int): String {
        if (text.length <= overlapChars) return text
        var start = text.length - overlapChars
        while (start < text.length && !text[start].isWhitespace()) start++
        return text.substring(start).trim()
    }
}
