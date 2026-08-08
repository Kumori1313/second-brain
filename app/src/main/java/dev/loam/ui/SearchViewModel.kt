package dev.loam.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import dev.loam.core.Loam
import dev.loam.core.domain.AskQuestion
import dev.loam.core.domain.SearchNotes
import dev.loam.core.domain.Tuning
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
        /** Last path segment of the vault URI — enough to recognise it. */
        val vaultName: String? = null,
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
    ) {
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
            vaultName = loam.vaultLocation.treeUri?.lastPathSegment,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeIndexing()
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

    fun onResetTuning() = onTuningChange(Tuning())

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

    /**
     * The work we have actually watched run, as opposed to a terminal state
     * WorkManager replays to every new subscriber.
     *
     * Without this, each app launch sees the *previous* run's SUCCEEDED and
     * treats it as fresh: it invalidates the index warm-up just finished
     * loading, reloads it, and reports a duration from a run that happened days
     * ago as though it had just completed.
     */
    private var runningWorkId: UUID? = null

    private fun observeIndexing() {
        val wm = WorkManager.getInstance(getApplication())
        wm.getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE_MANUAL)
            .onEach { infos ->
                val info = infos.firstOrNull() ?: return@onEach

                // Terminal states are replayed to every new subscriber, so a
                // completion we never saw running belongs to a previous launch.
                // Acting on it would invalidate the index warm-up just loaded
                // and report a days-old duration as if it had just happened.
                // The live note and chunk counts already say what is indexed.
                val isReplay = info.state.isFinished && info.id != runningWorkId
                if (isReplay) return@onEach

                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        runningWorkId = info.id
                        _state.value = _state.value.copy(
                            indexing = true,
                            error = null,
                            indexStatus = describe(info),
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        runningWorkId = null
                        if (info.outputData.getBoolean(IndexWorker.KEY_SKIPPED, false)) {
                            // Nothing ran, so nothing changed — reporting "up to
                            // date" here would claim a guarantee this run never
                            // checked, and reloading the index would throw away
                            // a warm cache to no purpose.
                            _state.value = _state.value.copy(
                                indexing = false,
                                indexStatus = "Already indexing",
                            )
                            return@onEach
                        }
                        val notes = info.outputData.getInt(IndexWorker.KEY_NOTES_INDEXED, 0)
                        val chunks = info.outputData.getInt(IndexWorker.KEY_CHUNKS, 0)
                        val secs = info.outputData.getLong(IndexWorker.KEY_MILLIS, 0) / 1000.0
                        _state.value = _state.value.copy(
                            indexing = false,
                            indexStatus = if (notes == 0) {
                                "Index up to date"
                            } else {
                                "Indexed %d notes, %d chunks in %.1fs".format(notes, chunks, secs)
                            },
                        )
                        // Vectors changed underneath the cache. Reload eagerly
                        // rather than leaving the next search to pay for it —
                        // indexing is exactly when the index grew largest.
                        viewModelScope.launch {
                            loam.searchNotes.invalidate()
                            if (_state.value.query.isNotBlank()) {
                                search(_state.value.query)
                            } else {
                                loam.searchNotes.warmUp()
                            }
                        }
                    }

                    WorkInfo.State.FAILED -> {
                        runningWorkId = null
                        _state.value = _state.value.copy(
                            indexing = false,
                            indexStatus = null,
                            error = info.outputData.getString(IndexWorker.KEY_ERROR)
                                ?: "Indexing failed",
                        )
                    }

                    else -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    private fun describe(info: WorkInfo): String =
        when (info.progress.getString(IndexWorker.KEY_STAGE)) {
            IndexWorker.STAGE_WALKING ->
                "Scanning vault… ${info.progress.getInt(IndexWorker.KEY_FILES_FOUND, 0)} notes found"

            IndexWorker.STAGE_EMBEDDING -> {
                val done = info.progress.getInt(IndexWorker.KEY_NOTES_DONE, 0)
                val total = info.progress.getInt(IndexWorker.KEY_NOTES_TOTAL, 0)
                val chunks = info.progress.getInt(IndexWorker.KEY_CHUNKS, 0)
                "Embedding $done/$total notes ($chunks chunks)"
            }

            else -> "Starting…"
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

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), searched = false)
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
        _state.value = _state.value.copy(
            ask = AskState(question = question, asking = true),
        )
        askJob = viewModelScope.launch {
            try {
                loam.askQuestion.ask(question).collect { event ->
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
                _state.value = _state.value.copy(ask = _state.value.ask.copy(asking = false))
            }
        }
    }

    /** Abandons generation; the Flow being cold means the model stops too. */
    fun cancelAsk() {
        askJob?.cancel()
        askJob = null
        _state.value = _state.value.copy(ask = _state.value.ask.copy(asking = false))
    }

    private suspend fun search(query: String) {
        _state.value = _state.value.copy(searching = true)
        try {
            val results = loam.searchNotes.search(query)
            _state.value = _state.value.copy(
                results = results,
                searching = false,
                searched = true,
                error = null,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                searching = false,
                error = e.message ?: "Search failed",
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
