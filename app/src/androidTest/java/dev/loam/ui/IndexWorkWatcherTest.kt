package dev.loam.ui

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.loam.work.IndexWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The state machine between the run log and the pane.
 *
 * Both ends had tests and this did not, which is backwards — this is the only
 * part with real branching, and the periodic work it has to reason about is
 * exactly the part that cannot be driven on demand (`cmd jobscheduler run -f`
 * is refused by WorkManager as "executed before schedule").
 *
 * Built from real [WorkInfo] values rather than an interface of our own, so
 * the states under test are ones WorkManager can actually produce — including
 * the one that matters most, a periodic run going back to ENQUEUED instead of
 * reaching a terminal state.
 */
class IndexWorkWatcherTest {

    private val manualId = UUID.randomUUID()
    private val periodicId = UUID.randomUUID()

    private fun info(
        id: UUID,
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ) = WorkInfo(
        id = id,
        state = state,
        tags = setOf(IndexWorker::class.java.name),
        outputData = output,
        progress = progress,
        runAttemptCount = 0,
        generation = 0,
    )

    private fun embedding(done: Int, total: Int, chunks: Int) = workDataOf(
        IndexWorker.KEY_STAGE to IndexWorker.STAGE_EMBEDDING,
        IndexWorker.KEY_NOTES_DONE to done,
        IndexWorker.KEY_NOTES_TOTAL to total,
        IndexWorker.KEY_CHUNKS to chunks,
    )

    @Test
    fun aManualRunReportsProgressWithoutTheBackgroundPrefix() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(
            manual = info(manualId, WorkInfo.State.RUNNING, progress = embedding(25, 392, 403)),
            periodic = info(periodicId, WorkInfo.State.ENQUEUED),
        )

        assertEquals("Embedding 25/392 notes (403 chunks)", d?.status)
        assertEquals(true, d?.indexing)
    }

    @Test
    fun aPeriodicRunIsLabelledAsBackground() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(
            manual = info(manualId, WorkInfo.State.SUCCEEDED),
            periodic = info(periodicId, WorkInfo.State.RUNNING, progress = embedding(25, 392, 403)),
        )

        // The whole point of watching the second work name. Without the prefix
        // a pass the user did not start is indistinguishable from one they did.
        assertEquals("Background reindex · Embedding 25/392 notes (403 chunks)", d?.status)
        assertEquals(true, d?.indexing)
    }

    @Test
    fun theWalkStageIsReportedSeparatelyFromEmbedding() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(
            manual = info(
                manualId,
                WorkInfo.State.RUNNING,
                progress = workDataOf(
                    IndexWorker.KEY_STAGE to IndexWorker.STAGE_WALKING,
                    IndexWorker.KEY_FILES_FOUND to 392,
                ),
            ),
            periodic = null,
        )

        // Enumeration was 13.5 s before the DocumentsContract rewrite and is
        // still its own stage, so it needs its own line rather than looking
        // like embedding that has not started.
        assertEquals("Scanning vault… 392 notes found", d?.status)
    }

    @Test
    fun aRunningWorkWithNoProgressYetStillSaysSomething() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)

        assertEquals("Starting…", d?.status)
    }

    @Test
    fun aReplayedTerminalStateIsIgnored() {
        val watcher = IndexWorkWatcher()

        // What every launch sees first: the previous run's SUCCEEDED, replayed
        // to a brand new subscriber. Acting on it reloads the index over the
        // warm-up that just finished.
        val d = watcher.onChange(
            manual = info(manualId, WorkInfo.State.SUCCEEDED),
            periodic = info(periodicId, WorkInfo.State.ENQUEUED),
        )

        assertNull(d)
    }

    @Test
    fun aFinishWeWatchedStartReloadsTheIndex() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)

        val d = watcher.onChange(info(manualId, WorkInfo.State.SUCCEEDED), null)

        assertNotNull(d)
        assertEquals(false, d?.indexing)
        assertTrue(d!!.reloadIndex)
        // The outcome text comes from the run log, not from here.
        assertNull(d.status)
    }

    @Test
    fun aPeriodicRunFinishesByReturningToEnqueued() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(null, info(periodicId, WorkInfo.State.RUNNING))

        val d = watcher.onChange(null, info(periodicId, WorkInfo.State.ENQUEUED))

        // A PeriodicWorkRequest never reaches a terminal state, so "finished"
        // here is ENQUEUED and not SUCCEEDED. Waiting for a terminal state
        // would leave the UI showing a background pass that ended hours ago.
        assertNotNull(d)
        assertEquals(false, d?.indexing)
        assertTrue(d!!.reloadIndex)
    }

    @Test
    fun aSkippedRunDoesNotReloadTheIndex() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)

        val d = watcher.onChange(
            info(
                manualId,
                WorkInfo.State.SUCCEEDED,
                output = workDataOf(IndexWorker.KEY_SKIPPED to true),
            ),
            null,
        )

        // It lost the mutex and looked at nothing. Reloading would throw away a
        // warm cache to no purpose, and claiming "up to date" would assert a
        // guarantee this run never checked.
        assertEquals("Already indexing", d?.status)
        assertFalse(d!!.reloadIndex)
    }

    @Test
    fun aFailedRunStillClearsTheIndexingFlag() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)

        val d = watcher.onChange(info(manualId, WorkInfo.State.FAILED), null)

        // Otherwise the progress bar spins forever and Reindex never comes
        // back, which is the one state with no way out.
        assertEquals(false, d?.indexing)
    }

    @Test
    fun aStartedRunClearsTheErrorFromTheLastOne() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)

        assertTrue(d!!.clearError)
    }

    @Test
    fun oneFinishIsReportedOnce() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)
        watcher.onChange(info(manualId, WorkInfo.State.SUCCEEDED), null)

        // Both flows re-emit on any transition either work makes, so the same
        // terminal state arrives repeatedly. A second reload would be pure cost.
        val again = watcher.onChange(info(manualId, WorkInfo.State.SUCCEEDED), null)

        assertNull(again)
    }

    @Test
    fun aManualRunTakesTheLineWhileABackgroundOneIsAlsoLive() {
        val watcher = IndexWorkWatcher()

        val d = watcher.onChange(
            manual = info(manualId, WorkInfo.State.RUNNING, progress = embedding(1, 392, 3)),
            periodic = info(periodicId, WorkInfo.State.RUNNING, progress = embedding(25, 392, 403)),
        )

        // Both can be RUNNING briefly before the loser hits the mutex. The one
        // the user started is the one they are waiting on.
        assertEquals("Embedding 1/392 notes (3 chunks)", d?.status)
    }

    @Test
    fun theBackgroundPassIsStillTrackedAfterAManualOneFinishes() {
        val watcher = IndexWorkWatcher()
        watcher.onChange(info(manualId, WorkInfo.State.RUNNING), null)
        watcher.onChange(info(manualId, WorkInfo.State.SUCCEEDED), null)

        val started = watcher.onChange(null, info(periodicId, WorkInfo.State.RUNNING))
        val finished = watcher.onChange(null, info(periodicId, WorkInfo.State.ENQUEUED))

        // The watched id is a single slot. Leaving a stale manual id in it
        // would make every later background pass look like a replay.
        assertEquals(true, started?.indexing)
        assertTrue(finished!!.reloadIndex)
    }
}
