package dev.loam.vault

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.loam.core.Loam
import dev.loam.core.vault.Chunker
import dev.loam.core.vault.ExcludeRules
import dev.loam.core.vault.TokenCounter
import dev.loam.core.vault.VaultReader
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where the chunking stage actually spends its time.
 *
 * **Every absolute timing this prints is a debug-build timing and is wrong by
 * roughly 25x.** Instrumented tests only run against the debug variant, and
 * `debuggable=true` costs the tokenizer about that much: measured at 5.4–7.8 ms
 * per chunk here against 0.2–0.3 ms in a release build doing identical work.
 * An apparent 100 s of chunking is about 1 s in the app anyone actually runs.
 *
 * What survives the build difference are the *ratios* — how many times the
 * corpus gets tokenized, how many calls per note, how much of chunking is the
 * counter rather than the chunker. Those are what this is for. Read the
 * nanoseconds as a way of comparing one change to another within a debug
 * build, never as a cost.
 *
 * Measured against the real vault rather than a fixture, because chunking cost
 * is a function of what the text looks like: fenced code, tables and long
 * unbroken runs each take a different path through [Chunker], and a synthetic
 * corpus would exercise whichever one the fixture happened to resemble.
 *
 * Reports aggregates only — counts, characters and milliseconds. No note text
 * and no note names.
 *
 * ```
 * adb shell am instrument -w -e class dev.loam.vault.ChunkerProfileTest \
 *   dev.loam.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s LoamChunkProf
 * ```
 *
 * Needs a real indexed vault, and fails rather than skips without one.
 */
class ChunkerProfileTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loam = Loam.get(context)

    @Test
    fun whereDoesChunkingGo() {
        val treeUri = loam.vaultLocation.treeUri
        if (treeUri == null) {
            fail("No vault. Point the app at one first — this measures a real corpus.")
            return
        }

        val reader = VaultReader(context)
        val rules = loam.settings.indexing
        val notes = reader.walk(treeUri, ExcludeRules.parse(rules.excludePatterns))

        var calls = 0L
        var charsTokenized = 0L
        var counterNanos = 0L
        val instrumented = TokenCounter { text ->
            calls++
            charsTokenized += text.length
            val t0 = System.nanoTime()
            val n = loam.tokenCounter.count(text)
            counterNanos += System.nanoTime() - t0
            n
        }

        var sourceChars = 0L
        var chunks = 0
        var readNanos = 0L
        var chunkNanos = 0L

        for (note in notes) {
            val r0 = System.nanoTime()
            val text = reader.readText(note.uri)
            readNanos += System.nanoTime() - r0
            sourceChars += text.length

            val c0 = System.nanoTime()
            chunks += Chunker.chunk(
                text,
                instrumented,
                targetTokens = rules.chunkTokens,
            ).size
            chunkNanos += System.nanoTime() - c0
        }

        val chunkMs = chunkNanos / 1_000_000.0
        val counterMs = counterNanos / 1_000_000.0
        Log.i(TAG, "notes=${notes.size} chunks=$chunks sourceChars=$sourceChars")
        Log.i(TAG, "read=%.0fms chunk=%.0fms".format(readNanos / 1_000_000.0, chunkMs))
        Log.i(
            TAG,
            "counter: calls=%d chars=%d time=%.0fms (%.1f%% of chunking)"
                .format(calls, charsTokenized, counterMs, 100 * counterMs / chunkMs),
        )
        // The number that says whether the cost is the tokenizer being slow or
        // the chunker asking it the same question repeatedly.
        Log.i(
            TAG,
            "amplification: %.1fx the vault tokenized, %.1f calls per note, %.0f ns/char"
                .format(
                    charsTokenized.toDouble() / sourceChars,
                    calls.toDouble() / notes.size,
                    counterNanos.toDouble() / charsTokenized,
                ),
        )
    }

    private companion object {
        const val TAG = "LoamChunkProf"
    }
}
