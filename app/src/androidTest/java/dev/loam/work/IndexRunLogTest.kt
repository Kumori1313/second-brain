package dev.loam.work

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record a periodic pass leaves behind, since WorkManager keeps none.
 *
 * Runs on device rather than as a JVM test because the whole thing is
 * SharedPreferences, and a fake one would be testing the fake.
 */
class IndexRunLogTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log() = IndexRunLog.get(context)

    private fun run(
        periodic: Boolean = true,
        notes: Int = 3,
        error: String? = null,
    ) = IndexRunLog.Run(
        finishedAt = System.currentTimeMillis(),
        periodic = periodic,
        notesIndexed = notes,
        chunksEmbedded = notes * 14,
        millis = 4200,
        error = error,
    )

    @Test
    fun aRecordedRunIsReadableBack() {
        val written = run(periodic = true, notes = 3)
        log().record(written)

        assertEquals(written, log().last.value)
    }

    @Test
    fun theFlowUpdatesRatherThanOnlyTheStoredValue() {
        val log = log()
        log.record(run(notes = 1))
        val before = log.last.value

        log.record(run(notes = 7))

        // The UI observes this flow. A write that persists but does not emit
        // would read correctly on the next launch and show nothing until then,
        // which is the failure mode that looks like the feature working.
        assertEquals(7, log.last.value?.notesIndexed)
        assertTrue(before != log.last.value)
    }

    @Test
    fun aFailedRunKeepsItsMessage() {
        log().record(run(error = "Vault access was revoked"))

        assertEquals("Vault access was revoked", log().last.value?.error)
    }

    @Test
    fun aSucceedingRunClearsAnEarlierFailure() {
        log().record(run(error = "Vault access was revoked"))
        log().record(run(error = null))

        // Otherwise the error banner outlives the problem, and the only way to
        // clear it is to reinstall.
        assertNull(log().last.value?.error)
    }

    @Test
    fun oneInstancePerProcess() {
        // The worker writes and the ViewModel observes. Two instances would each
        // hold their own flow, both would read the same prefs, and the live
        // update would silently never arrive.
        assertSame(IndexRunLog.get(context), IndexRunLog.get(context))
    }

    @Test
    fun aBackgroundPassIsDistinguishableFromAManualOne() {
        log().record(run(periodic = true))
        assertEquals(true, log().last.value?.periodic)

        log().record(run(periodic = false))
        assertEquals(false, log().last.value?.periodic)
    }
}
