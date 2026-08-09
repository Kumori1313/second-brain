package dev.loam.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.loam.core.Loam
import dev.loam.core.domain.AskQuestion
import dev.loam.core.domain.SearchNotes
import dev.loam.core.domain.IndexingRules
import dev.loam.core.domain.Tuning
import dev.loam.core.store.KeyProtection
import dev.loam.work.IndexRunLog
import dev.loam.work.IndexWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val loam = Loam.get(app)

    data class UiState(
        val hasVault: Boolean = false,
        val query: String = "",
        val results: List<SearchNotes.Result> = emptyList(),
        val searching: Boolean = false,
        /** Distinguishes "nothing matched" from "haven't searched yet". */
        val searched: Boolean = false,
        val indexStatus: String? = null,
        val indexing: Boolean = false,
        val error: String? = null,
        val noteCount: Int = 0,
        val chunkCount: Int = 0,
        val modelName: String? = null,
        val model: ModelState = ModelState.None,
        val ask: AskState = AskState(),
        val tuning: Tuning = Tuning(),
        val rules: IndexingRules = IndexingRules(),
        /** Last path segment of the vault URI — enough to recognise it. */
        val vaultName: String? = null,
        val keyProtection: KeyProtection = KeyProtection.OFF,
        /**
         * The last completed index pass, background or manual, possibly from a
         * previous launch. Rendered as the dated fact it is, not as news.
         */
        val lastRun: IndexRunLog.Run? = null,
        /**
         * These results came from below the relevance floor and are shown
         * because the user asked for them. They must stay labelled: the whole
         * value of the floor is that what clears it means something.
         */
        val weakMatches: Boolean = false,
        /**
         * Bumped each time text arrives from another app. The UI watches it to
         * switch to Search, which matters only when Loam was already open on
         * another tab — the case `singleTask` exists to create.
         */
        val shareToken: Int = 0,
        /**
         * Bumped when something outside the app asks for the search field
         * itself — currently only the home-screen widget.
         */
        val focusToken: Int = 0,
    )

    /**
     * @param sources arrive before any answer text and stay put. Rendering them
     *   during the ~10 s wait is the point: it gives the user something to read,
     *   and it means the citations were fixed before the model spoke.
     */
    data class AskState(
        val question: String = "",
        val asking: Boolean = false,
        val answer: String = "",
        val sources: List<AskQuestion.Source> = emptyList(),
        val outcome: Outcome? = null,
        /** Completed exchanges, oldest first. The one in flight is not here. */
        val turns: List<Exchange> = emptyList(),
    ) {
        /** A finished exchange, with the sources that answer was built from. */
        data class Exchange(
            val question: String,
            val answer: String,
            val sources: List<AskQuestion.Source>,
        )

        sealed interface Outcome {
            /** Retrieval found nothing worth answering from. */
            data object NoGoodMatches : Outcome

            /** No model chosen — a setup step, distinct from a broken one. */
            data object NoModel : Outcome

            data class Failed(val message: String) : Outcome
        }
    }

    /**
     * Distinguishing [None] from [Failed] is what stops a corrupt GGUF reading
     * as "you haven't picked one yet" and bouncing the user back to the picker
     * in a loop.
     */
    sealed interface ModelState {
        data object None : ModelState
        data object Loading : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val _state = MutableStateFlow(
        UiState(
            hasVault = loam.vaultLocation.treeUri != null,
            modelName = loam.modelLocation.displayName,
            tuning = loam.settings.tuning,
            rules = loam.settings.indexing,
            vaultName = loam.vaultLocation.treeUri?.lastPathSegment,
            keyProtection = loam.settings.keyProtection,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeIndexing()
        observeLastRun()
        observeCounts()
        warmUp()
        ensurePeriodicIndexing()
    }

    /**
     * Loads the model when the user reaches the Ask tab, not at startup.
     *
     * It used to warm on launch, by analogy with the embedder. The analogy does
     * not hold: warming the embedder costs ~600 ms and 22 MB, while warming the
     * LLM puts the process at ~2.1 GB PSS — about 849 MB of it the GGUF
     * mapping. Paying that for someone who opened the app to search made Loam a
     * prime candidate for the low-memory killer, which would then also cost
     * them the warm search index.
     *
     * Safe to call on every visit: [Loam.llmEngine] returns the cached engine
     * when one is already open, so this is a no-op after the first time until
     * the app is backgrounded and [Loam.closeEngine] releases it.
     *
     * Correctness does not depend on this running — asking opens the model
     * lazily anyway. It only moves the ~1 s cost off the first question.
     */
    fun onAskOpened() {
        if (loam.modelLocation.modelUri == null) {
            _state.value = _state.value.copy(model = ModelState.None)
            return
        }
        if (loam.isEngineLoaded) {
            _state.value = _state.value.copy(model = ModelState.Ready)
            return
        }
        warmModel()
    }

    /**
     * Opens the model in the background, reporting progress and failure.
     *
     * Failure is surfaced rather than swallowed: an unreadable or corrupt GGUF
     * is a different problem from not having chosen one, and only the user can
     * fix either.
     */
    private fun warmModel() {
        if (loam.modelLocation.modelUri == null) return
        _state.value = _state.value.copy(model = ModelState.Loading)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                model = runCatching { loam.llmEngine() }.fold(
                    onSuccess = { if (it == null) ModelState.None else ModelState.Ready },
                    onFailure = { ModelState.Failed(it.message ?: "Could not load model") },
                )
            )
        }
    }

    fun onTuningChange(tuning: Tuning) {
        loam.settings.tuning = tuning
        // Read back rather than trusting the input: Settings clamps to the
        // ranges it will honour, and the UI should show what was stored.
        val stored = loam.settings.tuning
        _state.value = _state.value.copy(tuning = stored)

        // The context window is baked into the llama.cpp context, so it only
        // takes effect on reopen. Releasing here means the next Ask picks it up
        // instead of the change silently doing nothing until a restart.
        if (stored.contextTokens != tuning.contextTokens || loam.isEngineLoaded) {
            viewModelScope.launch {
                loam.closeEngine()
                _state.value = _state.value.copy(model = ModelState.None)
            }
        }
    }

    fun onResetTuning() {
        onTuningChange(Tuning())
        onIndexingRulesChange(IndexingRules())
    }

    /**
     * Saves the rules and immediately reindexes, because they are only true of
     * the index once it has been rebuilt.
     *
     * Applied as a unit rather than per keystroke or per slider tick: either of
     * these costs a full pass over the vault, and a setting that quietly
     * launched one on every character would be unusable.
     *
     * A chunk-size change wipes and re-embeds everything — about 150 s for this
     * vault. Excludes are cheaper: an excluded note simply stops being found by
     * the walk, and the existing stale sweep deletes it.
     */
    fun onIndexingRulesChange(rules: IndexingRules) {
        loam.settings.indexing = rules
        // Read back rather than trusting the input: Settings clamps chunk size
        // to the range it will honour and trims the pattern text.
        _state.value = _state.value.copy(rules = loam.settings.indexing)
        reindex()
    }

    /**
     * Reacts to a completed protection change, and keeps the periodic reindex
     * consistent with the level that actually stuck.
     *
     * The change itself happens in the UI layer, because sealing under an
     * authenticated key needs a prompt and therefore an Activity. This only
     * reads back what was stored — a cancelled prompt leaves the old level in
     * place and this reports that truthfully rather than the level requested.
     *
     * [KeyProtection.EVERY_TIME] cancels the schedule outright rather than
     * letting background passes fail to decrypt. A pass that silently stops
     * working is the exact failure `ensurePeriodicIndexing` already exists to
     * prevent, and recreating it as a side effect of a security setting would
     * hide the cause even better.
     */
    fun onProtectionChanged(failure: Throwable?) {
        val stored = loam.settings.keyProtection
        _state.value = _state.value.copy(
            keyProtection = stored,
            error = failure?.let { "Protection unchanged: ${it.message}" },
        )

        if (stored.allowsBackgroundIndexing) {
            IndexWorker.schedulePeriodic(getApplication())
        } else {
            IndexWorker.cancelPeriodic(getApplication())
        }
    }

    fun onModelPicked(uri: Uri) {
        loam.modelLocation.save(uri)
        _state.value = _state.value.copy(
            modelName = loam.modelLocation.displayName,
            model = ModelState.Loading,
        )
        viewModelScope.launch {
            // Drop the previous mapping first: Loam does this internally too,
            // but doing it here means the old model is released while the
            // picker is still on screen rather than during the new open.
            loam.closeEngine()
            _state.value = _state.value.copy(
                model = runCatching { loam.llmEngine() }.fold(
                    onSuccess = { if (it == null) ModelState.None else ModelState.Ready },
                    onFailure = { ModelState.Failed(it.message ?: "Could not load model") },
                )
            )
        }
    }

    /**
     * Re-asserts the periodic reindex on every start, because scheduling it
     * only when the vault is picked leaves a way to lose it permanently.
     *
     * The vault URI lives in SharedPreferences and the schedule lives in
     * WorkManager's own database. Those are separate stores that can diverge:
     * clearing app data or restoring a partial backup can leave a perfectly
     * good vault with no schedule attached, and since [onVaultPicked] is the
     * only other caller, nothing would ever put it back. The failure is silent
     * — search keeps working against an index that quietly stops updating.
     *
     * Idempotent by way of KEEP, which is load-bearing here; see
     * [IndexWorker.schedulePeriodic].
     */
    private fun ensurePeriodicIndexing() {
        if (loam.vaultLocation.treeUri == null) return
        // A key that needs authentication per use cannot be reached by an
        // unattended worker, so there is nothing to schedule.
        if (!loam.settings.keyProtection.allowsBackgroundIndexing) return
        IndexWorker.schedulePeriodic(getApplication())
    }

    /**
     * Builds the ONNX session and loads the vector index before the user types.
     *
     * Measured at ~750 ms cold against 12 ms warm, so without this the first
     * search of every session looks broken and every later one is instant —
     * the worst possible shape for a search box, since it teaches the user to
     * distrust it exactly once per launch.
     *
     * Skipped when no vault is set: there is nothing to load, and building a
     * session would just delay the folder picker.
     */
    private fun warmUp() {
        if (loam.vaultLocation.treeUri == null) return
        viewModelScope.launch { loam.searchNotes.warmUp() }
    }

    private fun observeCounts() {
        combine(loam.indexStats.noteCount, loam.indexStats.chunkCount) { notes, chunks ->
            notes to chunks
        }
            .onEach { (notes, chunks) ->
                _state.value = _state.value.copy(noteCount = notes, chunkCount = chunks)
            }
            .launchIn(viewModelScope)
    }

    /** Holds the "did we watch this start?" question. See [IndexWorkWatcher]. */
    private val watcher = IndexWorkWatcher()

    /**
     * Both index passes, not only the one the user starts.
     *
     * Watching UNIQUE_MANUAL alone made a background pass invisible: no
     * progress while it ran, and the Reindex button stayed live throughout, so
     * the one thing the user could do about it was enqueue a run whose only
     * possible outcome was to skip.
     *
     * At most one can be doing work — [IndexVault] holds a mutex and the loser
     * returns skipped — so "whichever is RUNNING" is never ambiguous.
     */
    private fun observeIndexing() {
        val wm = WorkManager.getInstance(getApplication())
        combine(
            wm.getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE_MANUAL),
            wm.getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE_PERIODIC),
        ) { manual, periodic -> manual.firstOrNull() to periodic.firstOrNull() }
            .onEach { (manual, periodic) -> onIndexWork(manual, periodic) }
            .launchIn(viewModelScope)
    }

    private fun onIndexWork(manual: WorkInfo?, periodic: WorkInfo?) {
        val decision = watcher.onChange(manual, periodic) ?: return
        _state.value = _state.value.copy(
            indexing = decision.indexing,
            indexStatus = decision.status,
            error = if (decision.clearError) null else _state.value.error,
        )
        if (!decision.reloadIndex) return

        // Vectors changed underneath the cache. Reload eagerly rather than
        // leaving the next search to pay for it — indexing is exactly when the
        // index grew largest.
        viewModelScope.launch {
            loam.searchNotes.invalidate()
            if (_state.value.query.isNotBlank()) {
                search(_state.value.query)
            } else {
                loam.searchNotes.warmUp()
            }
        }
    }

    /**
     * The last completed pass, including ones this process never saw.
     *
     * Most periodic passes happen with the app closed, so this — not live
     * progress — is how a background reindex usually becomes visible at all.
     * An error survives here too, where the WorkInfo path lost it on restart.
     */
    private fun observeLastRun() {
        IndexRunLog.get(getApplication()).last
            .onEach { run ->
                _state.value = _state.value.copy(lastRun = run, error = run?.error)
            }
            .launchIn(viewModelScope)
    }

    fun onVaultPicked(uri: Uri) {
        loam.vaultLocation.save(uri)
        _state.value = _state.value.copy(
            hasVault = true,
            error = null,
            vaultName = uri.lastPathSegment,
        )
        reindex()
        IndexWorker.schedulePeriodic(getApplication())
    }

    fun reindex() {
        IndexWorker.runNow(getApplication())
    }

    /**
     * A query handed over whole by another app, rather than typed.
     *
     * Not debounced, unlike [onQueryChange]: there are no further keystrokes
     * coming, so waiting 250 ms would only make the share feel slow.
     */
    fun onSharedQuery(text: String) {
        _state.value = _state.value.copy(
            query = text,
            weakMatches = false,
            shareToken = _state.value.shareToken + 1,
        )
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(text) }
    }

    /**
     * Opens on Search with the field focused, which is the whole difference
     * between the widget and the launcher icon.
     */
    fun onFocusSearch() {
        _state.value = _state.value.copy(focusToken = _state.value.focusToken + 1)
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(
                results = emptyList(),
                searched = false,
                weakMatches = false,
            )
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce: each keystroke would otherwise run a full embed plus a
            // linear scan, and the embed alone is ~25 ms.
            delay(SEARCH_DEBOUNCE_MS)
            search(query)
        }
    }

    private var askJob: Job? = null

    fun onQuestionChange(question: String) {
        _state.value = _state.value.copy(ask = _state.value.ask.copy(question = question))
    }

    /**
     * Not debounced, unlike search.
     *
     * A question costs ~10 s of prompt processing and then streams; firing that
     * per keystroke would be absurd. Asking is an explicit act.
     */
    fun ask() {
        val question = _state.value.ask.question
        if (question.isBlank()) return

        askJob?.cancel()
        val previous = _state.value.ask
        // Keep the transcript, clear only the in-flight slots.
        _state.value = _state.value.copy(
            ask = AskState(question = question, asking = true, turns = previous.turns),
        )
        askJob = viewModelScope.launch {
            try {
                val history = previous.turns.map {
                    AskQuestion.Turn(question = it.question, answer = it.answer)
                }
                Log.i(TAG, "ask start history=${history.size} q=${question.take(40)}")
                loam.askQuestion.ask(question, history).collect { event ->
                    val ask = _state.value.ask
                    _state.value = _state.value.copy(
                        ask = when (event) {
                            is AskQuestion.Event.Sources ->
                                ask.copy(sources = event.sources)

                            is AskQuestion.Event.Token ->
                                ask.copy(answer = ask.answer + event.text)

                            AskQuestion.Event.NoGoodMatches ->
                                ask.copy(outcome = AskState.Outcome.NoGoodMatches)

                            AskQuestion.Event.NoModel ->
                                ask.copy(outcome = AskState.Outcome.NoModel)
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A model that fails to load surfaces here rather than as
                // NoModel — "broken" and "not chosen" need different answers.
                _state.value = _state.value.copy(
                    ask = _state.value.ask.copy(
                        outcome = AskState.Outcome.Failed(e.message ?: "Could not answer"),
                    )
                )
            } finally {
                val done = _state.value.ask
                Log.i(
                    TAG,
                    "ask done sources=${done.sources.size} answerChars=${done.answer.length} " +
                        "outcome=${done.outcome} turns=${done.turns.size}",
                )
                _state.value = _state.value.copy(
                    ask = if (done.answer.isNotBlank()) {
                        // Commit the exchange and clear the composer, so the
                        // next question starts empty rather than re-editing the
                        // last one.
                        done.copy(
                            asking = false,
                            question = "",
                            answer = "",
                            sources = emptyList(),
                            turns = done.turns + AskState.Exchange(
                                question = question,
                                answer = done.answer,
                                sources = done.sources,
                            ),
                        )
                    } else if (done.outcome == null && done.sources.isNotEmpty()) {
                        // Retrieved evidence but the model produced nothing.
                        // Without this the turn vanishes silently: sources with
                        // no answer render as an empty gap, which reads as the
                        // app having ignored the question.
                        done.copy(
                            asking = false,
                            outcome = AskState.Outcome.Failed(
                                "The model returned an empty answer."
                            ),
                        )
                    } else {
                        // Cancelled, refused, or failed: nothing worth carrying
                        // into the next question's context.
                        done.copy(asking = false)
                    }
                )
            }
        }
    }

    /** Starts a fresh conversation, dropping the transcript. */
    fun onNewConversation() {
        askJob?.cancel()
        askJob = null
        _state.value = _state.value.copy(ask = AskState())
    }

    /** Abandons generation; the Flow being cold means the model stops too. */
    fun cancelAsk() {
        askJob?.cancel()
        askJob = null
        _state.value = _state.value.copy(ask = _state.value.ask.copy(asking = false))
    }

    private suspend fun search(query: String, weak: Boolean = false) {
        _state.value = _state.value.copy(searching = true)
        try {
            val floor = loam.settings.tuning.relevanceFloor
            val results = loam.searchNotes.search(
                query,
                minScore = if (weak) floor * SearchNotes.WEAK_SCORE_RATIO else floor,
            )
            _state.value = _state.value.copy(
                results = results,
                searching = false,
                searched = true,
                weakMatches = weak,
                error = null,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                searching = false,
                error = e.message ?: "Search failed",
            )
        }
    }

    /**
     * Searches again below the floor, on request.
     *
     * The floor has to sit somewhere the two score bands overlap, so some
     * questions the vault genuinely answers fall under it — measured at 0.328
     * and 0.353 against a floor of 0.44. Discarding those silently is the
     * failure this exists to prevent; the alternative of lowering the floor to
     * catch them lets unrelated notes back in for everyone, all the time.
     */
    fun showWeakMatches() {
        val query = _state.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(query, weak = true) }
    }

    private companion object {
        const val TAG = "LoamAsk"
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
