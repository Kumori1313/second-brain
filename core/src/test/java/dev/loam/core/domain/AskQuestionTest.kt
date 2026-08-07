package dev.loam.core.domain

import dev.loam.core.llm.GenerationParams
import dev.loam.core.llm.Message
import dev.loam.core.llm.ModelInfo
import dev.loam.core.llm.ScriptedLlmEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskQuestionTest {

    private fun hit(
        id: Long,
        score: Float,
        name: String = "Note $id",
        heading: String = "Heading $id",
        text: String = "body of chunk $id",
    ) = SearchNotes.Result(
        chunkId = id,
        score = score,
        displayName = "$name.md",
        relativePath = "notes/$name.md",
        headingPath = heading,
        snippet = text.take(20),
        text = text,
        uri = "content://vault/$id",
    )

    private fun ask(
        hits: List<SearchNotes.Result>,
        engine: ScriptedLlmEngine? = ScriptedLlmEngine(answer = "Grounded answer."),
    ) = AskQuestion(
        retriever = { _, _ -> hits },
        engine = { engine },
    )

    @Test
    fun `emits sources before any answer text`() = runTest {
        val events = ask(listOf(hit(1, 0.7f), hit(2, 0.6f))).ask("why?").toList()

        // The ordering is the auditability guarantee: citations are published
        // before the model speaks, so they cannot be shaped by what it said.
        assertTrue(events.first() is AskQuestion.Event.Sources)
        assertTrue(events.drop(1).all { it is AskQuestion.Event.Token })
    }

    @Test
    fun `sources carry rank order and scores`() = runTest {
        val events = ask(listOf(hit(1, 0.71f), hit(2, 0.64f))).ask("why?").toList()

        val sources = (events.first() as AskQuestion.Event.Sources).sources
        assertEquals(listOf(1L, 2L), sources.map { it.chunkId })
        assertEquals(0.71f, sources.first().score, 1e-6f)
    }

    @Test
    fun `answer text concatenates from the token events`() = runTest {
        val events = ask(listOf(hit(1, 0.7f))).ask("why?").toList()

        val answer = events.filterIsInstance<AskQuestion.Event.Token>()
            .joinToString("") { it.text }
        assertEquals("Grounded answer.", answer)
    }

    @Test
    fun `no retrieval hits refuses without invoking the model`() = runTest {
        val engine = ScriptedLlmEngine()

        val events = ask(emptyList(), engine).ask("banana bread?").toList()

        assertEquals(listOf(AskQuestion.Event.NoGoodMatches), events)
        // Not merely "it refused" — the model must never run. Answering
        // unaided is how a notes tool starts inventing notes, and skipping it
        // is also the difference between instant and ten seconds.
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `no model chosen is reported distinctly from no matches`() = runTest {
        val events = AskQuestion(retriever = { _, _ -> listOf(hit(1, 0.7f)) }, engine = { null })
            .ask("why?")
            .toList()

        assertEquals(listOf(AskQuestion.Event.NoModel), events)
    }

    @Test
    fun `blank question does nothing at all`() = runTest {
        val engine = ScriptedLlmEngine()

        val events = ask(listOf(hit(1, 0.7f)), engine).ask("   ").toList()

        assertTrue(events.isEmpty())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `model receives the full chunk text, not the display snippet`() = runTest {
        val long = "x".repeat(500)
        val engine = ScriptedLlmEngine()

        ask(listOf(hit(1, 0.7f, text = long)), engine).ask("why?").toList()

        val user = engine.calls.single().last { it.role == Message.Role.USER }
        // Grounding an answer in a truncated snippet would look sound and be
        // wrong, which is the worst failure mode this feature has.
        assertTrue(user.content.contains(long))
    }

    @Test
    fun `prompt is labelled so the model can cite by name`() = runTest {
        val engine = ScriptedLlmEngine()

        ask(listOf(hit(1, 0.7f, name = "LUKS Setup", heading = "8. Encryption")), engine)
            .ask("which cipher?")
            .toList()

        val user = engine.calls.single().last { it.role == Message.Role.USER }
        assertTrue(user.content.contains("LUKS Setup"))
        assertTrue(user.content.contains("8. Encryption"))
        assertTrue(user.content.contains("Question: which cipher?"))
        // The ".md" is noise in a citation.
        assertFalse(user.content.contains("LUKS Setup.md"))
    }

    @Test
    fun `system prompt is sent as a system message`() = runTest {
        val engine = ScriptedLlmEngine()

        ask(listOf(hit(1, 0.7f)), engine).ask("why?").toList()

        val first = engine.calls.single().first()
        assertEquals(Message.Role.SYSTEM, first.role)
        assertTrue(first.content.contains("I could not find that in your notes"))
    }

    @Test
    fun `chunk count is capped even when everything fits`() = runTest {
        val engine = ScriptedLlmEngine(info = ModelInfo("big", contextTokens = 1_000_000))
        val many = (1L..20L).map { hit(it, 0.9f - it * 0.01f) }

        val events = ask(many, engine).ask("why?").toList()

        val sources = (events.first() as AskQuestion.Event.Sources).sources
        // More context is not monotonically better, and each chunk costs
        // ~1.5 s of prompt processing on device.
        assertEquals(AskQuestion.MAX_CHUNKS, sources.size)
    }

    @Test
    fun `chunks that do not fit the context window are dropped`() = runTest {
        // 512-token window with 256 reserved for the reply leaves room for
        // roughly three of these chunks, not six.
        val engine = ScriptedLlmEngine(info = ModelInfo("tiny", contextTokens = 512))
        val hits = (1L..6L).map { hit(it, 0.9f, text = "word ".repeat(30).trim()) }

        val events = ask(hits, engine).ask("why?", GenerationParams(maxTokens = 256)).toList()

        val sources = (events.first() as AskQuestion.Event.Sources).sources
        assertTrue("expected budgeting to drop some chunks", sources.size < 6)
        assertTrue("expected at least one chunk to survive", sources.isNotEmpty())
    }

    @Test
    fun `an oversized chunk is skipped so smaller ones below it still fit`() = runTest {
        val engine = ScriptedLlmEngine(info = ModelInfo("tiny", contextTokens = 512))
        val hits = listOf(
            hit(1, 0.90f, text = "word ".repeat(400).trim()),  // cannot fit
            hit(2, 0.80f, text = "small chunk two"),
            hit(3, 0.70f, text = "small chunk three"),
        )

        val events = ask(hits, engine).ask("why?", GenerationParams(maxTokens = 256)).toList()

        val sources = (events.first() as AskQuestion.Event.Sources).sources
        // Stopping at the first chunk too large would let one long note near
        // the top starve several relevant short ones ranked just below.
        assertEquals(listOf(2L, 3L), sources.map { it.chunkId })
    }

    @Test
    fun `cited sources are exactly the chunks the model was given`() = runTest {
        val engine = ScriptedLlmEngine(info = ModelInfo("tiny", contextTokens = 512))
        val hits = listOf(
            hit(1, 0.90f, name = "Huge", text = "word ".repeat(400).trim()),
            hit(2, 0.80f, name = "Small", text = "small chunk two"),
        )

        val events = ask(hits, engine).ask("why?", GenerationParams(maxTokens = 256)).toList()

        val sources = (events.first() as AskQuestion.Event.Sources).sources
        val user = engine.calls.single().last { it.role == Message.Role.USER }
        // Citing a chunk that was budgeted out would credit the answer to
        // evidence the model never saw.
        assertEquals(listOf(2L), sources.map { it.chunkId })
        assertFalse(user.content.contains("Huge"))
    }

    @Test
    fun `retrieval hits that cannot fit at all report no good matches`() = runTest {
        val engine = ScriptedLlmEngine(info = ModelInfo("cramped", contextTokens = 10))

        val events = ask(listOf(hit(1, 0.9f, text = "word ".repeat(50))), engine).ask("why?").toList()

        assertEquals(listOf(AskQuestion.Event.NoGoodMatches), events)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `generation params reach the engine`() = runTest {
        val engine = ScriptedLlmEngine(answer = "abcdefghijklmnop")

        val events = ask(listOf(hit(1, 0.7f)), engine)
            .ask("why?", GenerationParams(maxTokens = 2))
            .toList()

        assertEquals(2, events.filterIsInstance<AskQuestion.Event.Token>().size)
    }
}
