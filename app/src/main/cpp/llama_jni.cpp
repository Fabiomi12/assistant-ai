// app/src/main/cpp/llama_jni.cpp

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <thread>
#include "llama.h"

#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// -----------------------------
// JNI: Initialize and free
// -----------------------------
JNIEXPORT jlong JNICALL
Java_edu_upt_assistant_LlamaNative_llamaCreate(
        JNIEnv* env, jclass, jstring modelPathJ, jint nThreads
) {
    const char* path = env->GetStringUTFChars(modelPathJ, nullptr);
    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPathJ, path);
    if (!model) {
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to load model");
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    int threads = nThreads > 0 ? nThreads : static_cast<int>(std::thread::hardware_concurrency());
    if (threads <= 0) {
        threads = 1;
    }
    cparams.n_threads = threads;
    LOGI("Using %d threads", threads);
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to init context");
        return 0;
    }
    LOGI("Context initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaFree(
        JNIEnv*, jclass, jlong ctxPtr
) {
    auto* ctx = reinterpret_cast<llama_context*>(ctxPtr);
    if (ctx) {
        const llama_model* model = llama_get_model(ctx);
        llama_free(ctx);
        if (model) {
            llama_model_free(const_cast<llama_model*>(model));
        }
        LOGI("Context freed");
    }
}

// -----------------------------
// JNI: Synchronous Generation
// -----------------------------
JNIEXPORT jstring JNICALL
Java_edu_upt_assistant_LlamaNative_llamaGenerate(
        JNIEnv* env, jclass, jlong ctxPtr,
        jstring promptJ, jint maxTokens
) {
    auto* ctx = reinterpret_cast<llama_context*>(ctxPtr);
    if (!ctx) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Invalid context");
        return nullptr;
    }
    // Get prompt text
    const char* prompt = env->GetStringUTFChars(promptJ, nullptr);
    const llama_model* model = llama_get_model(ctx);
    if (!model) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Model not initialized");
        return nullptr;
    }
    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Vocab not initialized");
        return nullptr;
    }

    // Tokenize prompt
    std::vector<llama_token> tokens(4096);
    int32_t ntok = llama_tokenize(
            vocab,
            prompt,
            /*text_len=*/static_cast<int32_t>(strlen(prompt)),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            /*add_special=*/true,
            /*parse_special=*/false
    );
    env->ReleaseStringUTFChars(promptJ, prompt);
    if (ntok < 0) ntok = 0;

    // Create batch and decode initial prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), ntok);
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Initial decode failed");
    }

    // Prepare output
    std::string out;
    out.reserve(maxTokens * 4);
    int32_t vocab_size = llama_n_vocab(vocab);

    // Greedy generative loop
    for (int i = 0; i < maxTokens; ++i) {
        float* logits = llama_get_logits_ith(ctx, -1);
        if (!logits) break;
        int best = 0;
        float maxl = logits[0];
        for (int j = 1; j < vocab_size; ++j) {
            if (logits[j] > maxl) {
                maxl = logits[j];
                best = j;
            }
        }
        auto tok = static_cast<llama_token>(best);
        const char* piece = llama_vocab_get_text(vocab, tok);
        if (!piece) break;
        out += piece;

        // decode this token
        llama_batch b2 = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b2) != 0) {
            LOGE("Decode token failed");
            break;
        }
    }

    return env->NewStringUTF(out.c_str());
}

// -----------------------------
// JNI: Streaming Generation
// -----------------------------
JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaGenerateStream(
        JNIEnv* env, jclass, jlong ctxPtr,
        jstring promptJ, jint maxTokens,
        jobject callback
) {
    auto* ctx = reinterpret_cast<llama_context*>(ctxPtr);
    if (!ctx || !callback) return;

    const char* prompt = env->GetStringUTFChars(promptJ, nullptr);
    const llama_model* model = llama_get_model(ctx);
    if (!model) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        return;
    }
    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        return;
    }

    // Tokenize and decode prompt
    std::vector<llama_token> tokens(4096);
    int32_t ntok = llama_tokenize(
            vocab,
            prompt,
            /*text_len=*/static_cast<int32_t>(strlen(prompt)),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            /*add_special=*/true,
            /*parse_special=*/false
    );
    env->ReleaseStringUTFChars(promptJ, prompt);
    if (ntok < 0) ntok = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), ntok);
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Initial decode failed");
        return;
    }

    // Prepare Java callback method
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    int32_t vocab_size = llama_n_vocab(vocab);

    // Stream tokens as generated
    for (int i = 0; i < maxTokens; ++i) {
        float* logits = llama_get_logits_ith(ctx, -1);
        if (!logits) break;
        int best = 0;
        float maxl = logits[0];
        for (int j = 1; j < vocab_size; ++j) {
            if (logits[j] > maxl) {
                maxl = logits[j];
                best = j;
            }
        }
        auto tok = static_cast<llama_token>(best);
        const char* piece = llama_vocab_get_text(vocab, tok);
        if (!piece) break;
        jstring pieceJ = env->NewStringUTF(piece);
        env->CallVoidMethod(callback, onToken, pieceJ);
        env->DeleteLocalRef(pieceJ);

        llama_batch b2 = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b2) != 0) {
            LOGE("Decode token failed");
            break;
        }
    }
}

} // extern "C"
