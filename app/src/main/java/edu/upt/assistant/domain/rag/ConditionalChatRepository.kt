package edu.upt.assistant.domain.rag

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.ChatRepositoryImpl
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConditionalChatRepository @Inject constructor(
    private val baseRepository: ChatRepositoryImpl,
    private val ragRepository: RagChatRepository,
    private val dataStore: DataStore<Preferences>
) : ChatRepository {

    private suspend fun getCurrentRepository(): ChatRepository {
        val isRagEnabled = dataStore.data.map { prefs -> 
            prefs[SettingsKeys.RAG_ENABLED] ?: true 
        }.first()
        
        return if (isRagEnabled) ragRepository else baseRepository
    }

    override fun getConversations(): Flow<List<Conversation>> {
        return baseRepository.getConversations()
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return baseRepository.getMessages(conversationId)
    }

    override suspend fun createConversation(conversation: Conversation) {
        baseRepository.createConversation(conversation)
    }

    override fun sendMessage(conversationId: String, text: String): Flow<String> {
        return kotlinx.coroutines.flow.flow {
            val currentRepo = getCurrentRepository()
            currentRepo.sendMessage(conversationId, text).collect { emit(it) }
        }
    }

    override suspend fun deleteConversation(conversationId: String) {
        baseRepository.deleteConversation(conversationId)
    }

    override fun isModelReady(): Boolean {
        return baseRepository.isModelReady()
    }
}