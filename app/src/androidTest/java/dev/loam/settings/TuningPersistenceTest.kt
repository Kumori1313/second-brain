package dev.loam.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.loam.core.domain.Settings
import dev.loam.core.domain.Tuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * That a measured default can still be re-measured after the app has shipped.
 *
 * Writes to the app's real settings, so it snapshots and restores them — this
 * runs on a device someone actually uses Loam on.
 */
class TuningPersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("loam_settings", Context.MODE_PRIVATE)
    private lateinit var saved: Map<String, Any?>

    @Before
    fun capture() {
        saved = prefs.all.toMap()
    }

    @After
    fun restore() {
        prefs.edit().clear().apply {
            for ((k, v) in saved) when (v) {
                is Int -> putInt(k, v)
                is Float -> putFloat(k, v)
                is Long -> putLong(k, v)
                is Boolean -> putBoolean(k, v)
                is String -> putString(k, v)
            }
        }.commit()
    }

    private fun settings() = Settings(context)

    @Test
    fun aDeviationIsStoredAndReadBack() {
        settings().tuning = Tuning(relevanceFloor = 0.22f)

        assertEquals(0.22f, settings().tuning.relevanceFloor, 0.0001f)
        assertTrue(prefs.contains("relevance_floor"))
    }

    @Test
    fun aValueEqualToTheDefaultIsStoredAsAbsent() {
        settings().tuning = Tuning(relevanceFloor = 0.22f)
        settings().tuning = Tuning(relevanceFloor = Tuning.DEFAULT_RELEVANCE_FLOOR)

        // The point of the whole rule: this is what lets a recalibrated
        // constant reach someone who has opened Settings but never formed an
        // opinion about the floor.
        assertFalse(prefs.contains("relevance_floor"))
        assertEquals(
            Tuning.DEFAULT_RELEVANCE_FLOOR,
            settings().tuning.relevanceFloor,
            0.0001f,
        )
    }

    @Test
    fun theRuleAppliesToEveryTunableNotJustTheFloor() {
        settings().tuning = Tuning(chunksPerAnswer = 9, contextTokens = 8192)
        assertTrue(prefs.contains("chunks_per_answer"))
        assertTrue(prefs.contains("context_tokens"))

        settings().tuning = Tuning()

        assertFalse(prefs.contains("chunks_per_answer"))
        assertFalse(prefs.contains("context_tokens"))
    }

    @Test
    fun oneDeviationDoesNotDragTheOthersIntoStorage() {
        settings().tuning = Tuning(chunksPerAnswer = 9)

        // The setter writes all three at once. Storing the two untouched
        // defaults alongside the one real choice would freeze them just as
        // effectively.
        assertTrue(prefs.contains("chunks_per_answer"))
        assertFalse(prefs.contains("relevance_floor"))
        assertFalse(prefs.contains("context_tokens"))
    }

    @Test
    fun anOutOfRangeFloorIsClampedBeforeTheDefaultCheck() {
        settings().tuning = Tuning(relevanceFloor = 9f)

        assertEquals(
            Tuning.FLOOR_RANGE.endInclusive,
            settings().tuning.relevanceFloor,
            0.0001f,
        )
    }
}
