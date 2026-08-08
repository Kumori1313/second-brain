package dev.loam.ui

import androidx.work.WorkInfo
import dev.loam.work.IndexWorker
import java.util.UUID

/**
 * Decides what a change in index work means, without doing anything about it.
 *
 * Pulled out of [SearchViewModel] for the same reason [SearchPane] was pulled
 * out of [LoamScreen]: this is where the branching lives — which of the two
 * unique works is running, which finish to react to, which to ignore as a
 * replay — and inside the ViewModel none of it could be reached without a
 * WorkManager, a database and an embedder.
 *
 * Stateful by necessity. Whether a finish matters depends on whether we saw
 * the same work start, which no single [WorkInfo] can say.
 */
class IndexWorkWatcher {

    /**
     * @param reloadIndex the pass changed the stored vectors, so the cached
     *   index is stale. Separate from the display fields because it is the only
     *   part with a cost — reloading for a run that did nothing throws away a
     *   warm cache, and reloading for a replayed run throws away the warm-up
     *   that just finished.
     */
    data class Decision(
        val indexing: Boolean,
        val status: String?,
        val reloadIndex: Boolean = false,
        /** A pass that has started supersedes whatever the last one reported. */
        val clearError: Boolean = false,
    )

    private var watched: UUID? = null

    /**
     * @return null when the change means nothing and the UI should be left
     *   alone — which is most of them, since both flows re-emit on every state
     *   transition either work makes.
     */
    fun onChange(manual: WorkInfo?, periodic: WorkInfo?): Decision? {
        fun WorkInfo?.running() = this?.takeIf { it.state == WorkInfo.State.RUNNING }

        val background = periodic.running()
        val running = manual.running() ?: background
        if (running != null) {
            watched = running.id
            return Decision(
                indexing = true,
                status = describe(running, background = running === background),
                clearError = true,
            )
        }

        // Nothing is running now. Only react to a stop we watched start —
        // terminal states are replayed to every new subscriber, so the manual
        // run that finished days ago arrives on launch looking exactly like one
        // that just did, and reloading the index for it would throw away the
        // warm-up that finished moments earlier.
        val watched = watched ?: return null
        this.watched = null

        val finished = listOfNotNull(manual, periodic).firstOrNull { it.id == watched }
        if (finished?.outputData?.getBoolean(IndexWorker.KEY_SKIPPED, false) == true) {
            // Nothing ran, so nothing changed — reporting "up to date" here
            // would claim a guarantee this run never checked, and reloading the
            // index would throw away a warm cache to no purpose.
            return Decision(indexing = false, status = "Already indexing")
        }

        // What the pass actually did is reported from the run log instead. That
        // is not indirection for its own sake: a periodic run has no output
        // data to read, so the outcome has to come from somewhere both kinds of
        // run can reach.
        return Decision(indexing = false, status = null, reloadIndex = true)
    }

    /**
     * @param background prefixes the line, because the two runs are otherwise
     *   indistinguishable on screen and the difference matters: one appeared on
     *   its own and will finish on its own, the other is something the user is
     *   waiting on.
     */
    private fun describe(info: WorkInfo, background: Boolean): String {
        val stage = when (info.progress.getString(IndexWorker.KEY_STAGE)) {
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
        return if (background) "Background reindex · $stage" else stage
    }
}
