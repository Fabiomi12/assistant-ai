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
- Be concise
- Use PERSONAL MEMORY/CONTEXT only for user-specific facts
- Otherwise answer from your own knowledge
""".trimIndent()

    // keep your other prompts if you still use them elsewhere
    fun getSystemPromptForRAG(): String = """You are a concise assistant. Use ONLY the provided context to answer.
If the context does not contain the answer, reply: "I don't know based on the provided context."
Answer in 1–2 sentences unless the user asks for more. Do not invent details."""

    fun getSystemPromptForNormal(): String = """
- Be helpful and concise
- Reply only as the assistant
- Avoid long or fictional conversations
""".trimIndent()
}