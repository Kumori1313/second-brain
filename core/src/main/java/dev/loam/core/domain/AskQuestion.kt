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
    /**
     * A ceiling on chunks per answer, independent of the token budget.
     *
     * More context is not monotonically better: small models attend worse
     * across long inputs, and every extra chunk costs ~1.5 s of prompt
     * processing on a Pixel 8a. Read per question rather than captured, so
     * changing it in settings applies to the next answer.
     */
    private val maxChunks: () -> Int = { Tuning.DEFAULT_CHUNKS_PER_ANSWER },
    /**
     * Null until the user has chosen a model file — a setup state, not an
     * error. Suspending because the first call may open a gigabyte of weights,
     * and a plain getter would hide that behind what looks like a field read.
     * A model that fails to load throws rather than returning null: "not
     * chosen" and "chosen but broken" need different answers from the UI.
     */
    private val engine: suspend () -> LlmEngine?,
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

    /** One completed exchange. Carries text only — see [messagesFor]. */
    data class Turn(val question: String, val answer: String)

    fun ask(
        question: String,
        history: List<Turn> = emptyList(),
        params: GenerationParams = GenerationParams(),
    ): Flow<Event> = flow {
        if (question.isBlank()) return@flow

        val llm = engine() ?: run {
            emit(Event.NoModel)
            return@flow
        }

        val hits = retriever.retrieve(retrievalQuery(question, history), RETRIEVE_LIMIT)
        if (hits.isEmpty()) {
            emit(Event.NoGoodMatches)
            return@flow
        }

        val recent = trimHistory(llm, history, question, params.maxTokens)
        val used = fit(llm, question, hits, params.maxTokens, recent)
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

        llm.generate(messagesFor(question, used, recent), params)
            .collect { emit(Event.Token(it)) }
    }

    /**
     * What gets embedded for retrieval, which is not always the question.
     *
     * A follow-up is frequently unsearchable on its own — "and why that one?"
     * has no content to match against. Prepending the previous question
     * restores the subject cheaply, without the extra generation pass a proper
     * query rewrite would cost, and that pass would add seconds to a feature
     * already waiting ~9 s for its first token.
     *
     * Only the immediately preceding question, and only its text. Going further
     * back starts dragging retrieval toward whatever the conversation used to
     * be about, which is worse than a vague follow-up.
     */
    private fun retrievalQuery(question: String, history: List<Turn>): String =
        history.lastOrNull()?.let { "${it.question}\n$question" } ?: question

    /**
     * Keeps the most recent turns that fit a reserved slice of the window.
     *
     * History and retrieved chunks compete for the same space, and chunks win:
     * grounding is the whole product, so history gets a bounded reserve rather
     * than an equal claim. Oldest turns are dropped first — a follow-up refers
     * to what was just said, not to the start of the conversation.
     */
    private fun trimHistory(
        llm: LlmEngine,
        history: List<Turn>,
        question: String,
        replyTokens: Int,
    ): List<Turn> {
        if (history.isEmpty()) return emptyList()

        val free = llm.info.contextTokens -
            llm.countTokens(SYSTEM_PROMPT) -
            llm.countTokens(question) -
            replyTokens -
            FRAMING_TOKENS
        if (free <= 0) return emptyList()

        var budget = (free * HISTORY_SHARE).toInt()
        val kept = ArrayDeque<Turn>()
        for (turn in history.takeLast(MAX_HISTORY_TURNS).asReversed()) {
            val cost = llm.countTokens(turn.question) + llm.countTokens(turn.answer) + TURN_FRAMING
            if (cost > budget) break
            budget -= cost
            kept.addFirst(turn)
        }
        return kept.toList()
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
        history: List<Turn>,
    ): List<SearchNotes.Result> {
        val historyCost = history.sumOf {
            llm.countTokens(it.question) + llm.countTokens(it.answer) + TURN_FRAMING
        }
        val overhead = llm.countTokens(SYSTEM_PROMPT) +
            llm.countTokens(question) +
            replyTokens +
            historyCost +
            FRAMING_TOKENS
        var remaining = llm.info.contextTokens - overhead
        if (remaining <= 0) return emptyList()

        val cap = maxChunks().coerceAtLeast(1)

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
            if (used.size >= cap) break
        }
        return used
    }

    /**
     * System prompt, then prior turns as plain exchanges, then the notes and the
     * question.
     *
     * Prior turns carry their question and answer but **not** their source
     * chunks. Re-including old evidence would consume the window several times
     * over for the same notes, and the answer text already records what mattered
     * from them. Fresh retrieval runs every turn, so a follow-up that needs
     * different notes gets them.
     */
    private fun messagesFor(
        question: String,
        used: List<SearchNotes.Result>,
        history: List<Turn>,
    ): List<Message> = buildList {
        add(Message(Message.Role.SYSTEM, SYSTEM_PROMPT))
        history.forEach { turn ->
            add(Message(Message.Role.USER, turn.question))
            add(Message(Message.Role.ASSISTANT, turn.answer))
        }
        add(
            Message(
                Message.Role.USER,
                buildString {
                    used.forEach { append(render(it)).append('\n') }
                    append("\nQuestion: ").append(question)
                },
            )
        )
    }

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

        /** Slack for chat-template markup, separators and the role scaffold. */
        private const val FRAMING_TOKENS = 64

        /** Per-turn chat-template overhead, on top of the text itself. */
        private const val TURN_FRAMING = 8

        /**
         * How much of the free window history may claim before chunks get the
         * rest. History helps a follow-up resolve its referent; chunks are what
         * the answer is grounded in, so they get the larger share.
         */
        private const val HISTORY_SHARE = 0.35

        /**
         * Turns carried at most, regardless of budget.
         *
         * Every turn is re-fed on each question, since the KV cache is cleared
         * between them, so history costs prompt processing on every exchange —
         * roughly 1.5 s per chunk-equivalent. An unbounded transcript would make
         * the tenth question far slower than the first.
         */
        private const val MAX_HISTORY_TURNS = 4

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
