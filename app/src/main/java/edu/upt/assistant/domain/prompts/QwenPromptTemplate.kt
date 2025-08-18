package edu.upt.assistant.domain.prompts

class QwenPromptTemplate : PromptTemplate {
    override fun buildPrompt(
        systemPrompt: String,
        conversationHistory: List<ConversationMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()
        
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        sb.append("\n<|im_end|>\n")
        
        conversationHistory.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    sb.append("<|im_start|>user\n")
                    sb.append(msg.content)
                    sb.append("\n<|im_end|>\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("<|im_start|>assistant\n")
                    sb.append(msg.content)
                    sb.append("\n<|im_end|>\n")
                }
            }
        }
        
        sb.append("<|im_start|>user\n")
        sb.append(currentUserMessage)
        sb.append("\n<|im_end|>\n<|im_start|>assistant\n")
        
        return sb.toString()
    }
}