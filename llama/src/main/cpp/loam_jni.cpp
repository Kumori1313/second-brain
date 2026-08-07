// JNI shim over llama.cpp.
//
// Kept as thin as it can be: everything that can live in Kotlin does, because
// a mistake here is a native crash or a silent quality regression rather than
// a failing test. The only logic that belongs on this side is what needs the
// C API — model lifetime, tokenization, the chat template, and the decode loop.

#include <jni.h>
#include <android/log.h>

#include <string>
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

    llama_model_params mparams = llama_model_default_params();
    // mmap, explicitly, and never mlock. Mapping keeps the weights out of the
    // app's heap and lets the kernel evict pages under pressure — the
    // difference between a 1 GB allocation and a 1 GB file mapping on a phone
    // with ~1 GB free. mlock would pin all of it and invite the OOM killer.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        throw_load_error(env, "Could not load model: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = (uint32_t) nCtx;
    cparams.n_threads   = nThreads;
    cparams.n_batch     = 512;
    cparams.n_threads_batch = nThreads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_load_error(env, "Could not create context for: " + path);
        return 0;
    }

    auto * session = new Session{model, ctx};
    LOGI("loaded %s (n_ctx=%d, threads=%d)", path.c_str(), nCtx, nThreads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_dev_loam_llama_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    Session * s = as_session(handle);
    if (s == nullptr) return;
    if (s->ctx   != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
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

    // Start from a clean slate: each question is independent, and leftover KV
    // from a previous answer would silently condition this one.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("prompt decode failed (%zu tokens)", tokens.size());
        return;
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
