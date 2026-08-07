package dev.loam.core.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the [LlmEngine] contract that callers depend on, using the
 * scripted double.
 *
 * These are the behaviours a second implementation would have to reproduce, so
 * the test doubles as the specification: if the JNI engine ever replaces the
 * scripted one here, anything that breaks is a real incompatibility rather
 * than a detail.
 */
class LlmEngineContractTest {

    private val messages = listOf(
        Message(Message.Role.SYSTEM, "Answer from the notes only."),
        Message(Message.Role.USER, "What cipher does my LUKS setup use?"),
    )

    @Test
    fun `streams the answer in fragments that concatenate to the whole`() = runTest {
        val engine = ScriptedLlmEngine(answer = "aes-xts-plain64 with argon2id.")

        val pieces = engine.generate(messages).toList()

        assertTrue("expected more than one fragment", pieces.size > 1)
        assertEquals("aes-xts-plain64 with argon2id.", pieces.joinToString(""))
    }

    @Test
    fun `generation is cold - nothing runs until collected`() = runTest {
        val engine = ScriptedLlmEngine()

        engine.generate(messages)

        // Building the flow must not invoke the model. On the real engine this
        // is the difference between composing a request and spending 8 seconds
        // of prompt processing.
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `abandoning collection stops generation early`() = runTest {
        val engine = ScriptedLlmEngine(answer = "one two three four five six seven")

        val partial = engine.generate(messages).take(2).toList()

        assertEquals(2, partial.size)
        // The real cost of not honouring this is a model that keeps generating
        // to maxTokens after the user has navigated away.
        assertTrue(partial.joinToString("").length < "one two three four five six seven".length)
    }

    @Test
    fun `concurrent callers are serialized rather than interleaved`() = runTest {
        val engine = ScriptedLlmEngine(answer = "abcdefghijkl", tokenDelayMs = 1)

        val first = async { engine.generate(messages).toList() }
        val second = async { engine.generate(messages).toList() }

        // Both complete, both intact. Native state is not safe for concurrent
        // use, so the contract is that the second waits, not that it fails.
        withTimeout(5_000) {
            assertEquals("abcdefghijkl", first.await().joinToString(""))
            assertEquals("abcdefghijkl", second.await().joinToString(""))
        }
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `maxTokens bounds the response`() = runTest {
        val engine = ScriptedLlmEngine(answer = "a".repeat(300))

        val pieces = engine.generate(messages, GenerationParams(maxTokens = 4)).toList()

        assertEquals(4, pieces.size)
    }

    @Test
    fun `messages reach the engine in order with roles intact`() = runTest {
        val engine = ScriptedLlmEngine()

        engine.generate(messages).toList()

        val sent = engine.calls.single()
        assertEquals(Message.Role.SYSTEM, sent.first().role)
        assertEquals(Message.Role.USER, sent.last().role)
        assertEquals("What cipher does my LUKS setup use?", sent.last().content)
    }

    @Test
    fun `token counting is the engine's own, not the embedder's`() {
        val engine = ScriptedLlmEngine()

        // The point of the assertion is that the count comes from the engine at
        // all. Budgeting retrieved chunks against the embedding model's
        // WordPiece count would repeat Phase 1's truncation bug in a new place.
        assertEquals(5, engine.countTokens("one two three four five"))
    }

    @Test
    fun `close is observable so native resources can be released`() {
        val engine = ScriptedLlmEngine()
        assertFalse(engine.closed)

        engine.close()

        assertTrue(engine.closed)
    }
}
