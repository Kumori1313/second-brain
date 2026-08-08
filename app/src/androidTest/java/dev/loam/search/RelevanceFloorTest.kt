package dev.loam.search

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.loam.core.Loam
import dev.loam.core.domain.SearchNotes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

/**
 * Calibrates the relevance floor against the index that is actually on the
 * device, and keeps it honest afterwards.
 *
 * Runs inside the app's own uid, so it opens the real encrypted store rather
 * than a fixture. That is the whole point: the floor separates "this vault can
 * answer you" from "it cannot", and a synthetic corpus cannot tell you where
 * that line falls for a real one. It prints only note titles, headings and
 * scores — never chunk text.
 *
 * ```
 * adb shell am instrument -w -e class dev.loam.search.RelevanceFloorTest \
 *   dev.loam.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s LoamFloor
 * ```
 *
 * An unindexed vault fails rather than skips, following the `:llama` suite's
 * precedent: a green run that measured nothing is worse than a red one. It is
 * therefore the one test here that needs a real device with a real vault —
 * exclude it with `-e notClass dev.loam.search.RelevanceFloorTest` elsewhere.
 */
class RelevanceFloorTest {

    private val loam = Loam.get(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * Questions this vault demonstrably can answer. Every one of these is
     * confirmed by inspection of what comes back, not by assumption — a query
     * whose top hits are unrelated is a bad probe, not a failing floor.
     */
    private val answerable = listOf(
        "how do I set up a virtual machine",
        "encrypting a disk with LUKS",
        "what cipher and key derivation does my LUKS setup use",
        "installing packages with pacman",
        "how do I configure the bootloader",
        "creating a btrfs subvolume",
        "how to enable a systemd service at boot",
        "setting up a firewall",
        "how do I create a swap file",
        "generating an SSH key",
    )

    /**
     * Questions no Linux-documentation vault can answer. Deliberately ordinary
     * rather than gibberish: random characters embed to nothing in particular
     * and score low for free, which would flatter any floor. These are
     * well-formed questions about other domains, which is the case that
     * actually goes wrong.
     */
    private val oblique = listOf(
        "my computer will not boot after an update",
        "I want to run Windows without rebooting",
        "how do I make more room on my main drive",
        "my screen tears when I move windows",
        "the laptop battery drains too fast",
        "I locked myself out and need to get back in",
    )

    private val nearDomain = listOf(
        "kubernetes cluster autoscaling",
        "configuring an Apache virtual host",
        "how do I write a Dockerfile",
        "setting up a PostgreSQL read replica",
        "compiling a Rust project with cargo",
        "how do I flash an Android phone with fastboot",
    )

    private val unanswerable = listOf(
        "banana bread recipe with walnuts",
        "who won the world cup in 1998",
        "best hiking trails in Patagonia",
        "how to change a car tyre",
        "symptoms of vitamin D deficiency",
        "wedding venue booking checklist",
        "stock market outlook for next quarter",
        "how do I stop my puppy barking at night",
    )

    private fun search(query: String): List<SearchNotes.Result> = runBlocking {
        // Floor of zero: the question is what the scores are, and a search that
        // has already applied the floor cannot answer it.
        loam.searchNotes.search(query, limit = 8, minScore = 0f)
    }

    private fun requireIndex() {
        val chunks = search("linux")
        if (chunks.isEmpty()) {
            fail(
                "No index on this device. Point the app at a vault and let it " +
                    "index before running this. Failing rather than skipping: " +
                    "this is the one test that measures the real corpus, and a " +
                    "silent skip reads as a pass."
            )
        }
    }

    @Test
    fun printTheScoreDistribution() {
        requireIndex()
        for (query in answerable + oblique + nearDomain + unanswerable) {
            Log.i(TAG, "── $query")
            search(query).forEachIndexed { i, r ->
                Log.i(TAG, "   %d %.3f  %s › %s".format(i, r.score, r.displayName, r.headingPath))
            }
        }
    }

    /**
     * Which chunks turn up for questions the vault cannot answer.
     *
     * A floor assumes a high score means relevance. If the same handful of
     * chunks score well against every unrelated query, the score is measuring
     * something else about them and no threshold can fix that.
     */
    @Test
    fun findTheChunksThatMatchEverything() {
        requireIndex()
        val seen = mutableMapOf<Long, MutableList<Float>>()
        val label = mutableMapOf<Long, String>()
        for (query in unanswerable + nearDomain) {
            for (r in search(query)) {
                seen.getOrPut(r.chunkId) { mutableListOf() }.add(r.score)
                label[r.chunkId] = "%s › %s (%d chars)"
                    .format(r.displayName, r.headingPath, r.text.length)
            }
        }
        Log.i(TAG, "══ chunks in the top 8 of ${unanswerable.size + nearDomain.size} unanswerable queries")
        seen.entries
            .sortedByDescending { it.value.size }
            .take(12)
            .forEach { (id, scores) ->
                Log.i(
                    TAG,
                    "   x%-2d best %.3f  %s".format(scores.size, scores.max(), label[id]),
                )
            }

        // For contrast: how long is a chunk that only ever answers its own
        // subject? Sampled from the queries the vault genuinely answers.
        Log.i(TAG, "══ top hit length for answerable queries")
        for (query in answerable) {
            val top = search(query).firstOrNull() ?: continue
            Log.i(TAG, "   %.3f %5d chars  %s".format(top.score, top.text.length, top.displayName))
        }
    }

    companion object {
        private const val TAG = "LoamFloor"
    }
}
