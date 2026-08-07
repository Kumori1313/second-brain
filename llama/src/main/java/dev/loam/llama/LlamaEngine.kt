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
         * llama.cpp needs something to mmap and SAF hands back a `content://`
         * URI, so the descriptor is passed to native code and mapped directly.
         *
         * Note what does *not* work, because it looks like it should and fails
         * only against a real SAF grant: bridging through `/proc/self/fd/N`.
         * Opening that path re-opens the underlying file, which requires
         * filesystem permission on it, and a SAF grant confers permission on
         * the URI instead — so it fails with EACCES. It succeeds for files the
         * app could already open by path, which is exactly why a test using one
         * of those proves nothing.
         *
         * The descriptor must outlive the model, which is why the engine holds
         * it and closes it last.
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
                LlamaNative.loadModelFd(fd.fd, contextTokens, threads)
            } catch (e: Throwable) {
                fd.closeQuietly()
                throw e
            }
            if (handle == 0L) {
                fd.closeQuietly()
                throw ModelLoadException("llama.cpp could not load: $model")
            }

            return LlamaEngine(handle, fd, infoFor(handle))
        }

        /**
         * For a descriptor the caller already holds and will keep open for the
         * engine's lifetime. [open] is the usual entry point; this exists so
         * the descriptor path can be exercised directly in tests.
         */
        fun openFd(
            context: Context,
            fd: Int,
            contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
            threads: Int = DEFAULT_THREADS,
        ): LlamaEngine {
            LlamaNative.backendInit(context.applicationInfo.nativeLibraryDir)
            val handle = LlamaNative.loadModelFd(fd, contextTokens, threads)
            if (handle == 0L) throw ModelLoadException("llama.cpp could not load descriptor $fd")
            return LlamaEngine(handle, null, infoFor(handle))
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
            return LlamaEngine(handle, null, infoFor(handle))
        }

        private fun infoFor(handle: Long) = ModelInfo(
            name = LlamaNative.describe(handle).ifBlank { "GGUF model" },
            contextTokens = LlamaNative.contextTokens(handle),
        )

        private fun Closeable.closeQuietly() = runCatching { close() }
    }
}
