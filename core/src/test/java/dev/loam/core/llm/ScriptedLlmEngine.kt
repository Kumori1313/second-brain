package dev.loam.core.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [LlmEngine] that replays a fixed answer, so everything above the engine
 * can be built and tested before any native code exists.
 *
 * This is not a mock in the "assert it was called" sense. It reproduces the
 * behaviours the real engine has that callers actually have to handle —
 * streaming in fragments, honouring cancellation mid-answer, and serializing
 * concurrent callers — because those are what break integration code, not the
 * text that comes back.
 *
 * Token counting is whitespace-based and deliberately crude. It exists so
 * prompt-budgeting logic can be exercised, not to imitate a real tokenizer; a
 * test that depends on the exact count is testing this class rather than the
 * code under test.
 */
class ScriptedLlmEngine(
    private val answer: String = "A scripted answer.",
    override val info: ModelInfo = ModelInfo(
        name = "scripted",
        contextTokens = 4096,
    ),
    /** Simulates per-token latency, so cancellation has a window to land in. */
    private val tokenDelayMs: Long = 0,
) : LlmEngine {

    /** Every [generate] call, in order, for asserting on what was sent. */
    val calls = mutableListOf<List<Message>>()

    var closed: Boolean = false
        private set

    /** Mirrors the real engine: native state, one caller at a time. */
    private val mutex = Mutex()

    override fun countTokens(text: String): Int =
        text.split(WHITESPACE).count { it.isNotEmpty() }

    override fun generate(
        messages: List<Message>,
        params: GenerationParams,
    ): Flow<String> = flow {
        mutex.withLock {
            calls += messages
            // Fragments rather than whole words, because a real tokenizer emits
            // word pieces and callers must not assume token boundaries align
            // with anything meaningful.
            answer.chunked(FRAGMENT).take(params.maxTokens).forEach { fragment ->
                if (tokenDelayMs > 0) delay(tokenDelayMs)
                emit(fragment)
            }
        }
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val FRAGMENT = 3
    }
}
