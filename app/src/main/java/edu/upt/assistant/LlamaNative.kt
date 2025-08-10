package edu.upt.assistant

fun interface TokenCallback {
  fun onToken(token: String)
}

object LlamaNative {
  init { System.loadLibrary("llama_jni") }

  @JvmStatic external fun llamaCreate(modelPath: String): Long
  @JvmStatic external fun llamaGenerate(ctxPtr: Long, prompt: String, maxTokens: Int): String
  @JvmStatic external fun llamaGenerateStream(
    ctxPtr: Long,
    prompt: String,
    maxTokens: Int,
    callback: TokenCallback
  )
  @JvmStatic external fun llamaFree(ctxPtr: Long)
}
