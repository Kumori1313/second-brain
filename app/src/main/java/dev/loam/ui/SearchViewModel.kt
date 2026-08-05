package dev.loam.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import dev.loam.core.Loam
import dev.loam.core.domain.SearchNotes
import dev.loam.work.IndexWorker
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
    )

    private val _state = MutableStateFlow(UiState(hasVault = loam.vaultLocation.treeUri != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeIndexing()
        observeCounts()
        warmUp()
        ensurePeriodicIndexing()
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
        _state.value = _state.value.copy(hasVault = true, error = null)
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
