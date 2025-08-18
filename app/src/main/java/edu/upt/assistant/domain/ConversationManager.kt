package edu.upt.assistant.domain

import edu.upt.assistant.domain.prompts.ConversationMessage
import edu.upt.assistant.domain.prompts.MessageRole
import edu.upt.assistant.domain.prompts.PromptTemplate
import edu.upt.assistant.domain.prompts.GenericPromptTemplate

enum class Role { SYSTEM, USER, ASSISTANT }
data class Message(val role: Role, val content: String)

class ConversationManager(
    private val systemPrompt: String = """You are a helpful, concise AI assistant. 
Respond naturally and directly to the user's messages. 
Keep responses focused and avoid generating lengthy or fictional conversations.
Only respond as the Assistant - do not continue the conversation or create additional exchanges.""",
    private val maxTokens: Int = 1536,  // Reduced to match n_ctx for better performance
    private val promptTemplate: PromptTemplate = GenericPromptTemplate()
) {
    private val history = ArrayDeque<Message>()

    fun appendUser(text: String) {
        history += Message(Role.USER, text)
        trimIfNeeded(text)
    }

    fun appendAssistant(text: String) {
        history += Message(Role.ASSISTANT, text)
        trimIfNeeded(text)
    }

    fun buildPrompt(text: String): String {
        val conversationMessages = history.map { msg ->
            ConversationMessage(
                role = when (msg.role) {
                    Role.USER -> MessageRole.USER
                    Role.ASSISTANT -> MessageRole.ASSISTANT
                    else -> MessageRole.USER // fallback
                },
                content = msg.content
            )
        }
        
        return promptTemplate.buildPrompt(
            systemPrompt = systemPrompt,
            conversationHistory = conversationMessages,
            currentUserMessage = text
        )
    }
    
    fun buildPromptWithHistory(conversationHistory: List<ConversationMessage>, currentUserMessage: String): String {
        return promptTemplate.buildPrompt(
            systemPrompt = systemPrompt,
            conversationHistory = conversationHistory,
            currentUserMessage = currentUserMessage
        )
    }
    
    fun getHistory(): List<Message> {
        return history.toList()
    }

    private fun trimIfNeeded(text: String) {
        fun estimateTokens() = buildPrompt(text).length / 4
        while (history.size > 2 && estimateTokens() > maxTokens) {
            // Remove oldest USER+ASSISTANT pair
            history.removeFirst()
            if (history.firstOrNull()?.role == Role.USER) {
                history.removeFirst()
            }
        }
    }
}