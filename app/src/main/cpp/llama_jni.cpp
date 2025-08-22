#include "llama.h"
#include <android/log.h>
#include <cstring>
#include <jni.h>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
#include <cctype>
#include "ggml.h"

#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ---------- helpers ----------

// Clear a llama_batch in a way that's compatible with older headers
static inline void batch_clear_compat(llama_batch* b) {
    for (int i = 0; i < b->n_tokens; ++i) {
        b->token[i]     = 0;
        b->pos[i]       = 0;
        b->n_seq_id[i]  = 0;
        b->seq_id[i][0] = 0;
        b->logits[i]    = 0;
    }
    b->n_tokens = 0;
}

// Tokenize with parse_special=true so chat headers are treated as single tokens
static std::vector<llama_token> tokenize_with_specials(const llama_vocab* vocab, const char* text) {
    int32_t needed = llama_tokenize(vocab, text, (int32_t)strlen(text),
                                    nullptr, 0,
            /*add_special=*/true,
            /*parse_special=*/true);
    if (needed < 0) needed = -needed;
    std::vector<llama_token> out(needed);
    int32_t got = llama_tokenize(vocab, text, (int32_t)strlen(text),
                                 out.data(), (int32_t)out.size(),
            /*add_special=*/true,
            /*parse_special=*/true);
    if (got < 0) {
        out.clear();
    } else {
        out.resize(got);
    }
    return out;
}

// Stop on real special tokens (EOS, EOT, ChatML <|im_end|>) with a fallback
static inline bool should_stop_generation(llama_token tok,
                                          const llama_vocab* vocab,
                                          llama_token tok_eos,
                                          llama_token tok_im_end,
                                          llama_token tok_eot) {
    if (tok == tok_eos) return true;
    if (tok_eot != -1 && tok == tok_eot) return true;       // Llama 3 end-of-turn
    if (tok_im_end != -1 && tok == tok_im_end) return true; // ChatML <|im_end|>
    if (llama_vocab_is_eog(vocab, tok)) return true;        // fallback
    return false;
}

// Best-effort KV clear compatible with older llama.cpp
static inline void kv_clear_compat(llama_context* ctx) {
#if defined(LLAMA_KV_CACHE_CLEAR) || defined(LLAMA_API_KV_CACHE_CLEAR)
    llama_kv_cache_clear(ctx);
#else
    llama_kv_self_clear(ctx);
#endif
}


// ---------- JNI: init / free ----------

JNIEXPORT jlong JNICALL
Java_edu_upt_assistant_LlamaNative_llamaCreate(JNIEnv *env, jclass, jstring modelPathJ, jint nThreads) {
    const char *path = env->GetStringUTFChars(modelPathJ, nullptr);
    if (!path) {
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to get model path");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPathJ, path);
    if (!model) {
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 1536;
    cparams.n_batch         = 256;
    cparams.n_ubatch        = 64;
    int threads = (nThreads > 0 ? nThreads : 8);
    threads = std::max(6, std::min(threads, 8)); // 6–8 threads
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;
#ifdef LLAMA_KV_8
    cparams.type_kv         = LLAMA_KV_8;
#endif

    LOGI("Using %d threads (ctx=%d, batch=%d, ubatch=%d)", threads, cparams.n_ctx, cparams.n_batch, cparams.n_ubatch);

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to init context");
        return 0;
    }

#ifdef NDEBUG
    LOGI("JNI build: Release");
#else
    LOGI("JNI build: Debug");
#endif

    LOGI("Context initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaFree(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (ctx) {
        const llama_model *model = llama_get_model(ctx);
        llama_free(ctx);
        if (model) llama_model_free(const_cast<llama_model *>(model));
        LOGI("Context and model freed");
    }
}

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaKvCacheClear(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (ctx) {
        kv_clear_compat(ctx);
        LOGI("KV cache cleared");
    }
}

// ---------- JNI: synchronous generate ----------

JNIEXPORT jstring JNICALL
Java_edu_upt_assistant_LlamaNative_llamaGenerate(JNIEnv *env, jclass, jlong ctxPtr, jstring promptJ, jint maxTokens) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (!ctx) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Invalid context");
        return nullptr;
    }

    // Always start from a clean slate for seq_id=0 to avoid collisions
    kv_clear_compat(ctx);
    // llama_reset_timings(ctx); // not available in your build

    const char *prompt = env->GetStringUTFChars(promptJ, nullptr);
    if (!prompt) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Failed to get prompt string");
        return nullptr;
    }

    const llama_model *model = llama_get_model(ctx);
    if (!model) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Model not initialized");
        return nullptr;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (!vocab) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Vocab not initialized");
        return nullptr;
    }

    // Tokenize with special parsing
    std::vector<llama_token> tokens = tokenize_with_specials(vocab, prompt);
    env->ReleaseStringUTFChars(promptJ, prompt);
    if (tokens.empty()) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }
    const int32_t ntok = (int32_t)tokens.size();
    LOGI("Tokenized prompt: %d tokens", ntok);

    // Prefill in chunks
    const int n_batch = 256;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    for (int32_t cur = 0; cur < ntok; ) {
        batch_clear_compat(&batch);
        const int32_t nb = std::min(n_batch, ntok - cur);
        for (int i = 0; i < nb; ++i) {
            const int32_t pos = cur + i;
            const bool set_logits = (pos == ntok - 1); // request logits only on final prefill token
            batch.token[i]     = tokens[pos];
            batch.pos[i]       = pos;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = set_logits ? 1 : 0;
        }
        batch.n_tokens = nb;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Batch decode failed at pos %d", cur);
            llama_batch_free(batch);
            return env->NewStringUTF("");
        }
        cur += nb;
    }

    // Sampler chain tuned for CPU: repeat penalty, top-k, top-p, temp
    // (consider dynatemp/xtc samplers if available)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(30));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Stop tokens
    const llama_token tok_eos = llama_vocab_eos(vocab);
    const llama_token tok_eot = llama_vocab_eot(vocab); // may be -1
    llama_token tok_im_end = -1;
    llama_token tok_gemma_eot = -1;
    {
        // ChatML end
        static const char* IM_END = "<|im_end|>";
        llama_token tmp[8];
        int32_t n1 = llama_tokenize(vocab, IM_END, (int32_t)strlen(IM_END),
                                    tmp, 8, /*add_special=*/true, /*parse_special=*/true);
        if (n1 == 1) tok_im_end = tmp[0];

        // Gemma end-of-turn
        static const char* GEMMA_EOT = "<end_of_turn>";
        int32_t n2 = llama_tokenize(vocab, GEMMA_EOT, (int32_t)strlen(GEMMA_EOT),
                                    tmp, 8, /*add_special=*/true, /*parse_special=*/true);
        if (n2 == 1) tok_gemma_eot = tmp[0];
    }

    // Decode loop
    std::string output;
    output.reserve(std::max(16, (int)maxTokens * 4));
    int n_cur = ntok;
    bool first = true;

    for (int i = 0; i < maxTokens; ++i) {
        llama_token next;
        if (first) {
            llama_sampler *s_first = llama_sampler_chain_init(sparams);
            llama_sampler_chain_add(s_first, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
            llama_sampler_chain_add(s_first, llama_sampler_init_top_k(30));
            llama_sampler_chain_add(s_first, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(s_first, llama_sampler_init_temp(0.2f));
            llama_sampler_chain_add(s_first, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            next = llama_sampler_sample(s_first, ctx, -1);
            llama_sampler_free(s_first);
            first = false;
        } else {
            next = llama_sampler_sample(sampler, ctx, -1);
        }
        if (should_stop_generation(next, vocab, tok_eos, tok_im_end, tok_eot) || (tok_gemma_eot != -1 && next == tok_gemma_eot))
            break;

        const char *piece = llama_vocab_get_text(vocab, next);
        if (!piece) break;

        std::string t(piece);
        if (t == "▁") t = " ";
        else if (t.size() >= 3 && t.substr(0, 3) == "▁") t = " " + t.substr(3);
        output += t;

        batch_clear_compat(&batch);
        batch.n_tokens     = 1;
        batch.token[0]     = next;
        batch.pos[0]       = n_cur++;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode token failed");
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    llama_perf_context_print(ctx);

    while (!output.empty() && std::isspace((unsigned char)output.back())) output.pop_back();
    return env->NewStringUTF(output.c_str());
}

// ---------- JNI: streaming generate ----------

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaGenerateStream(JNIEnv *env, jclass, jlong ctxPtr,
                                                       jstring promptJ, jint maxTokens, jobject callback) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (!ctx || !callback) { LOGE("Invalid context or callback"); return; }

    // Always clear KV first to avoid overlapping seq_id=0 history
    kv_clear_compat(ctx);
    // llama_reset_timings(ctx); // not available in your build

    const char *prompt = env->GetStringUTFChars(promptJ, nullptr);
    if (!prompt) { LOGE("Failed to get prompt"); return; }

    const llama_model *model = llama_get_model(ctx);
    if (!model) { env->ReleaseStringUTFChars(promptJ, prompt); LOGE("Model not initialized"); return; }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (!vocab) { env->ReleaseStringUTFChars(promptJ, prompt); LOGE("Vocab not initialized"); return; }

    std::vector<llama_token> tokens = tokenize_with_specials(vocab, prompt);
    env->ReleaseStringUTFChars(promptJ, prompt);
    if (tokens.empty()) { LOGE("Tokenization failed"); return; }
    const int32_t ntok = (int32_t)tokens.size();
    LOGI("Streaming: tokenized prompt %d tokens", ntok);

    const int n_batch = 256;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    const int64_t t0 = ggml_time_us();

    // Prefill
    for (int32_t cur = 0; cur < ntok; ) {
        batch_clear_compat(&batch);
        const int32_t nb = std::min(n_batch, ntok - cur);
        for (int i = 0; i < nb; ++i) {
            const int32_t pos = cur + i;
            const bool set_logits = (pos == ntok - 1);
            batch.token[i]     = tokens[pos];
            batch.pos[i]       = pos;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = set_logits ? 1 : 0;
        }
        batch.n_tokens = nb;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Streaming batch decode failed at pos %d", cur);
            llama_batch_free(batch);
            return;
        }
        cur += nb;
    }

    const int64_t t_prefill_done = ggml_time_us();

    // Java callback
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) { LOGE("Failed to find onToken"); llama_batch_free(batch); return; }

    // Sampler
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Stop tokens
    const llama_token tok_eos = llama_vocab_eos(vocab);
    const llama_token tok_eot = llama_vocab_eot(vocab);
    llama_token tok_im_end = -1;
    llama_token tok_gemma_eot = -1;
    {
        llama_token tmp[8];

        // ChatML end
        const char *s1 = "<|im_end|>";
        int32_t n1 = llama_tokenize(vocab, s1, (int32_t)strlen(s1),
                                    tmp, 8, /*add_special=*/true, /*parse_special=*/true);
        if (n1 == 1) tok_im_end = tmp[0];

        // Gemma end-of-turn
        const char *s2 = "<end_of_turn>";
        int32_t n2 = llama_tokenize(vocab, s2, (int32_t)strlen(s2),
                                    tmp, 8, /*add_special=*/true, /*parse_special=*/true);
        if (n2 == 1) tok_gemma_eot = tmp[0];
    }

    int n_cur = ntok;
    bool logged_first_sample = false;

    bool first = true;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token next;
        if (first) {
            llama_sampler *s_first = llama_sampler_chain_init(sparams);
            llama_sampler_chain_add(s_first, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(s_first, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(s_first, llama_sampler_init_temp(0.2f)); // <-- colder
            llama_sampler_chain_add(s_first, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            next = llama_sampler_sample(s_first, ctx, -1);
            llama_sampler_free(s_first);
            first = false;
        } else {
            next = llama_sampler_sample(sampler, ctx, -1);
        }

        if (!logged_first_sample) {
            const int64_t t_first = ggml_time_us();
            LOGI("TIMINGS us: prefill=%lld, first_sample_delay=%lld",
                 (long long)(t_prefill_done - t0),
                 (long long)(t_first - t_prefill_done));
            logged_first_sample = true;
        }

        if (should_stop_generation(next, vocab, tok_eos, tok_im_end, tok_eot) || (tok_gemma_eot != -1 && next == tok_gemma_eot)) break;

        const char *piece = llama_vocab_get_text(vocab, next);
        if (!piece) break;

        std::string t(piece);
        if (t == "▁") t = " ";
        else if (t.size() >= 3 && t.substr(0, 3) == "▁") t = " " + t.substr(3);

        jstring pieceJ = env->NewStringUTF(t.c_str());
        if (pieceJ) {
            env->CallVoidMethod(callback, onToken, pieceJ);
            env->DeleteLocalRef(pieceJ);
            if (env->ExceptionCheck()) { env->ExceptionClear(); LOGE("Java exception in callback"); break; }
        }

        batch_clear_compat(&batch);
        batch.n_tokens     = 1;
        batch.token[0]     = next;
        batch.pos[0]       = n_cur++;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Streaming decode token failed");
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    llama_perf_context_print(ctx);
    LOGI("Streaming generation completed");
}

} // extern "C"
