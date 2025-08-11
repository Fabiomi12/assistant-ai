package edu.upt.assistant

fun interface TokenCallback {
  /**
   * @return true to continue streaming, false to stop generation
   */
  fun onToken(token: String): Boolean
}

object LlamaNative {
  init { System.loadLibrary("llama_jni") }

  @JvmStatic external fun llamaCreate(modelPath: String, nThreads: Int): Long
  @JvmStatic external fun llamaGenerate(ctxPtr: Long, prompt: String, maxTokens: Int): String
  @JvmStatic external fun llamaGenerateStream(
    ctxPtr: Long,
    prompt: String,
    maxTokens: Int,
    callback: TokenCallback
  )
  @JvmStatic external fun llamaFree(ctxPtr: Long)
}
