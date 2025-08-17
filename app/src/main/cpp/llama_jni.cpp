
#include "llama.h"
#include <android/log.h>
#include <cstring>
#include <jni.h>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Helper to detect response completion
bool should_stop_generation(const std::string& accumulated_text, llama_token token, const llama_vocab* vocab) {
    const char* piece = llama_vocab_get_text(vocab, token);
    if (!piece) return false;

    std::string token_text(piece);

    // Stop on EOS token
    if (llama_vocab_is_eog(vocab, token)) {
        return true;
    }

    // Stop if we encounter "User:" which indicates model is hallucinating dialogue
    if (accumulated_text.find("User:") != std::string::npos) {
        LOGI("Stopping generation - detected 'User:' in response");
        return true;
    }

    // Stop if response seems to be creating a new assistant turn
    if (accumulated_text.find("Assistant:") != std::string::npos && accumulated_text.length() > 20) {
        LOGI("Stopping generation - detected additional 'Assistant:' in response");
        return true;
    }

    // Stop on excessive newlines (might indicate dialogue generation)
    int newline_count = 0;
    for (char c : accumulated_text) {
        if (c == '\n') newline_count++;
    }
    if (newline_count > 3) {
        LOGI("Stopping generation - too many newlines detected");
        return true;
    }

    return false;
}

// -----------------------------
// JNI: Initialize and free
// -----------------------------
JNIEXPORT jlong JNICALL Java_edu_upt_assistant_LlamaNative_llamaCreate(
        JNIEnv *env, jclass, jstring modelPathJ, jint nThreads) {
    const char *path = env->GetStringUTFChars(modelPathJ, nullptr);
    if (!path) {
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to get model path");
        return 0;
    }

    // Load model
    llama_model_params mparams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPathJ, path);

    if (!model) {
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to load model");
        return 0;
    }

    // Create context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;

    int threads = nThreads > 0
                  ? nThreads
                  : static_cast<int>(std::thread::hardware_concurrency());
    if (threads <= 0) {
        threads = 1;
    }
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    LOGI("Using %d threads", threads);

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        jclass ioe = env->FindClass("java/io/IOException");
        env->ThrowNew(ioe, "Failed to init context");
        return 0;
    }

    LOGI("Context initialized successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaFree(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (ctx) {
        const llama_model *model = llama_get_model(ctx);
        llama_free(ctx);
        if (model) {
            llama_model_free(const_cast<llama_model *>(model));
        }
        LOGI("Context and model freed");
    }
}

JNIEXPORT void JNICALL
Java_edu_upt_assistant_LlamaNative_llamaKvCacheClear(JNIEnv *, jclass,
                                                     jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (ctx) {
        llama_kv_self_clear(ctx);
        LOGI("KV cache cleared");
    }
}

// -----------------------------
// JNI: Synchronous Generation
// -----------------------------
JNIEXPORT jstring JNICALL Java_edu_upt_assistant_LlamaNative_llamaGenerate(
        JNIEnv *env, jclass, jlong ctxPtr, jstring promptJ, jint maxTokens) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (!ctx) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "Invalid context");
        return nullptr;
    }

    // Get prompt text
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

    const int32_t vocab_size = llama_vocab_n_tokens(vocab);

    // Tokenize prompt
    std::vector<llama_token> tokens(4096);
    int32_t ntok =
            llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)),
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           true, // add_special
                           false // parse_special
            );

    env->ReleaseStringUTFChars(promptJ, prompt);

    if (ntok < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }

    tokens.resize(ntok);

    // Create batch and decode initial prompt
    llama_batch batch = llama_batch_init(512, 0, 1);

    // Fill batch with prompt tokens
    batch.n_tokens = ntok;
    for (int i = 0; i < ntok; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] =
                (i == ntok - 1) ? 1 : 0; // Only need logits for last token
    }

    if (llama_decode(ctx, batch) != 0) {
        LOGE("Initial decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Prepare output
    std::string output;
    output.reserve(maxTokens * 4);
    int n_cur = ntok;

    // Configure sampler for better quality
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Generation loop with better stopping conditions
    for (int i = 0; i < maxTokens; ++i) {
        // Sample next token
        llama_token next_token = llama_sampler_sample(sampler, ctx, -1);

        // Check for EOS or stopping conditions
        if (should_stop_generation(output, next_token, vocab)) {
            break;
        }

        // Get token text
        const char *piece = llama_vocab_get_text(vocab, next_token);
        if (!piece) break;

        std::string token_text(piece);
        // Replace common space tokens
        if (token_text == "▁") {
            token_text = " ";
        } else if (token_text.length() >= 3 && token_text.substr(0, 3) == "▁") {
            token_text = " " + token_text.substr(3);
        }

        output += token_text;

        // Prepare batch for next token
        batch.n_tokens = 1;
        batch.token[0] = next_token;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode token failed");
            break;
        }

        n_cur++;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    // Clean up output
    std::string cleaned_output = output;
    // Remove any trailing dialogue artifacts
    size_t user_pos = cleaned_output.find("User:");
    if (user_pos != std::string::npos) {
        cleaned_output = cleaned_output.substr(0, user_pos);
    }
    size_t assistant_pos = cleaned_output.find("Assistant:");
    if (assistant_pos != std::string::npos && assistant_pos > 10) {
        cleaned_output = cleaned_output.substr(0, assistant_pos);
    }

    // Trim whitespace
    while (!cleaned_output.empty() && std::isspace(cleaned_output.back())) {
        cleaned_output.pop_back();
    }

    return env->NewStringUTF(cleaned_output.c_str());
}

// -----------------------------
// JNI: Streaming Generation
// -----------------------------
JNIEXPORT void JNICALL Java_edu_upt_assistant_LlamaNative_llamaGenerateStream(
        JNIEnv *env, jclass, jlong ctxPtr, jstring promptJ, jint maxTokens,
        jobject callback) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (!ctx || !callback) {
        LOGE("Invalid context or callback");
        return;
    }

    const char *prompt = env->GetStringUTFChars(promptJ, nullptr);
    if (!prompt) {
        LOGE("Failed to get prompt string");
        return;
    }

    const llama_model *model = llama_get_model(ctx);
    if (!model) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        LOGE("Model not initialized");
        return;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (!vocab) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        LOGE("Vocab not initialized");
        return;
    }

    // Tokenize prompt
    std::vector<llama_token> tokens(4096);
    int32_t ntok =
            llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)),
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           true, // add_special
                           false // parse_special
            );

    env->ReleaseStringUTFChars(promptJ, prompt);

    if (ntok < 0) {
        LOGE("Tokenization failed");
        return;
    }

    tokens.resize(ntok);

    // Initialize batch with proper size
    llama_batch batch = llama_batch_init(512, 0, 1);

    // Fill batch with prompt tokens
    batch.n_tokens = ntok;
    for (int i = 0; i < ntok; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] =
                (i == ntok - 1) ? 1 : 0; // Only need logits for last token
    }

    if (llama_decode(ctx, batch) != 0) {
        LOGE("Initial decode failed");
        llama_batch_free(batch);
        return;
    }

    // Prepare Java callback method
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken =
            env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("Failed to find onToken method");
        llama_batch_free(batch);
        return;
    }

    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    int n_cur = ntok;

    // Configure sampler with better parameters for focused responses
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Track accumulated output for stopping conditions
    std::string accumulated_output;

    // Stream tokens as generated with better stopping
    for (int i = 0; i < maxTokens; ++i) {
        // Sample next token
        llama_token next_token = llama_sampler_sample(sampler, ctx, -1);

        // Check stopping conditions early
        if (should_stop_generation(accumulated_output, next_token, vocab)) {
            LOGI("Stopping generation due to completion criteria");
            break;
        }

        // Get token text
        const char *piece = llama_vocab_get_text(vocab, next_token);
        if (!piece) {
            LOGE("Failed to get token text");
            break;
        }

        std::string token_text(piece);
        // Normalize token text
        if (token_text == "▁") {
            token_text = " ";
        } else if (token_text.length() >= 3 && token_text.substr(0, 3) == "▁") {
            token_text = " " + token_text.substr(3);
        }

        accumulated_output += token_text;

        // Send token to Java callback
        jstring pieceJ = env->NewStringUTF(token_text.c_str());
        if (pieceJ) {
            env->CallVoidMethod(callback, onToken, pieceJ);
            env->DeleteLocalRef(pieceJ);

            // Check for Java exceptions
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOGE("Java exception in callback");
                break;
            }
        }

        // Prepare batch for next token
        batch.n_tokens = 1;
        batch.token[0] = next_token;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode token failed");
            break;
        }

        n_cur++;
    }

    // Clean up sampler and batch
    llama_sampler_free(sampler);
    llama_batch_free(batch);
    LOGI("Streaming generation completed");
}

} // extern "C"