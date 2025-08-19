// GemmaPromptTemplate.kt
package edu.upt.assistant.domain.prompts

class GemmaPromptTemplate : PromptTemplate {
    override fun buildPrompt(
        systemPrompt: String,
        conversationHistory: List<ConversationMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()

        // System turn
        sb.append("<start_of_turn>system\n")
            .append(systemPrompt.trim())
            .append("\n<end_of_turn>\n")

        // (Optional) tiny bit of history for style/continuity; keep short for speed
        conversationHistory.takeLast(2).forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    sb.append("<start_of_turn>user\n")
                        .append(msg.content.trim())
                        .append("\n<end_of_turn>\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n")
                        .append(msg.content.trim())
                        .append("\n<end_of_turn>\n")
                }
            }
        }

        // Current user turn (you’ll pass in the memory/ctx + question as one string)
        sb.append("<start_of_turn>user\n")
            .append(currentUserMessage.trim())
            .append("\n<end_of_turn>\n")

        // Generate here
        sb.append("<start_of_turn>model\n")

        return sb.toString()
    }
}
