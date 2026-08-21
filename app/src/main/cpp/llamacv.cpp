/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// JNI bridge to llama.cpp, for summarising a transcript on the device.
//
// Deliberately the same shape as whispercv.cpp next door: an opaque pointer paired with exactly one
// free, one Kotlin owner serialising access, and a real abort flag — because a generate is one
// blocking native call that neither coroutine cancellation nor WorkManager can interrupt. That
// lesson was learned the expensive way on the transcription side; it is not relearned here.
//
// Summary text is never logged. It is the substance of a private call, exactly like the transcript
// it is made from.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"

#define TAG "CV:llamacv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// Set from any thread; read by the token loop and by ggml between computation steps.
static std::atomic<bool> g_abort{false};

static bool abort_requested(void * /* user_data */) {
    return g_abort.load(std::memory_order_relaxed);
}

// The CPU backend ships as its own .so (the build defines GGML_BACKEND_SHARED), so it has to be
// dlopen'd before a model will load. Once per process, and not once per generate.
static std::once_flag g_backends_once;

static void ensure_backends() {
    std::call_once(g_backends_once, [] {
        ggml_backend_load_all();
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            // Upstream chatter only. Prompts and generated text never pass through here.
            if (level >= GGML_LOG_LEVEL_WARN) LOGW("%s", text);
        }, nullptr);
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_systemInfo(JNIEnv *env, jobject /* this */) {
    ensure_backends();
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT void JNICALL
Java_com_baba_callvault_summary_LlamaNative_requestAbort(JNIEnv * /* env */, jobject /* this */) {
    g_abort.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_baba_callvault_summary_LlamaNative_initContext(JNIEnv *env, jobject /* this */, jstring path) {
    ensure_backends();

    const char *c_path = env->GetStringUTFChars(path, nullptr);
    llama_model_params params = llama_model_default_params();
    // CPU only. A phone's GPU/NPU is not reliably reachable from an app process, and the point of
    // this spike is what an ordinary device can do without one.
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(c_path, params);
    if (model == nullptr) {
        LOGW("could not load the model");
    }
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_baba_callvault_summary_LlamaNative_freeContext(JNIEnv * /* env */, jobject /* this */, jlong ptr) {
    if (ptr == 0) return;
    llama_model_free(reinterpret_cast<llama_model *>(ptr));
}

/** Tokens in [text], or a negative value if it cannot be tokenised. Used to size chunks honestly. */
extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_summary_LlamaNative_countTokens(JNIEnv *env, jobject /* this */, jlong ptr, jstring text) {
    if (ptr == 0) return -1;
    const auto *model = reinterpret_cast<const llama_model *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *c_text = env->GetStringUTFChars(text, nullptr);
    const int n = -llama_tokenize(vocab, c_text, (int32_t) strlen(c_text), nullptr, 0, true, true);
    env->ReleaseStringUTFChars(text, c_text);
    return n;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_generate(
        JNIEnv *env, jobject /* this */, jlong ptr, jstring prompt, jint maxTokens, jint threads) {

    if (ptr == 0) return env->NewStringUTF("");

    // Cleared here, not on abort: a stale flag left by a previous run must never kill this one.
    g_abort.store(false, std::memory_order_relaxed);

    auto *model = reinterpret_cast<llama_model *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *c_prompt = env->GetStringUTFChars(prompt, nullptr);

    // Wrap the prompt in the model's own chat template.
    //
    // These are instruct models: they were trained to answer text arriving inside their template,
    // with the assistant turn opened for them. Handed a bare paragraph they still produce something,
    // but it is not what they are good at — and measuring that would be measuring our mistake rather
    // than the model. Falls back to the bare prompt for a model that carries no template.
    std::string templated;
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) {
        llama_chat_message msg{"user", c_prompt};
        // Twice the input is upstream's recommended allocation; the template adds only control tags.
        std::vector<char> buf(strlen(c_prompt) * 2 + 1024);
        const int32_t written =
                llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (written > 0 && written <= (int32_t) buf.size()) {
            templated.assign(buf.data(), written);
        }
    }
    if (templated.empty()) templated = c_prompt;

    const char *text = templated.c_str();
    const auto prompt_len = (int32_t) templated.size();

    const int n_prompt = -llama_tokenize(vocab, text, prompt_len, nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    const bool tokenised =
            llama_tokenize(vocab, text, prompt_len, tokens.data(), (int32_t) tokens.size(), true, true) >= 0;
    env->ReleaseStringUTFChars(prompt, c_prompt);

    if (!tokenised) {
        LOGW("could not tokenise the prompt");
        return env->NewStringUTF("");
    }

    llama_context_params ctx_params = llama_context_default_params();
    // Room for the prompt and the answer, but never past what the model was trained for: asking for
    // more silently degrades output rather than failing, which is the worst way to be wrong.
    const uint32_t wanted = (uint32_t) (n_prompt + maxTokens);
    ctx_params.n_ctx           = std::min(wanted, (uint32_t) llama_model_n_ctx_train(model));
    ctx_params.n_batch         = (uint32_t) n_prompt > 0 ? (uint32_t) n_prompt : 512;
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;
    // Aborts the prefill too, not just the token loop. On a long transcript the prefill IS the wait,
    // so an abort that only checked between tokens would appear to do nothing for a minute.
    ctx_params.abort_callback      = abort_requested;
    ctx_params.abort_callback_data = nullptr;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGW("could not create a context");
        return env->NewStringUTF("");
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Greedy. A summary is not creative writing, and a deterministic run is one a measurement can
    // actually compare across models.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());

    // Declared out here, not inside the loop. llama_batch_get_one keeps a POINTER to the token, and
    // the batch is read at the top of the next iteration — so a token scoped to the loop body would
    // be read after it had gone. The upstream example does the same, for the same reason.
    llama_token sampled = 0;

    const int64_t t_start = ggml_time_us();
    int generated = 0;

    for (int n_pos = 0; n_pos + batch.n_tokens < (int) ctx_params.n_ctx && generated < maxTokens; ) {
        if (llama_decode(ctx, batch) != 0) break;   // includes the abort path
        if (g_abort.load(std::memory_order_relaxed)) break;

        n_pos += batch.n_tokens;

        sampled = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, sampled)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, sampled, piece, sizeof(piece), 0, true);
        if (n < 0) break;
        out.append(piece, n);
        generated++;

        batch = llama_batch_get_one(&sampled, 1);
    }

    // Counts and timings only — never the text.
    LOGI("generated %d tokens from a %d-token prompt in %lld ms",
         generated, n_prompt, (long long) ((ggml_time_us() - t_start) / 1000));

    llama_sampler_free(sampler);
    llama_free(ctx);

    return env->NewStringUTF(out.c_str());
}
