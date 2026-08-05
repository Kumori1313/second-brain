package dev.loam.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.loam.core.Loam
import dev.loam.core.domain.IndexVault
import java.util.concurrent.TimeUnit

/**
 * Runs indexing off the UI, and keeps running if the user leaves the app.
 *
 * SAF has no filesystem watch, so there is nothing to subscribe to — periodic
 * plus manual reindex is the intended design, not a limitation to work around.
 */
class IndexWorker(
    context: Context,
    params: androidx.work.WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val loam = Loam.get(applicationContext)
        val treeUri = loam.vaultLocation.treeUri ?: return Result.success()
        if (!loam.vaultLocation.isGrantValid()) {
            // The user revoked folder access in system settings. Failing loudly
            // beats retrying forever against a URI we can no longer read.
            return Result.failure(workDataOf(KEY_ERROR to "Vault access was revoked"))
        }

        return try {
            val done = loam.indexVault.run(treeUri) { progress ->
                setProgressAsync(progress.toData())
            } ?: return Result.success(workDataOf(KEY_SKIPPED to true))
            loam.searchNotes.invalidate()
            Result.success(
                workDataOf(
                    KEY_NOTES_INDEXED to done.notesIndexed,
                    KEY_CHUNKS to done.chunksEmbedded,
                    KEY_MILLIS to done.millis,
                )
            )
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e::class.simpleName)))
        }
    }

    companion object {
        const val UNIQUE_MANUAL = "loam-index-once"
        const val UNIQUE_PERIODIC = "loam-index-periodic"

        const val KEY_STAGE = "stage"
        const val KEY_FILES_FOUND = "filesFound"
        const val KEY_NOTES_DONE = "notesDone"
        const val KEY_NOTES_TOTAL = "notesTotal"
        const val KEY_CHUNKS = "chunks"
        const val KEY_NOTES_INDEXED = "notesIndexed"
        const val KEY_MILLIS = "millis"
        const val KEY_ERROR = "error"

        /**
         * Set when this run did nothing because another was already in flight.
         * Distinguishing it from a real run matters: both index zero notes, but
         * only one of them means the index is actually up to date.
         */
        const val KEY_SKIPPED = "skipped"

        const val STAGE_WALKING = "walking"
        const val STAGE_EMBEDDING = "embedding"

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MANUAL,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().build(),
            )
        }

        /**
         * Battery-friendly catch-up pass. Deliberately unconstrained by network
         * — there is no network involved, and adding a constraint the app can
         * never satisfy would stall it forever.
         *
         * Safe to call on every app start, and [SearchViewModel] does. KEEP is
         * what makes that safe and is not interchangeable with UPDATE here:
         * UPDATE restarts the period on each call, so a user who opens Loam
         * more often than the interval would reset the clock every time and the
         * pass would never come due. KEEP leaves an existing schedule running
         * and only fills in a missing one.
         */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IndexWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build(),
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UNIQUE_MANUAL)
                cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }

        private fun IndexVault.Progress.toData(): Data = when (this) {
            is IndexVault.Progress.Walking ->
                workDataOf(KEY_STAGE to STAGE_WALKING, KEY_FILES_FOUND to filesFound)

            is IndexVault.Progress.Embedding -> workDataOf(
                KEY_STAGE to STAGE_EMBEDDING,
                KEY_NOTES_DONE to notesDone,
                KEY_NOTES_TOTAL to notesTotal,
                KEY_CHUNKS to chunksEmbedded,
            )

            is IndexVault.Progress.Done -> workDataOf(
                KEY_NOTES_INDEXED to notesIndexed,
                KEY_CHUNKS to chunksEmbedded,
                KEY_MILLIS to millis,
            )
        }
    }
}

/** Convenience for the UI: has the user picked a vault yet? */
fun Context.hasVault(): Boolean = Loam.get(this).vaultLocation.treeUri != null

fun Uri.asVault(context: Context) {
    Loam.get(context).vaultLocation.save(this)
}
