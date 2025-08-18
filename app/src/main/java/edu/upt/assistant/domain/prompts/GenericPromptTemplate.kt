package edu.upt.assistant.domain.prompts

class GenericPromptTemplate : PromptTemplate {
    override fun buildPrompt(
        systemPrompt: String,
        conversationHistory: List<ConversationMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()
        
        sb.append(systemPrompt).append("\n\n")
        
        conversationHistory.forEach { msg ->
            val prefix = when (msg.role) {
                MessageRole.USER -> "User: "
                MessageRole.ASSISTANT -> "Assistant: "
            }
            sb.append(prefix).append(msg.content).append("\n")
        }
        
        sb.append("User: ").append(currentUserMessage).append("\n")
        sb.append("Assistant:")
        
        return sb.toString()
    }
}