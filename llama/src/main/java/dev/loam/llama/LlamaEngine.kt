package dev.loam.llama

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.loam.core.llm.GenerationParams
import dev.loam.core.llm.LlmEngine
import dev.loam.core.llm.Message
import dev.loam.core.llm.ModelInfo
import dev.loam.core.llm.ModelLoadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * [LlmEngine] backed by llama.cpp.
 *
 * Owns the native handle: nothing else may hold it, and every call goes through
 * [mutex], because llama.cpp's context is not safe for concurrent use and the
 * failure mode is memory corruption rather than an exception.
 */
class LlamaEngine private constructor(
    private val handle: Long,
    private val fd: ParcelFileDescriptor?,
    override val info: ModelInfo,
) : LlmEngine {

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    override fun countTokens(text: String): Int {
        check(!closed) { "engine is closed" }
        return LlamaNative.countTokens(handle, text)
    }

    override fun generate(
        messages: List<Message>,
        params: GenerationParams,
    ): Flow<String> = callbackFlow {
        check(!closed) { "engine is closed" }

        mutex.withLock {
            val prompt = LlamaNative.applyTemplate(
                handle,
                messages.map { it.role.wireName }.toTypedArray(),
                messages.map { it.content }.toTypedArray(),
            )

            LlamaNative.generate(
                handle,
                prompt,
                params.maxTokens,
                params.temperature,
                params.seed ?: -1,
            ) { token ->
                // trySend fails only if the consumer is gone, which is exactly
                // when generation should stop. Returning false unwinds the
                // native decode loop at the next token rather than letting it
                // run to maxTokens behind a dismissed UI.
                trySend(token).isSuccess && isActive
            }
        }
        close()
        awaitClose { }
    }
        // Generation is a long blocking native call. Without this it would run
        // on whatever dispatcher collected it — in practice the main thread,
        // which is a ten-second ANR.
        .flowOn(Dispatchers.Default)
        // The native loop calls back synchronously, so an unbuffered channel
        // would stall decoding on every UI frame.
        .buffer(BUFFERED_TOKENS)

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.free(handle)
        fd?.close()
    }

    private val Message.Role.wireName: String
        get() = when (this) {
            Message.Role.SYSTEM -> "system"
            Message.Role.USER -> "user"
            Message.Role.ASSISTANT -> "assistant"
        }

    companion object {
        private const val BUFFERED_TOKENS = 64

        /** Kept below the model's trained window unless the user raises it. */
        const val DEFAULT_CONTEXT_TOKENS = 4096

        /**
         * Four threads, measured. The Pixel 8a has nine cores and six threads
         * was slower than four (24.25 against 26.62 pp512) — llama.cpp's
         * threadpool waits on the slowest participant, so scheduling work onto
         * little cores costs more than the cores contribute.
         */
        const val DEFAULT_THREADS = 4

        /**
         * Opens a GGUF that the user picked through SAF.
         *
         * llama.cpp needs a filesystem path in order to mmap, and SAF hands
         * back a `content://` URI. `/proc/self/fd/N` bridges the two: opening
         * that path re-opens the underlying file, so the 1 GB of weights are
         * mapped in place rather than copied into app storage. The descriptor
         * has to outlive the model, which is why the engine holds it.
         */
        fun open(
            context: Context,
            model: Uri,
            contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
            threads: Int = DEFAULT_THREADS,
        ): LlamaEngine {
            LlamaNative.backendInit(context.applicationInfo.nativeLibraryDir)

            val fd = runCatching {
                context.contentResolver.openFileDescriptor(model, "r")
            }.getOrNull() ?: throw ModelLoadException("Cannot open model file: $model")

            val handle = try {
                LlamaNative.loadModel("/proc/self/fd/${fd.fd}", contextTokens, threads)
            } catch (e: Throwable) {
                fd.closeQuietly()
                throw e
            }
            if (handle == 0L) {
                fd.closeQuietly()
                throw ModelLoadException("llama.cpp could not load: $model")
            }

            return LlamaEngine(
                handle = handle,
                fd = fd,
                info = ModelInfo(
                    name = LlamaNative.describe(handle).ifBlank { "GGUF model" },
                    contextTokens = LlamaNative.contextTokens(handle),
                ),
            )
        }

        /** For a model already on a real path, e.g. copied into app storage. */
        fun openPath(
            context: Context,
            path: String,
            contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
            threads: Int = DEFAULT_THREADS,
        ): LlamaEngine {
            LlamaNative.backendInit(context.applicationInfo.nativeLibraryDir)
            val handle = LlamaNative.loadModel(path, contextTokens, threads)
            if (handle == 0L) throw ModelLoadException("llama.cpp could not load: $path")
            return LlamaEngine(
                handle = handle,
                fd = null,
                info = ModelInfo(
                    name = LlamaNative.describe(handle).ifBlank { "GGUF model" },
                    contextTokens = LlamaNative.contextTokens(handle),
                ),
            )
        }

        private fun Closeable.closeQuietly() = runCatching { close() }
    }
}
