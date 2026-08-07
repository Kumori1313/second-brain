package dev.loam.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * A local text-generation model, narrow enough that the runtime behind it stays
 * replaceable.
 *
 * Phase 2 ships llama.cpp over JNI. The roadmap keeps a second track open — a
 * Rust core via UniFFI — and the point of this interface is that choosing wrong
 * costs one implementation rather than a rewrite. Everything above it (chunk
 * retrieval, prompt assembly, the sources panel) is plain Kotlin and knows
 * nothing about the engine.
 *
 * ### Why messages instead of a prompt string
 *
 * Instruction-tuned models each want their own chat template, and getting it
 * wrong degrades answers quietly rather than failing. That formatting is a
 * property of the model, so it belongs to the implementation — which can ask
 * the GGUF for its own template — not to the caller assembling a RAG prompt.
 *
 * ### Threading
 *
 * Implementations are expected to be backed by native state that is not safe
 * for concurrent use, so [generate] serializes: a second call waits rather than
 * corrupting the first. Callers should not need their own lock.
 */
interface LlmEngine : AutoCloseable {

    /** What is loaded, for display and for budgeting a prompt against it. */
    val info: ModelInfo

    /**
     * Token count according to *this model's* tokenizer.
     *
     * Deliberately not [dev.loam.core.vault.TokenCounter], which counts
     * WordPiece tokens for the embedding model. The two disagree — different
     * vocabularies, different merges — and using the embedder's count to decide
     * how many chunks fit in the LLM's window is the same class of mistake as
     * sizing chunks by characters, which silently truncated 14% of the vault in
     * Phase 1. Budget with the tokenizer that will actually see the text.
     */
    fun countTokens(text: String): Int

    /**
     * Streams the answer token by token.
     *
     * A cold [Flow]: generation starts on collection and stops when collection
     * is cancelled, so abandoning an answer releases the model promptly rather
     * than running to [GenerationParams.maxTokens] in the background.
     *
     * Streaming is not a nicety. Measured on a Pixel 8a with Qwen2.5-1.5B Q4_0,
     * a RAG prompt of ~1,100 tokens takes 8–10 s before the first token, then
     * produces 18–27 tokens/sec. Waiting for the whole answer would read as a
     * hang; streaming turns the same latency into visible progress.
     *
     * Emissions are text fragments, not whole words — a token may be a word
     * piece, punctuation, or part of a multi-byte character. Concatenate in
     * order; do not assume anything about the boundaries.
     */
    fun generate(
        messages: List<Message>,
        params: GenerationParams = GenerationParams(),
    ): Flow<String>
}

/**
 * @param contextTokens the model's window. Prompt *and* answer share it, so a
 *   caller budgeting retrieved chunks has to leave room for the response.
 */
data class ModelInfo(
    val name: String,
    val contextTokens: Int,
    val parameterCount: Long? = null,
    val quantization: String? = null,
)

data class Message(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * @param temperature low by default, and deliberately. Loam's answers are meant
 *   to be grounded in retrieved notes; sampling creatively is how a RAG system
 *   starts embellishing its sources. The Phase 2 spike answered correctly at
 *   0.2 and that is the value carried forward.
 * @param maxTokens a ceiling, not a target — generation also stops at the
 *   model's end-of-turn token.
 * @param seed fixed value makes a run reproducible, which matters for testing
 *   retrieval changes without sampling noise confounding the comparison.
 */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.2f,
    val seed: Int? = null,
)

/**
 * Thrown when a model cannot be opened — missing file, revoked SAF grant,
 * unsupported or corrupt GGUF.
 *
 * Worth distinguishing from "no model chosen yet", which is not an error and is
 * represented by simply having no engine.
 */
class ModelLoadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
