package dev.loam.llama

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.loam.core.llm.GenerationParams
import dev.loam.core.llm.Message
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Exercises the native engine on a real device, because nothing here can fail
 * on the JVM: a missing CPU backend, `mmap` refusing an fd path, and a wrong
 * chat template all produce a working build that misbehaves only on hardware.
 *
 * Requires a GGUF on the device. `connectedAndroidTest` will not work: it
 * reinstalls the app first, and that wipes the app's external files directory
 * along with the model. Install, then push, then instrument:
 *
 * ```
 * ./gradlew :llama:assembleDebugAndroidTest
 * adb install -r llama/build/outputs/apk/androidTest/debug/llama-debug-androidTest.apk
 * adb push model.gguf /sdcard/Android/data/dev.loam.llama.test/files/model.gguf
 * adb shell am instrument -w dev.loam.llama.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * A missing model fails rather than skips, deliberately. The first run of this
 * suite reported "BUILD SUCCESSFUL in 14s" having executed nothing, because
 * every test had quietly assumed its way out — a green build that ran no tests
 * is worse than a red one, since only the red one gets investigated.
 */
class LlamaEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireModel(): File {
        val file = File(context.getExternalFilesDir(null), MODEL_NAME)
        if (!file.isFile) {
            fail(
                "No model at ${file.absolutePath}.\n" +
                    "Push one first — see this class's KDoc. Failing rather than " +
                    "skipping on purpose: these are the only tests that exercise " +
                    "native code, and a silent skip reads as a pass."
            )
        }
        return file
    }

    private val messages = listOf(
        Message(Message.Role.SYSTEM, "Answer in one short sentence."),
        Message(Message.Role.USER, "What colour is a clear midday sky?"),
    )

    @Test
    fun loadsFromAPathAndReportsItsContextWindow() {
        val model = requireModel()

        LlamaEngine.openPath(context, model.absolutePath, contextTokens = 512).use { engine ->
            assertEquals(512, engine.info.contextTokens)
            assertTrue("model description was blank", engine.info.name.isNotBlank())
        }
    }

    @Test
    fun tokenizesWithTheModelsOwnVocabulary() {
        val model = requireModel()

        LlamaEngine.openPath(context, model.absolutePath, contextTokens = 512).use { engine ->
            val count = engine.countTokens("The quick brown fox jumps over the lazy dog.")
            // Exact value is model-specific; what matters is that it is a real
            // count from the GGUF vocabulary rather than a stub or a crash.
            assertTrue("implausible token count: $count", count in 5..25)
        }
    }

    @Test
    fun loadsThroughAFileDescriptorPath() {
        val model = requireModel()

        // The mechanism SAF forces on us: llama.cpp needs a path to mmap and a
        // content:// URI has none, so the descriptor is bridged through
        // /proc/self/fd/N. This is the assumption the whole sideload design
        // rests on — if mmap refuses it, first run needs a 1 GB copy instead.
        val pfd = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use {
            LlamaEngine.openPath(
                context,
                "/proc/self/fd/${it.fd}",
                contextTokens = 512,
            ).use { engine ->
                assertTrue(engine.info.name.isNotBlank())
            }
        }
    }

    @Test
    fun loadsFromAContentResolverUri() {
        val model = requireModel()

        // file:// goes through the same ContentResolver path a SAF content://
        // URI would, without needing a user to pick anything.
        LlamaEngine.open(context, Uri.fromFile(model), contextTokens = 512).use { engine ->
            assertTrue(engine.info.contextTokens > 0)
        }
    }

    @Test
    fun generatesTextThatArrivesInFragments() = runBlocking {
        val model = requireModel()

        LlamaEngine.openPath(context, model.absolutePath, contextTokens = 1024).use { engine ->
            val pieces = engine.generate(messages, GenerationParams(maxTokens = 24, temperature = 0f))
                .toList()

            assertTrue("no tokens produced", pieces.isNotEmpty())
            val answer = pieces.joinToString("")
            assertTrue("answer was blank", answer.isNotBlank())
            // A wrong chat template usually shows up as the model echoing turn
            // markers back rather than answering.
            assertTrue("template markers leaked: $answer", !answer.contains("<|im_start|>"))
        }
    }

    @Test
    fun abandoningCollectionStopsGeneration() = runBlocking {
        val model = requireModel()

        LlamaEngine.openPath(context, model.absolutePath, contextTokens = 1024).use { engine ->
            val partial = engine.generate(messages, GenerationParams(maxTokens = 200, temperature = 0f))
                .take(3)
                .toList()

            assertEquals(3, partial.size)
        }
    }

    @Test
    fun theSameEngineAnswersTwiceWithoutCarryingStateOver() = runBlocking {
        val model = requireModel()

        LlamaEngine.openPath(context, model.absolutePath, contextTokens = 1024).use { engine ->
            val params = GenerationParams(maxTokens = 16, temperature = 0f)
            val first = engine.generate(messages, params).toList().joinToString("")
            val second = engine.generate(messages, params).toList().joinToString("")

            // Greedy sampling and a cleared KV cache: the same question must
            // give the same answer. Drift here means leftover state is
            // conditioning the next reply.
            assertEquals(first, second)
        }
    }

    private companion object {
        const val MODEL_NAME = "model.gguf"
    }
}
