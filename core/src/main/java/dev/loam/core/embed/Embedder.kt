package dev.loam.core.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * MiniLM-L6-v2 over ONNX Runtime: tokenize, run, mean-pool, L2 normalize.
 *
 * Mean pooling — not the `[CLS]` token — is what sentence-transformers does for
 * this model, and the difference is a silent quality bug rather than a crash.
 * Verified against a desktop reference: `[CLS]` scores a related pair *higher*
 * (0.645 vs 0.250) while halving the margin over an unrelated pair (0.073 vs
 * 0.188). The wrong pooling looks better by the obvious metric and retrieves
 * worse.
 *
 * Vectors come back L2-normalized, so cosine similarity downstream is a plain
 * dot product.
 *
 * ONNX Runtime is Microsoft's, not Google's — deliberate, per Core principle #3
 * (the target device may have no Play Services at all).
 */
class Embedder(
    modelFile: File,
    private val tokenizer: WordPieceTokenizer,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Load by path rather than by byte array: ORT maps the file, so the 22MB of
    // weights never sits on the Java heap.
    private val session: OrtSession =
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

    /** Input names vary between exports; resolve rather than assume. */
    private val inputNames: Set<String> = session.inputNames

    /** Cumulative cost split, so a slow index can be attributed to a stage. */
    var tokenizeNanos: Long = 0L
        private set
    var inferNanos: Long = 0L
        private set

    fun embed(text: String, maxLen: Int = MAX_LEN): FloatArray {
        val tokStart = System.nanoTime()
        val tokens = tokenizer.tokenize(text, maxLen)
        val padTo = bucketFor(tokens.size, maxLen)
        val enc = tokenizer.pad(tokens, padTo)
        tokenizeNanos += System.nanoTime() - tokStart

        val inferStart = System.nanoTime()
        try {
            return runGraph(enc, padTo)
        } finally {
            inferNanos += System.nanoTime() - inferStart
        }
    }

    private fun runGraph(enc: WordPieceTokenizer.Encoded, maxLen: Int): FloatArray {
        val shape = longArrayOf(1, maxLen.toLong())

        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
        val types = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)

        val inputs = buildMap {
            put("input_ids", ids)
            put("attention_mask", mask)
            // Some MiniLM exports drop token_type_ids entirely; passing an
            // input the graph doesn't declare makes ORT reject the run.
            if ("token_type_ids" in inputNames) put("token_type_ids", types)
        }

        try {
            session.run(inputs).use { results ->
                @Suppress("UNCHECKED_CAST")
                val hidden = results[0].value as Array<Array<FloatArray>>
                return normalize(meanPool(hidden[0], enc.attentionMask))
            }
        } finally {
            ids.close(); mask.close(); types.close()
        }
    }

    /** Average token vectors, counting only real (unmasked) tokens. */
    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = tokens[0].size
        val sum = FloatArray(dim)
        var count = 0
        for (i in tokens.indices) {
            if (mask[i] == 0L) continue
            count++
            val row = tokens[i]
            for (d in 0 until dim) sum[d] += row[d]
        }
        if (count > 0) for (d in 0 until dim) sum[d] /= count
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Output width of MiniLM-L6-v2. */
        const val DIMENSIONS = 384

        /**
         * Token budget per input. Everything past this is dropped.
         *
         * The chunker sizes by characters at roughly 4 per token, but this
         * vault measures 3.46 — code, paths and URLs tokenize far denser than
         * prose — so 28% of chunks currently overflow and 14% of the vault's
         * text never reaches the model. Sizing chunks by real token count is
         * the fix; see the roadmap's Phase 3 notes.
         */
        const val MAX_LEN = 256

        /**
         * Inputs are padded up to a multiple of this rather than to [MAX_LEN].
         *
         * The graph's axes are dynamic (`['batch_size', 'sequence_length']`)
         * and attention is O(n²), so padding a short input to 256 buys nothing
         * but work: desktop-measured, 64 tokens costs 4.3 ms against 11.4 ms at
         * 256. Bucketing rather than using the exact length keeps the number of
         * distinct shapes small, so ONNX Runtime can reuse execution plans and
         * memory arenas instead of re-planning per input.
         *
         * Worth 21% of padded work while indexing this vault, and far more on
         * queries — a search phrase is ~8 tokens, so it pads to 32 rather than
         * 256.
         */
        const val BUCKET = 32

        /**
         * Padding is not perfectly neutral on the INT8 graph: the same text
         * padded to its own length versus to 256 measures 0.9988 mean cosine,
         * because the mask is applied as a large negative bias rather than a
         * true -inf and quantization noise rides along with it. That is well
         * inside the margin between a real hit (~0.66) and noise (~0.19), but
         * it does mean vectors are only comparable to others built the same
         * way. Changing this constant invalidates an existing index, which is
         * why LoamDatabase's version was bumped alongside it.
         */
        fun bucketFor(tokens: Int, maxLen: Int): Int =
            minOf(maxLen, maxOf(BUCKET, ((tokens + BUCKET - 1) / BUCKET) * BUCKET))
    }
}
