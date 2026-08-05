package dev.loam.core.vault

/**
 * Splits markdown into chunks that fit the embedding model's window.
 *
 * Deliberately free of Android imports so it can be tested on the JVM against a
 * real vault.
 *
 * **Sizing is in tokens, not characters.** An earlier version budgeted by
 * character count assuming ~4 characters per token. Measured on a real vault it
 * is 3.46 — code, paths, URLs and box-drawing tokenize far denser than prose —
 * so a 1,200-character target was ~350 tokens against a 256-token window. The
 * result was that 28% of chunks overflowed and **14% of the vault's text was
 * truncated away before it ever reached the model**: unsearchable, while
 * appearing indexed. Counting tokens costs ~1 ms per chunk against ~99 ms to
 * embed one, so the proxy was never worth its risk. This also removes the CJK
 * hazard noted earlier, which was the same defect at ~1 char/token.
 *
 * Four rules, each pinned by a regression test because breaking it produced a
 * measured defect:
 *
 *  1. **Nothing exceeds [maxTokens].** Enforced by counting, including inside
 *     an oversized block — splitting only between blank-line blocks once left a
 *     77k-character table whole.
 *  2. **A heading breaks a chunk only once it's worth keeping.** Breaking at
 *     every heading emitted chunks holding nothing but a heading line.
 *  3. **Fenced code is one block.** Splitting on blank lines tears fences apart
 *     and lets a `#` comment read as a heading.
 *  4. **Overlap is carried across size breaks, not heading breaks**, so a
 *     passage spanning a boundary stays retrievable from either side without
 *     blurring a topic change the author marked deliberately.
 */
object Chunker {

    /**
     * Hard ceiling, matching the model's window. Content is budgeted at
     * `maxTokens - 2` to leave room for `[CLS]` and `[SEP]`.
     */
    const val DEFAULT_MAX_TOKENS = 256

    /**
     * Soft size a chunk grows toward before a new one is started.
     *
     * Swept against the 392-note vault: 200 gives 5,853 chunks averaging 144
     * tokens, 240 gives 5,297 averaging 156, and 254 gives 5,176 averaging 159.
     * Returns flatten past 240, and staying under the content budget leaves
     * slack rather than sitting exactly on the ceiling.
     *
     * The roadmap's "200-400 tokens" predates knowing the model's window is
     * 256, so the achievable range is really 64-254. Chunks average below the
     * target because whole blocks are packed rather than split mid-paragraph.
     */
    const val DEFAULT_TARGET_TOKENS = 240

    /** A heading won't break a chunk smaller than this. */
    const val DEFAULT_MIN_TOKENS = 64

    /** Context carried from the previous chunk across a size break. */
    const val DEFAULT_OVERLAP_TOKENS = 32

    /**
     * One chunk, with the heading trail that led to it.
     *
     * [headingPath] is what search results show as a breadcrumb, so a hit reads
     * as "Arch Install > Networking > Static IP" rather than an anonymous
     * paragraph. Captured when the chunk opens, so it reflects where the chunk
     * starts rather than wherever it happens to end.
     */
    data class Chunk(
        val ordinal: Int,
        val headingPath: String,
        val text: String,
    )

    /** Text-only chunking, for callers that don't need the breadcrumb. */
    fun chunkText(
        markdown: String,
        counter: TokenCounter,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        targetTokens: Int = DEFAULT_TARGET_TOKENS,
        minTokens: Int = DEFAULT_MIN_TOKENS,
        overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
    ): List<String> =
        chunk(markdown, counter, maxTokens, targetTokens, minTokens, overlapTokens)
            .map { it.text }

    fun chunk(
        markdown: String,
        counter: TokenCounter,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        targetTokens: Int = DEFAULT_TARGET_TOKENS,
        minTokens: Int = DEFAULT_MIN_TOKENS,
        overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
    ): List<Chunk> {
        // Two slots reserved for [CLS] and [SEP]; forgetting them is how a
        // "fits exactly" chunk loses its final token.
        val contentBudget = maxTokens - 2
        require(contentBudget > 0) { "maxTokens must leave room for [CLS]/[SEP]" }
        require(targetTokens in 1..contentBudget) { "targetTokens must fit the budget" }
        require(overlapTokens in 0 until targetTokens) { "overlapTokens must be < targetTokens" }

        // Cap each piece so overlap + piece can never breach the budget, which
        // is what makes rule 1 a guarantee rather than a hope.
        val pieceBudget = contentBudget - overlapTokens
        require(pieceBudget > 0) { "overlapTokens must leave room under maxTokens" }

        val out = ArrayList<Chunk>()
        val current = StringBuilder()
        var currentTokens = 0
        val headings = ArrayList<Pair<Int, String>>()
        var chunkPath: String? = null

        fun flush(carryOverlap: Boolean) {
            val text = current.toString().trim()
            current.setLength(0)
            currentTokens = 0
            if (text.isNotEmpty()) out.add(Chunk(out.size, chunkPath.orEmpty(), text))
            chunkPath = null
            if (carryOverlap && overlapTokens > 0 && text.isNotEmpty()) {
                val tail = overlapTail(text, overlapTokens, counter)
                if (tail.isNotEmpty()) {
                    current.append(tail).append("\n\n")
                    currentTokens = counter.count(tail)
                }
            }
        }

        for (block in splitBlocks(markdown)) {
            if (block.isHeading) pushHeading(headings, block.text)

            val pieces = splitToFit(block.text, pieceBudget, counter)
            pieces.forEachIndexed { i, piece ->
                val pieceTokens = counter.count(piece)
                // Only the first piece inherits the block's heading status; the
                // rest of a split block is body text, not a new topic.
                val isHeading = block.isHeading && i == 0
                when {
                    isHeading && currentTokens >= minTokens -> flush(carryOverlap = false)
                    currentTokens > 0 && currentTokens + pieceTokens > targetTokens ->
                        flush(carryOverlap = true)
                }
                if (chunkPath == null) chunkPath = headings.joinToString(" > ") { it.second }
                current.append(piece).append("\n\n")
                currentTokens += pieceTokens
            }
        }
        flush(carryOverlap = false)

        return out
    }

    private fun pushHeading(headings: MutableList<Pair<Int, String>>, text: String) {
        val level = text.takeWhile { it == '#' }.length
        val title = text.lineSequence().first().trimStart('#').trim().ifEmpty { "(untitled)" }
        // A heading closes every section at its level or deeper.
        while (headings.isNotEmpty() && headings.last().first >= level) {
            headings.removeAt(headings.lastIndex)
        }
        headings.add(level to title)
    }

    private data class Block(val text: String, val isHeading: Boolean)

    /** Group lines into blocks on blank lines, except inside fenced code. */
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
                // A '#' only opens a heading outside a fence — inside one it is
                // far more likely a shell or Python comment.
                isHeading = !inFence && trimmed.startsWith("#")
                empty = false
            }
            buf.append(line).append('\n')
        }
        flush()
        return blocks
    }

    /**
     * Break [text] into pieces that each fit [budget] tokens, preferring the
     * coarsest boundary that works: paragraphs, then lines, then sentences,
     * then words, then a hard cut.
     *
     * Falling through progressively matters because the pathological inputs are
     * real — a table with no blank lines, a base64 blob with no spaces — and
     * each finer level costs readability, so it is only reached when the
     * coarser one genuinely cannot fit.
     */
    private fun splitToFit(
        text: String,
        budget: Int,
        counter: TokenCounter,
        level: Int = 0,
    ): List<String> {
        if (counter.count(text) <= budget) return listOf(text)
        if (level >= SPLITTERS.size) return hardSplit(text, budget, counter)

        val units = SPLITTERS[level](text).filter { it.isNotBlank() }
        if (units.size <= 1) return splitToFit(text, budget, counter, level + 1)

        val separator = if (level == 0) "\n\n" else if (level == 1) "\n" else " "
        val out = ArrayList<String>()
        val current = StringBuilder()
        var currentTokens = 0

        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString().trim())
                current.setLength(0)
                currentTokens = 0
            }
        }

        for (unit in units) {
            val unitTokens = counter.count(unit)
            if (unitTokens > budget) {
                // This unit cannot fit at any packing; go finer for it alone.
                flush()
                out.addAll(splitToFit(unit, budget, counter, level + 1))
                continue
            }
            if (currentTokens + unitTokens > budget && current.isNotEmpty()) flush()
            if (current.isNotEmpty()) current.append(separator)
            current.append(unit)
            currentTokens += unitTokens
        }
        flush()
        return out
    }

    private val SPLITTERS: List<(String) -> List<String>> = listOf(
        { it.split(Regex("\n\\s*\n")) },
        { it.split("\n") },
        { splitSentences(it) },
        { it.split(" ") },
    )

    private fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (i in text.indices) {
            current.append(text[i])
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            ) {
                out.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /**
     * Last resort for a single token-dense run with no whitespace to cut on —
     * a long URL, a base64 payload, a line of box-drawing.
     */
    private fun hardSplit(text: String, budget: Int, counter: TokenCounter): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val tokens = counter.count(rest)
            if (tokens <= budget) {
                out.add(rest)
                break
            }
            // Estimate the character length that fits, then shrink until it
            // actually does — the ratio varies wildly across scripts, so the
            // estimate is a starting point, never the answer.
            var take = (rest.length.toLong() * budget / tokens).toInt().coerceAtLeast(1)
            while (take > 1 && counter.count(rest.substring(0, take)) > budget) {
                take = take * 3 / 4
            }
            out.add(rest.substring(0, take))
            rest = rest.substring(take)
        }
        return out
    }

    /** Trailing context of roughly [overlapTokens], cut at a word boundary. */
    private fun overlapTail(text: String, overlapTokens: Int, counter: TokenCounter): String {
        if (counter.count(text) <= overlapTokens) return text
        val words = text.split(" ")
        val tail = ArrayList<String>()
        var tokens = 0
        for (i in words.indices.reversed()) {
            val t = counter.count(words[i])
            if (tokens + t > overlapTokens) break
            tail.add(0, words[i])
            tokens += t
        }
        return tail.joinToString(" ").trim()
    }
}
