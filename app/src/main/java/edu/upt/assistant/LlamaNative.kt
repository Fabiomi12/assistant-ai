package edu.upt.assistant

interface StreamCallback {
  fun onToken(token: String)
  fun onTimings(prefillMs: Long, firstSampleDelayMs: Long)
}

object LlamaNative {
  init {
    try {
      System.loadLibrary("llama_jni")
    } catch (e: UnsatisfiedLinkError) {
      throw RuntimeException("Failed to load llama_jni library", e)
    }
  }

  @JvmStatic external fun llamaCreate(modelPath: String, nThreads: Int): Long
  @JvmStatic external fun llamaGenerate(ctxPtr: Long, prompt: String, maxTokens: Int): String
  @JvmStatic external fun llamaGenerateStream(
    ctxPtr: Long,
    prompt: String,
    maxTokens: Int,
    callback: StreamCallback
  )
  @JvmStatic external fun llamaKvCacheClear(ctxPtr: Long)
  @JvmStatic external fun llamaFree(ctxPtr: Long)
}