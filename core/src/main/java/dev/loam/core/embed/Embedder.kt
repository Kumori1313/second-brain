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

    fun embed(text: String, maxLen: Int = MAX_LEN): FloatArray {
        val enc = tokenizer.encode(text, maxLen)
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
         * Token budget per chunk. Everything past this is dropped, which is why
         * the chunker enforces a hard character ceiling rather than a target.
         */
        const val MAX_LEN = 256
    }
}
