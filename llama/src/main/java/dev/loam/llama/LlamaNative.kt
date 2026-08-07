package dev.loam.llama

/**
 * Raw JNI entry points. Everything here is unsafe by nature — a stale handle is
 * a segfault, not an exception — so nothing outside this package should touch
 * it. [LlamaEngine] owns the lifetime and is the supported surface.
 */
internal object LlamaNative {

    init {
        // Order matters: the JNI shim links against these, and the CPU backend
        // variants are dlopen'd later by backendInit.
        System.loadLibrary("llama")
        System.loadLibrary("loamllama")
    }

    /**
     * @param libDir `ApplicationInfo.nativeLibraryDir`. A GGML_BACKEND_DL build
     *   scans a directory for its CPU backends and defaults to the executable's
     *   path, which on Android is `/system/bin/app_process`. Without the real
     *   directory ggml finds no CPU backend and model loading fails with
     *   nothing obviously wrong in the log.
     */
    external fun backendInit(libDir: String)

    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    /**
     * The SAF path. `/proc/self/fd/N` cannot be used: opening it re-opens the
     * underlying file, and a SAF grant covers the URI rather than the path, so
     * the re-open is denied. Native side maps this descriptor directly.
     */
    external fun loadModelFd(fd: Int, nCtx: Int, nThreads: Int): Long

    external fun free(handle: Long)

    external fun contextTokens(handle: Long): Int

    external fun describe(handle: Long): String

    external fun countTokens(handle: Long, text: String): Int

    external fun applyTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
    ): String

    /** [callback] returns false to stop generation at the next token. */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        callback: TokenCallback,
    )

    /** Invoked from native code on the calling thread. */
    internal fun interface TokenCallback {
        fun onToken(text: String): Boolean
    }
}
