package edu.upt.assistant.domain.prompts

object PromptTemplateFactory {

    fun getTemplateForModel(modelUrl: String): PromptTemplate {
        val filename = extractFilename(modelUrl).lowercase()

        return when {
            // Qwen 1.x / 2.x – ChatML
            filename.contains("qwen") -> QwenPromptTemplate()

            // Gemma / Gemma2 – <start_of_turn>/<end_of_turn>
            filename.contains("gemma") -> GemmaPromptTemplate()

            // Llama-3 family – Llama 3 chat headers
            filename.contains("llama-3") 
                || filename.contains("llama3") 
                || filename.contains("meta-llama") -> LlamaPromptTemplate()

            // Legacy Llama support
            filename.contains("llama") || filename.contains("meta") -> LlamaPromptTemplate()

            else -> GenericPromptTemplate()
        }
    }
    
    private fun extractFilename(url: String): String {
        return url.substringAfterLast("/").substringAfterLast("\\")
    }

    // New: hybrid system prompt (not “RAG-only”)
    fun getSystemPromptForHybrid(): String = """
You are a concise assistant.Use PERSONAL MEMORY / CONTEXT only for user-specific facts; otherwise answer from your own knowledge. Be concise.
""".trimIndent()

    // keep your other prompts if you still use them elsewhere
    fun getSystemPromptForRAG(): String = """You are a concise assistant. Use ONLY the provided context to answer.
If the context does not contain the answer, reply: "I don't know based on the provided context."
Answer in 1–2 sentences unless the user asks for more. Do not invent details."""

    fun getSystemPromptForNormal(): String = """
You are a helpful, concise AI assistant.
Respond naturally and directly to the user's messages.
Keep responses focused and avoid generating lengthy or fictional conversations.
Only respond as the Assistant - do not continue the conversation or create additional exchanges.
""".trimIndent()
}