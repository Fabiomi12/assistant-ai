package edu.upt.assistant.domain

import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.ConversationEntity
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.MessageEntity
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val convDao: ConversationDao,
    private val msgDao: MessageDao
) : ChatRepository {

    override fun getConversations(): Flow<List<Conversation>> =
        convDao.getAllConversations()
            .map { list -> list.map { it.toDomain() } }

    override fun getMessages(conversationId: String): Flow<List<Message>> =
        msgDao.getMessagesFor(conversationId)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun createConversation(conversation: Conversation) {
        convDao.upsert(conversation.toEntity())
    }

    override suspend fun sendMessage(conversationId: String, text: String) {
        val now = System.currentTimeMillis()
        // user message
        msgDao.insert(
            MessageEntity(
            conversationId = conversationId,
            text = text,
            isUser = true,
            timestamp = now
        )
        )
        // update conversation metadata
        convDao.upsert(
            ConversationEntity(
            id = conversationId,
            title = conversationId,      // or derive a nicer title
            lastMessage = text,
            timestamp = now
        )
        )
        // stubbed bot reply
        val botReply = "Got it!"
        msgDao.insert(MessageEntity(
            conversationId = conversationId,
            text = botReply,
            isUser = false,
            timestamp = System.currentTimeMillis()
        ))
        convDao.upsert(ConversationEntity(
            id = conversationId,
            title = conversationId,
            lastMessage = botReply,
            timestamp = System.currentTimeMillis()
        ))
    }

    // -- Helpers to convert between Entity ⇄ Domain --
    private fun ConversationEntity.toDomain() =
        Conversation(id, title, lastMessage, formatTimestamp(timestamp))

    private fun Conversation.toEntity() =
        ConversationEntity(id, title, lastMessage, parseTimestamp(timestamp))

    private fun MessageEntity.toDomain() =
        Message(text, isUser)

    // Format milliseconds into e.g. "Jul 19, 10:00 AM"
    private fun formatTimestamp(ms: Long): String {
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    // Parse a label like "Jul 19, 10:00 AM" back to millis, or use now on failure
    private fun parseTimestamp(label: String): Long {
        if (label.isBlank()) return System.currentTimeMillis()
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return try {
            sdf.parse(label)?.time ?: System.currentTimeMillis()
        } catch (e: ParseException) {
            System.currentTimeMillis()
        }
    }
}
