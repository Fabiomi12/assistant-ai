package edu.upt.assistant.domain.prompts

object PromptTemplateFactory {

    fun getTemplateForModel(modelUrl: String): PromptTemplate {
        val filename = extractFilename(modelUrl).lowercase()

        return when {
            filename.contains("qwen") -> QwenPromptTemplate()
            filename.contains("llama") || filename.contains("meta") -> LlamaPromptTemplate()
            else -> GenericPromptTemplate()
        }
    }
    
    private fun extractFilename(url: String): String {
        return url.substringAfterLast("/").substringAfterLast("\\")
    }
    
    fun getSystemPromptForRAG(): String = """You are a concise assistant. Use ONLY the provided context to answer.
If the context does not contain the answer, reply: "I don't know based on the provided context."
Answer in 1–2 sentences. Do not invent details."""

    fun getSystemPromptForNormal(): String = """You are a helpful, concise AI assistant. 
Respond naturally and directly to the user's messages. 
Keep responses focused and avoid generating lengthy or fictional conversations.
Only respond as the Assistant - do not continue the conversation or create additional exchanges."""
}