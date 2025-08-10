package edu.upt.assistant.domain

enum class Role { SYSTEM, USER, ASSISTANT }
data class Message(val role: Role, val content: String)

class ConversationManager(
    private val systemPrompt: String = "You are a helpful assistant.",
    private val maxTokens: Int = 2048
) {
    private val history = ArrayDeque<Message>()

    fun appendUser(text: String) {
        history += Message(Role.USER, text)
        trimIfNeeded()
    }

    fun appendAssistant(text: String) {
        history += Message(Role.ASSISTANT, text)
        trimIfNeeded()
    }

    fun buildPrompt(): String {
        val sb = StringBuilder()
            .append("<<SYSTEM>>\n")
            .append(systemPrompt).append("\n\n")
            .append("<<CONVERSATION>>\n")
        history.forEach { msg ->
            val prefix = when (msg.role) {
                Role.USER      -> "User: "
                Role.ASSISTANT -> "Assistant: "
                else           -> ""
            }
            sb.append(prefix).append(msg.content).append("\n")
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    private fun trimIfNeeded() {
        fun estimateTokens() = buildPrompt().length / 4
        while (history.size > 2 && estimateTokens() > maxTokens) {
            // drop oldest USER+ASSISTANT pair
            history.removeFirst()
            if (history.firstOrNull()?.role == Role.USER) {
                history.removeFirst()
            }
        }
    }
}
