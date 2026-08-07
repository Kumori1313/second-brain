// JNI shim over llama.cpp.
//
// Kept as thin as it can be: everything that can live in Kotlin does, because
// a mistake here is a native crash or a silent quality regression rather than
// a failing test. The only logic that belongs on this side is what needs the
// C API — model lifetime, tokenization, the chat template, and the decode loop.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "LoamLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    // Only set when loaded from a descriptor. llama_file borrows a FILE* with
    // owns_fp(false), so closing it is ours to do — after the model, since the
    // loader may still read through it.
    FILE          * fp    = nullptr;
};

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

Session * as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

llama_model_params default_model_params() {
    llama_model_params mparams = llama_model_default_params();
    // mmap, explicitly, and never mlock. Mapping keeps the weights out of the
    // app's heap and lets the kernel evict pages under pressure — the
    // difference between a 1 GB allocation and a 1 GB file mapping on a phone
    // with ~1 GB free. mlock would pin all of it and invite the OOM killer.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    return mparams;
}

/** Builds the context and wraps everything up, or cleans up and returns null. */
Session * finish_session(llama_model * model, FILE * fp, int n_ctx, int n_threads) {
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.n_batch         = 512;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        if (fp != nullptr) fclose(fp);
        return nullptr;
    }
    return new Session{model, ctx, fp};
}

void throw_load_error(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("dev/loam/core/llm/ModelLoadException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

std::vector<llama_token> tokenize(
    const llama_vocab * vocab,
    const std::string & text,
    bool add_special
) {
    // Negative return is the required capacity; llama.cpp uses that to let
    // callers size the buffer in one retry rather than guessing.
    const int32_t needed = -llama_tokenize(
        vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens(needed);
    const int32_t written = llama_tokenize(
        vocab, text.data(), (int32_t) text.size(),
        tokens.data(), needed, add_special, true);
    if (written < 0) return {};
    tokens.resize(written);
    return tokens;
}

} // namespace

extern "C" {

/**
 * Loads the CPU backend shared objects.
 *
 * A GGML_BACKEND_DL build finds its backends by scanning a directory, and its
 * default guess is the executable's own path — which on Android is
 * /system/bin/app_process, not the app. The caller passes
 * ApplicationInfo.nativeLibraryDir instead. Skipping this leaves ggml with no
 * CPU backend at all and model loading fails with nothing obviously wrong.
 */
JNIEXPORT void JNICALL
Java_dev_loam_llama_LlamaNative_backendInit(JNIEnv * env, jclass, jstring libDir) {
    static bool done = false;
    if (done) return;

    const std::string dir = to_string(env, libDir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    }, nullptr);
    done = true;
}

JNIEXPORT jlong JNICALL
Java_dev_loam_llama_LlamaNative_loadModel(
    JNIEnv * env, jclass, jstring pathJ, jint nCtx, jint nThreads
) {
    const std::string path = to_string(env, pathJ);

    llama_model * model = llama_model_load_from_file(path.c_str(), default_model_params());
    if (model == nullptr) {
        throw_load_error(env, "Could not load model: " + path);
        return 0;
    }

    Session * session = finish_session(model, nullptr, nCtx, nThreads);
    if (session == nullptr) {
        throw_load_error(env, "Could not create context for: " + path);
        return 0;
    }
    LOGI("loaded %s (n_ctx=%d, threads=%d)", path.c_str(), nCtx, nThreads);
    return reinterpret_cast<jlong>(session);
}

/**
 * Loads from an already-open descriptor.
 *
 * This is what SAF actually requires, and the reason the obvious approach
 * fails. llama.cpp wants a path to mmap, and a `content://` URI has none, so
 * the tempting bridge is `/proc/self/fd/N` — but opening that path *re-opens*
 * the underlying file, which needs filesystem permission on it. A SAF grant
 * confers permission on the URI, not the path, so the re-open is denied:
 *
 *     gguf_init_from_file: failed to open GGUF file '/proc/self/fd/96'
 *     (Permission denied)
 *
 * `llama_model_load_from_file_ptr` takes a `FILE *` instead and mmaps
 * `fileno(fp)` directly, so the descriptor SAF already handed us is mapped
 * without ever being reopened. No copy into app storage, and no 1 GB of heap.
 *
 * The descriptor is duped because the caller's ParcelFileDescriptor owns
 * theirs, and llama_file borrows the FILE* without taking ownership — so this
 * side closes exactly what it opened, and neither closes the other's.
 */
JNIEXPORT jlong JNICALL
Java_dev_loam_llama_LlamaNative_loadModelFd(
    JNIEnv * env, jclass, jint fd, jint nCtx, jint nThreads
) {
    const int duped = dup(fd);
    if (duped < 0) {
        throw_load_error(env, "Could not duplicate model file descriptor");
        return 0;
    }

    FILE * fp = fdopen(duped, "rb");
    if (fp == nullptr) {
        close(duped);
        throw_load_error(env, "Could not open model file descriptor");
        return 0;
    }

    llama_model * model = llama_model_load_from_file_ptr(fp, default_model_params());
    if (model == nullptr) {
        fclose(fp);
        throw_load_error(env, "Could not load model from descriptor");
        return 0;
    }

    Session * session = finish_session(model, fp, nCtx, nThreads);
    if (session == nullptr) {
        throw_load_error(env, "Could not create context for model descriptor");
        return 0;
    }
    LOGI("loaded from fd (n_ctx=%d, threads=%d)", nCtx, nThreads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_dev_loam_llama_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return;
    if (s->ctx   != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    // After the model: the loader reads through this handle, and llama_file
    // borrows it without owning it.
    if (s->fp    != nullptr) fclose(s->fp);
    delete s;
}

JNIEXPORT jint JNICALL
Java_dev_loam_llama_LlamaNative_contextTokens(JNIEnv *, jclass, jlong handle) {
    Session * s = as_session(handle);
    return s == nullptr ? 0 : (jint) llama_n_ctx(s->ctx);
}

JNIEXPORT jstring JNICALL
Java_dev_loam_llama_LlamaNative_describe(JNIEnv * env, jclass, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return env->NewStringUTF("");
    char buf[256] = {0};
    llama_model_desc(s->model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_dev_loam_llama_LlamaNative_countTokens(
    JNIEnv * env, jclass, jlong handle, jstring textJ
) {
    Session * s = as_session(handle);
    if (s == nullptr) return 0;
    const std::string text = to_string(env, textJ);
    return (jint) tokenize(llama_model_get_vocab(s->model), text, false).size();
}

/**
 * Renders chat messages with the template baked into the GGUF.
 *
 * Each instruction-tuned model wants its own turn markers, and using the wrong
 * ones degrades answers quietly instead of failing — the reason [LlmEngine]
 * takes messages rather than a formatted prompt. Falls back to ChatML only
 * when the model carries no template of its own.
 */
JNIEXPORT jstring JNICALL
Java_dev_loam_llama_LlamaNative_applyTemplate(
    JNIEnv * env, jclass, jlong handle, jobjectArray rolesJ, jobjectArray contentsJ
) {
    Session * s = as_session(handle);
    if (s == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(rolesJ);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> messages(n);
    for (jsize i = 0; i < n; ++i) {
        roles[i]    = to_string(env, (jstring) env->GetObjectArrayElement(rolesJ, i));
        contents[i] = to_string(env, (jstring) env->GetObjectArrayElement(contentsJ, i));
        messages[i] = { roles[i].c_str(), contents[i].c_str() };
    }

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) {
        LOGI("model carries no chat template; falling back to chatml");
        tmpl = "chatml";
    }

    std::vector<char> buf(8192);
    int32_t len = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGE("chat template failed");
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(std::string(buf.data(), len).c_str());
}

/**
 * Decodes the prompt, then streams tokens to the callback.
 *
 * The callback returns false to stop, which is how coroutine cancellation
 * reaches native code: abandoning the Flow stops generation at the next token
 * instead of letting the model run to maxTokens behind a dismissed UI.
 */
JNIEXPORT void JNICALL
Java_dev_loam_llama_LlamaNative_generate(
    JNIEnv * env, jclass, jlong handle,
    jstring promptJ, jint maxTokens, jfloat temperature, jint seed,
    jobject callback
) {
    Session * s = as_session(handle);
    if (s == nullptr) return;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    if (onToken == nullptr) {
        LOGE("callback has no onToken(String):boolean");
        return;
    }

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt  = to_string(env, promptJ);

    // The prompt is already fully templated, so special tokens are present as
    // text and must be parsed, not escaped.
    std::vector<llama_token> tokens = tokenize(vocab, prompt, false);
    if (tokens.empty()) {
        LOGE("prompt tokenized to nothing");
        return;
    }

    const uint32_t n_ctx = llama_n_ctx(s->ctx);
    if (tokens.size() >= n_ctx) {
        // Would overrun the window. The caller budgets against this, but a
        // mismatch here must not reach llama_decode, which aborts the process
        // rather than returning an error.
        LOGE("prompt of %zu tokens does not fit a %u-token context", tokens.size(), n_ctx);
        return;
    }

    // Start from a clean slate: each question is independent, and leftover KV
    // from a previous answer would silently condition this one.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    // Feed the prompt in n_batch-sized pieces. llama_decode asserts — and
    // therefore aborts the whole process — when a single batch exceeds
    // n_batch, and a real RAG prompt is well over the 512 default. Passing the
    // whole prompt at once survives short test prompts and kills the app on
    // the first genuine question.
    const int32_t n_batch = (int32_t) llama_n_batch(s->ctx);
    for (size_t off = 0; off < tokens.size(); off += n_batch) {
        const int32_t n = (int32_t) std::min((size_t) n_batch, tokens.size() - off);
        llama_batch batch = llama_batch_get_one(tokens.data() + off, n);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("prompt decode failed at offset %zu of %zu", off, tokens.size());
            return;
        }
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(
            seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }

    char piece[256];
    for (int i = 0; i < maxTokens; ++i) {
        const llama_token id = llama_sampler_sample(chain, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        const int32_t n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
        if (n > 0) {
            jstring text = env->NewStringUTF(std::string(piece, n).c_str());
            const jboolean keepGoing = env->CallBooleanMethod(callback, onToken, text);
            env->DeleteLocalRef(text);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (keepGoing == JNI_FALSE) break;
        }

        llama_sampler_accept(chain, id);
        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(s->ctx, next) != 0) {
            LOGE("decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(chain);
}

} // extern "C"
