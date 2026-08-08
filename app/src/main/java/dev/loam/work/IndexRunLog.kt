package dev.loam.work

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the last index pass did, recorded by the worker itself.
 *
 * WorkManager cannot report this for a periodic run. A `PeriodicWorkRequest`
 * never reaches a terminal state — it goes back to ENQUEUED after each pass —
 * so `WorkInfo.outputData` stays empty for it and the whole result-reporting
 * path the manual run uses is simply unavailable. Writing the outcome down is
 * the only way a background pass can say anything at all.
 *
 * Persisted rather than held in memory because that is where the value is:
 * most periodic passes happen while the app is closed, so the run worth
 * reporting is almost always one this process never saw.
 */
class IndexRunLog private constructor(context: Context) {

    /**
     * @param periodic distinguishes a background catch-up from a pass the user
     *   asked for and watched. Both do identical work; only one of them is news.
     */
    data class Run(
        val finishedAt: Long,
        val periodic: Boolean,
        val notesIndexed: Int,
        val chunksEmbedded: Int,
        val millis: Long,
        val error: String? = null,
    )

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())

    /** The last completed pass, surviving process death. Null until one runs. */
    val last: StateFlow<Run?> = _last.asStateFlow()

    fun record(run: Run) {
        prefs.edit()
            .putLong(KEY_AT, run.finishedAt)
            .putBoolean(KEY_PERIODIC, run.periodic)
            .putInt(KEY_NOTES, run.notesIndexed)
            .putInt(KEY_CHUNKS, run.chunksEmbedded)
            .putLong(KEY_MILLIS, run.millis)
            .putString(KEY_ERROR, run.error)
            .apply()
        _last.value = run
    }

    private fun read(): Run? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        return Run(
            finishedAt = at,
            periodic = prefs.getBoolean(KEY_PERIODIC, false),
            notesIndexed = prefs.getInt(KEY_NOTES, 0),
            chunksEmbedded = prefs.getInt(KEY_CHUNKS, 0),
            millis = prefs.getLong(KEY_MILLIS, 0L),
            error = prefs.getString(KEY_ERROR, null),
        )
    }

    companion object {
        private const val PREFS = "loam_index_runs"
        private const val KEY_AT = "finished_at"
        private const val KEY_PERIODIC = "periodic"
        private const val KEY_NOTES = "notes"
        private const val KEY_CHUNKS = "chunks"
        private const val KEY_MILLIS = "millis"
        private const val KEY_ERROR = "error"

        @Volatile
        private var instance: IndexRunLog? = null

        /**
         * One per process, because the worker writes and the UI observes. Two
         * instances would each hold their own flow and the UI's would never see
         * the worker's write — the reads would agree and the live update would
         * silently not happen.
         */
        fun get(context: Context): IndexRunLog =
            instance ?: synchronized(this) {
                instance ?: IndexRunLog(context).also { instance = it }
            }
    }
}
