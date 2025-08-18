package edu.upt.assistant.domain.prompts

class LlamaPromptTemplate : PromptTemplate {
    override fun buildPrompt(
        systemPrompt: String,
        conversationHistory: List<ConversationMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()
        
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
        sb.append(systemPrompt)
        sb.append("\n<|eot_id|>")
        
        conversationHistory.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    sb.append("<|start_header_id|>user<|end_header_id|>\n")
                    sb.append(msg.content)
                    sb.append("\n<|eot_id|>")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
                    sb.append(msg.content)
                    sb.append("\n<|eot_id|>")
                }
            }
        }
        
        sb.append("<|start_header_id|>user<|end_header_id|>\n")
        sb.append(currentUserMessage)
        sb.append("\n<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n")
        
        return sb.toString()
    }
}