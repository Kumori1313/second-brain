package dev.loam.spike

import java.io.File
import java.text.Normalizer

/**
 * Minimal BERT-uncased WordPiece tokenizer, enough to feed MiniLM-L6-v2.
 *
 * This is deliberately hand-rolled rather than pulled from a library: the
 * HuggingFace tokenizers binding is a heavy native dependency, and the whole
 * point of the spike is to find out what the real cost of on-device embedding
 * is without dragging in things we might not ship.
 *
 * Correctness note: a tokenizer that is subtly wrong does not throw — it
 * silently produces plausible-looking embeddings that retrieve badly. Verify
 * against a known-good reference (e.g. Python `transformers` on the same
 * string) before trusting any retrieval quality numbers measured here.
 */
class WordPieceTokenizer(vocabFile: File) {

    private val vocab: Map<String, Int> = buildMap {
        vocabFile.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, token -> put(token, index) }
        }
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

    fun encode(text: String, maxLen: Int = 256): Encoded {
        val pieces = ArrayList<Int>(maxLen)
        pieces.add(clsId)

        for (word in basicTokenize(text)) {
            if (pieces.size >= maxLen - 1) break
            for (piece in wordPiece(word)) {
                if (pieces.size >= maxLen - 1) break
                pieces.add(piece)
            }
        }
        pieces.add(sepId)

        val ids = LongArray(maxLen)
        val mask = LongArray(maxLen)
        for (i in pieces.indices) {
            ids[i] = pieces[i].toLong()
            mask[i] = 1L
        }
        // Remaining entries stay 0 — [PAD] is id 0 in BERT vocabs, and a zero
        // attention mask keeps them out of the mean pooling downstream.
        return Encoded(ids, mask, LongArray(maxLen))
    }

    /** Lowercase, strip accents, split on whitespace and punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in stripped) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                    out.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        // BERT treats the ASCII punctuation ranges as punctuation regardless of
        // what Unicode says, then falls back to Unicode categories.
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(ch).toByte()) {
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
