// app/src/main/cpp/llama_jni.cpp

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
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
        JNIEnv* env, jclass, jstring modelPathJ
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
    cparams.n_threads = 4;
    llama_context* ctx = llama_init_from_model(model, cparams);
    llama_model_free(model);
    if (!ctx) {
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
        llama_free(ctx);
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
    const llama_vocab* vocab = llama_model_get_vocab(model);

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
    llama_batch_free(batch);

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
        // Stop if the model signaled end of turn
        if (strcmp(piece, "<end_of_turn>") == 0) {
            llama_batch b2 = llama_batch_get_one(&tok, 1);
            llama_decode(ctx, b2);
            llama_batch_free(b2);
            break;
        }
        out += piece;

        // decode this token
        llama_batch b2 = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b2) != 0) {
            LOGE("Decode token failed");
            llama_batch_free(b2);
            break;
        }
        llama_batch_free(b2);
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
    const llama_vocab* vocab = llama_model_get_vocab(model);

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
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch);

    // Prepare Java callback method
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
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
        // Stop if the model signaled end of turn
        if (strcmp(piece, "<end_of_turn>") == 0) {
            llama_batch b2 = llama_batch_get_one(&tok, 1);
            llama_decode(ctx, b2);
            llama_batch_free(b2);
            break;
        }
        jstring pieceJ = env->NewStringUTF(piece);
        jboolean cont = env->CallBooleanMethod(callback, onToken, pieceJ);
        env->DeleteLocalRef(pieceJ);
        if (cont == JNI_FALSE) {
            llama_batch b2 = llama_batch_get_one(&tok, 1);
            llama_decode(ctx, b2);
            llama_batch_free(b2);
            break;
        }

        llama_batch b2 = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b2) != 0) {
            LOGE("Decode token failed");
            llama_batch_free(b2);
            break;
        }
        llama_batch_free(b2);
    }
}

} // extern "C"
