package dev.loam.core.domain

import dev.loam.core.llm.GenerationParams
import dev.loam.core.llm.LlmEngine
import dev.loam.core.llm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Retrieve-then-generate: find the chunks that answer a question, then let a
 * local model write the answer from those chunks and nothing else.
 *
 * The retrieval half is Phase 1's, unchanged — same embedding model, same
 * calibrated threshold. What is added here is a budget, a prompt, and two
 * refusals.
 *
 * ### Sources are emitted before the answer, deliberately
 *
 * Generation takes 8–10 s to produce its first token on a Pixel 8a, and the
 * sources are known the instant retrieval finishes. Emitting them first turns
 * that wait into something to read, and means the citations cannot be
 * post-hoc rationalisation of whatever the model happened to say — they are
 * the exact text it was given, published before it spoke. That ordering is
 * Core principle #4 expressed as a data flow rather than a promise.
 *
 * ### Two different refusals
 *
 * [Event.NoGoodMatches] fires when nothing clears the relevance floor. The
 * model is never invoked: there is nothing to ground an answer in, and asking
 * a 1.5B model to answer unaided is exactly how a note-search tool starts
 * inventing your notes back to you. It is also instant instead of ten seconds.
 *
 * The model may still answer "I could not find that in your notes" when chunks
 * were retrieved but do not contain the answer. Both refusals are wanted; they
 * catch different failures, and only the first is free.
 */
class AskQuestion(
    /**
     * Ranked chunks for a question. Narrower than [SearchNotes] on purpose:
     * this use case needs ranking and nothing else, and depending on the whole
     * thing would drag a Context and an encrypted database into every test of
     * prompt budgeting.
     */
    private val retriever: Retriever,
    /** Null until the user has chosen a model file. Not an error state. */
    private val engine: () -> LlmEngine?,
) {

    fun interface Retriever {
        suspend fun retrieve(question: String, limit: Int): List<SearchNotes.Result>
    }

    sealed interface Event {
        /** The chunks the answer will be written from, in rank order. */
        data class Sources(val sources: List<Source>) : Event

        /** A fragment of the answer. Concatenate in order. */
        data class Token(val text: String) : Event

        /** Retrieval found nothing worth answering from. */
        data object NoGoodMatches : Event

        /** No model file chosen yet — a setup state, not a failure. */
        data object NoModel : Event
    }

    data class Source(
        val chunkId: Long,
        val score: Float,
        val displayName: String,
        val relativePath: String,
        val headingPath: String,
        val snippet: String,
        val uri: String,
    )

    fun ask(question: String, params: GenerationParams = GenerationParams()): Flow<Event> = flow {
        if (question.isBlank()) return@flow

        val llm = engine() ?: run {
            emit(Event.NoModel)
            return@flow
        }

        val hits = retriever.retrieve(question, RETRIEVE_LIMIT)
        if (hits.isEmpty()) {
            emit(Event.NoGoodMatches)
            return@flow
        }

        val used = fit(llm, question, hits, params.maxTokens)
        if (used.isEmpty()) {
            // Everything retrieved was too large to fit alongside the question
            // and a reply. Rare, but claiming "no good matches" would be a lie
            // about relevance when the real problem is size.
            emit(Event.NoGoodMatches)
            return@flow
        }

        // Only the chunks that survived budgeting. Citing a chunk that was
        // dropped would attribute the answer to evidence the model never saw.
        emit(Event.Sources(used.map(::toSource)))

        llm.generate(messagesFor(question, used), params).collect { emit(Event.Token(it)) }
    }

    /**
     * Packs the highest-scoring chunks into whatever the context window has
     * left after the instructions, the question, and room to reply.
     *
     * Budgeted with the *engine's* tokenizer. The embedding model's WordPiece
     * count would be the convenient thing to reach for and would be wrong — a
     * different vocabulary disagrees enough to overflow a window, which is how
     * Phase 1 lost 14% of the vault to silent truncation.
     */
    private fun fit(
        llm: LlmEngine,
        question: String,
        hits: List<SearchNotes.Result>,
        replyTokens: Int,
    ): List<SearchNotes.Result> {
        val overhead = llm.countTokens(SYSTEM_PROMPT) +
            llm.countTokens(question) +
            replyTokens +
            FRAMING_TOKENS
        var remaining = llm.info.contextTokens - overhead
        if (remaining <= 0) return emptyList()

        val used = ArrayList<SearchNotes.Result>(hits.size)
        for (hit in hits) {
            val cost = llm.countTokens(render(hit))
            if (cost > remaining) {
                // Skip rather than stop: a long chunk near the top should not
                // shut out several short ones ranked just below it.
                continue
            }
            used += hit
            remaining -= cost
            if (used.size >= MAX_CHUNKS) break
        }
        return used
    }

    private fun messagesFor(question: String, used: List<SearchNotes.Result>) = listOf(
        Message(Message.Role.SYSTEM, SYSTEM_PROMPT),
        Message(
            Message.Role.USER,
            buildString {
                used.forEach { append(render(it)).append('\n') }
                append("\nQuestion: ").append(question)
            },
        ),
    )

    /** One chunk, labelled well enough that the model can cite it by name. */
    private fun render(hit: SearchNotes.Result): String = buildString {
        append("--- NOTE: ").append(hit.displayName.removeSuffix(".md"))
        if (hit.headingPath.isNotBlank()) append(" › ").append(hit.headingPath)
        append(" ---\n")
        append(hit.text.trim()).append('\n')
    }

    private fun toSource(hit: SearchNotes.Result) = Source(
        chunkId = hit.chunkId,
        score = hit.score,
        displayName = hit.displayName,
        relativePath = hit.relativePath,
        headingPath = hit.headingPath,
        snippet = hit.snippet,
        uri = hit.uri,
    )

    companion object {
        /**
         * Ask for more than can be used, then let the budget decide.
         * Over-retrieving is nearly free — the scan is a few milliseconds — and
         * it means a chunk skipped for size is replaced rather than lost.
         */
        const val RETRIEVE_LIMIT = 12

        /**
         * A ceiling independent of the token budget. More context is not
         * monotonically better: small models attend worse across long inputs,
         * and every extra chunk costs ~1.5 s of prompt processing on a Pixel
         * 8a. Grounding versus latency, set deliberately.
         */
        const val MAX_CHUNKS = 6

        /** Slack for chat-template markup, separators and the role scaffold. */
        private const val FRAMING_TOKENS = 64

        /**
         * Kept blunt on purpose. The spike answered correctly with wording
         * close to this, and elaborate instructions gave a 1.5B model more to
         * misread rather than more to obey.
         */
        internal val SYSTEM_PROMPT = """
            You answer questions about the user's personal notes.

            Use ONLY the notes provided in the message. Do not use outside
            knowledge, and do not guess. If the notes do not contain the answer,
            reply exactly: I could not find that in your notes.

            Cite the note titles you used. Be concise.
        """.trimIndent()
    }
}
