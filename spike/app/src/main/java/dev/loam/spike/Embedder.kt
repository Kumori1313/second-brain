package dev.loam.spike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Wraps an ONNX MiniLM-L6-v2 session: tokenize -> run -> mean-pool -> L2 normalize.
 *
 * Mean pooling (not the [CLS] token) is what sentence-transformers does for this
 * model. Using [CLS] instead is a classic silent-quality bug — it runs fine and
 * returns worse vectors.
 */
class Embedder(
    modelFile: File,
    private val tokenizer: WordPieceTokenizer,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(modelFile.readBytes(), OrtSession.SessionOptions())

    /** Input names vary slightly between exports; resolve rather than assume. */
    private val inputNames: Set<String> = session.inputNames

    val dimensions: Int by lazy { embed("dimension probe").size }

    data class Timed(val vector: FloatArray, val millis: Long)

    fun embedTimed(text: String, maxLen: Int = 256): Timed {
        val start = System.nanoTime()
        val vec = embed(text, maxLen)
        return Timed(vec, (System.nanoTime() - start) / 1_000_000)
    }

    fun embed(text: String, maxLen: Int = 256): FloatArray {
        val enc = tokenizer.encode(text, maxLen)
        val shape = longArrayOf(1, maxLen.toLong())

        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
        val types = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)

        val inputs = buildMap {
            put("input_ids", ids)
            put("attention_mask", mask)
            // Some MiniLM exports drop token_type_ids entirely; only pass it if
            // the graph actually declares it, or ORT rejects the run.
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
}
