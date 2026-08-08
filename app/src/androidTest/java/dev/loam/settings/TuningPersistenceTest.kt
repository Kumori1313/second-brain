package dev.loam.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.loam.core.domain.IndexingRules
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
    fun excludePatternsSurviveAndClearProperly() {
        settings().indexing = IndexingRules(excludePatterns = "templates/\njournal/")
        assertEquals("templates/\njournal/", settings().indexing.excludePatterns)

        settings().indexing = IndexingRules(excludePatterns = "   \n  ")

        // Whitespace is not a pattern. Storing it would leave ExcludeRules
        // parsing blank lines forever and the box looking non-empty.
        assertFalse(prefs.contains("exclude_patterns"))
        assertEquals("", settings().indexing.excludePatterns)
    }

    @Test
    fun theChunkSizeFollowsTheSameStoreOnlyDeviationsRule() {
        settings().indexing = IndexingRules(chunkTokens = 200)
        assertTrue(prefs.contains("chunk_tokens"))

        settings().indexing = IndexingRules()

        assertFalse(prefs.contains("chunk_tokens"))
    }

    @Test
    fun anOutOfRangeChunkSizeIsClamped() {
        settings().indexing = IndexingRules(chunkTokens = 4096)

        // The ceiling is the model's window. Storing more would silently
        // truncate every oversized chunk at embed time.
        assertEquals(IndexingRules.CHUNK_RANGE.last, settings().indexing.chunkTokens)
    }

    @Test
    fun theChunkingFingerprintIsRecordedSeparatelyFromTheSetting() {
        settings().indexedChunking = "v1:240"
        settings().indexing = IndexingRules(chunkTokens = 200)

        // Changing the setting must not look like the index was rebuilt — the
        // gap between the two is the signal IndexVault acts on.
        assertEquals("v1:240", settings().indexedChunking)
        assertEquals("v1:200", settings().indexing.chunkingFingerprint())
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
