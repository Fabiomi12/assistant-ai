package edu.upt.assistant.domain

import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
  fun getConversations(): Flow<List<Conversation>>
  fun getMessages(conversationId: String): Flow<List<Message>>
  suspend fun createConversation(conversation: Conversation)
  suspend fun sendMessage(conversationId: String, text: String)
  suspend fun deleteConversation(conversationId: String)
}
