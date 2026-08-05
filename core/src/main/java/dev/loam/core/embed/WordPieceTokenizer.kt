package dev.loam.core.embed

import java.io.File
import java.io.InputStream
import java.text.Normalizer

/**
 * BERT-uncased WordPiece tokenizer, matching the one MiniLM-L6-v2 was trained
 * with.
 *
 * Hand-rolled rather than pulled from a library: the HuggingFace tokenizers
 * binding is a heavy native dependency for what amounts to a vocabulary lookup
 * and a handful of Unicode rules.
 *
 * A wrong tokenizer does not throw — it produces plausible embeddings that
 * retrieve badly. This one is checked against HuggingFace's own implementation
 * across 56 hand-written cases and 1,500 segments of real vault text; see the
 * spike's tokenizer verification notes. Two bugs surfaced that way (CJK
 * ideograph splitting, and a missing `_clean_text`), both fixed here. Re-run
 * that comparison if the embedding model ever changes, since a new model means
 * a new vocabulary and different rules.
 */
class WordPieceTokenizer private constructor(private val vocab: Map<String, Int>) {

    companion object {
        /** Vocab files are one token per line, id == line number. */
        fun fromLines(lines: Sequence<String>): WordPieceTokenizer =
            WordPieceTokenizer(buildMap {
                lines.forEachIndexed { index, token -> put(token, index) }
            })

        fun fromFile(file: File): WordPieceTokenizer =
            file.bufferedReader().useLines { fromLines(it) }

        fun fromStream(input: InputStream): WordPieceTokenizer =
            input.bufferedReader().useLines { fromLines(it) }
    }

    private val unkId = vocab["[UNK]"] ?: error("vocab.txt missing [UNK]")
    private val clsId = vocab["[CLS]"] ?: error("vocab.txt missing [CLS]")
    private val sepId = vocab["[SEP]"] ?: error("vocab.txt missing [SEP]")

    val vocabSize: Int get() = vocab.size

    /** Token ids plus attention mask, already padded/truncated to [maxLen]. */
    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    /**
     * Token ids with `[CLS]`/`[SEP]`, truncated to [maxLen]. Unpadded, so the
     * caller can decide how far to pad — see [pad].
     */
    fun tokenize(text: String, maxLen: Int = 256): IntArray {
        val pieces = ArrayList<Int>(minOf(maxLen, 64))
        pieces.add(clsId)

        for (word in basicTokenize(text)) {
            if (pieces.size >= maxLen - 1) break
            for (piece in wordPiece(word)) {
                if (pieces.size >= maxLen - 1) break
                pieces.add(piece)
            }
        }
        pieces.add(sepId)
        return IntArray(pieces.size) { pieces[it] }
    }

    /** Pads [tokens] out to [length]. Must be at least `tokens.size`. */
    fun pad(tokens: IntArray, length: Int): Encoded {
        require(length >= tokens.size) {
            "cannot pad ${tokens.size} tokens into $length slots"
        }
        val ids = LongArray(length)
        val mask = LongArray(length)
        for (i in tokens.indices) {
            ids[i] = tokens[i].toLong()
            mask[i] = 1L
        }
        // Remaining entries stay 0 — [PAD] is id 0 in BERT vocabs, and a zero
        // attention mask keeps them out of the mean pooling downstream.
        return Encoded(ids, mask, LongArray(length))
    }

    fun encode(text: String, maxLen: Int = 256): Encoded =
        pad(tokenize(text, maxLen), maxLen)

    /**
     * BERT's `_clean_text`: drop null/replacement/control characters outright,
     * and collapse every Unicode space separator to a plain space.
     *
     * The two halves behave differently and both matter. Control and format
     * characters (category C*, e.g. U+200B zero-width space) are *deleted*, so
     * "zero<ZWSP>width" becomes one word that subword-splits. Space separators
     * (category Zs, e.g. U+00A0 non-breaking space) become real spaces and
     * therefore split words. Java's `Character.isWhitespace` disagrees with
     * BERT here — it returns false for U+00A0 — so relying on it silently
     * fuses words into a single [UNK].
     */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD -> Unit
                isControl(cp) -> Unit
                isBertWhitespace(cp) -> sb.append(' ')
                else -> sb.append(text, i, i + n)
            }
            i += n
        }
        return sb.toString()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt() -> true
            else -> false
        }
    }

    private fun isBertWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** Lowercase, strip accents, split on whitespace, punctuation, and CJK. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        val stripped = Normalizer.normalize(cleaned.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        val out = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
        }

        // Iterate code points, not chars: the higher CJK planes are surrogate
        // pairs, and splitting those mid-pair produces garbage.
        var i = 0
        while (i < stripped.length) {
            val cp = stripped.codePointAt(i)
            val charCount = Character.charCount(cp)
            val segment = stripped.substring(i, i + charCount)
            when {
                Character.isWhitespace(cp) -> flush()
                isPunctuation(cp) || isCjkIdeograph(cp) -> {
                    flush()
                    out.add(segment)
                }
                else -> current.append(segment)
            }
            i += charCount
        }
        flush()
        return out
    }

    /**
     * BERT surrounds CJK *ideographs* with whitespace so each becomes its own
     * word. Note what's excluded: hiragana (U+3040–309F) and katakana
     * (U+30A0–30FF) are deliberately absent from these ranges, so "のテキスト"
     * stays a single word and subword-splits, while "日本語" does not. Getting
     * this wrong is invisible in Latin text and only shows up on CJK notes.
     */
    private fun isCjkIdeograph(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F)

    private fun isPunctuation(code: Int): Boolean {
        // BERT treats the ASCII punctuation ranges as punctuation regardless of
        // what Unicode says, then falls back to Unicode categories.
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(code).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    /** Greedy longest-match-first subword split, `##` marking continuations. */
    private fun wordPiece(word: String, maxCharsPerWord: Int = 100): List<Int> {
        if (word.length > maxCharsPerWord) return listOf(unkId)

        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: Int? = null
            while (start < end) {
                val piece = if (start == 0) word.substring(start, end)
                            else "##" + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) { match = id; break }
                end--
            }
            if (match == null) return listOf(unkId) // whole word is OOV, as BERT does
            out.add(match)
            start = end
        }
        return out
    }
}
