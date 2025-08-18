package edu.upt.assistant.domain.prompts

interface PromptTemplate {
    fun buildPrompt(
        systemPrompt: String,
        conversationHistory: List<ConversationMessage>,
        currentUserMessage: String
    ): String
}

data class ConversationMessage(
    val role: MessageRole,
    val content: String
)

enum class MessageRole {
    USER, ASSISTANT
}