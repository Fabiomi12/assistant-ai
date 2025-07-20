package edu.upt.assistant.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.LlamaNative
import edu.upt.assistant.TokenCallback
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.ConversationEntity
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.MessageEntity
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val convDao: ConversationDao,
    private val msgDao: MessageDao,
    @ApplicationContext private val appContext: Context
) : ChatRepository {

    // Lazily initialize the native llama context
    private val llamaCtx: Long by lazy {
        val modelFile = File(appContext.filesDir, "models/gemma-3n-Q4_0.gguf")
        if (!modelFile.exists()) {
            appContext.assets.open("models/gemma-3n-Q4_0.gguf").use { input ->
                modelFile.parentFile?.mkdirs()
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        LlamaNative.llamaCreate(modelFile.absolutePath)
    }

    // Keep a ConversationManager per conversation
    private val managers = mutableMapOf<String, ConversationManager>()

    override fun getConversations(): Flow<List<Conversation>> =
        convDao.getAllConversations()
            .map { list -> list.map { it.toDomain() } }

    override fun getMessages(conversationId: String): Flow<List<Message>> =
        msgDao.getMessagesFor(conversationId)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun createConversation(conversation: Conversation) {
        convDao.upsert(conversation.toEntity())
    }

    /**
     * Streams tokens from the LLM. Saves user message immediately.
     * Once streaming completes, saves the full assistant message.
     */
    override fun sendMessage(conversationId: String, text: String): Flow<String> = channelFlow {
        val now = System.currentTimeMillis()
        // 1) persist user message
        msgDao.insert(
            MessageEntity(
                conversationId = conversationId,
                text = text,
                isUser = true,
                timestamp = now
            )
        )
        convDao.upsert(ConversationEntity(conversationId, conversationId, text, now))

        // 2) prepare prompt
        val manager = managers.getOrPut(conversationId) { ConversationManager() }
        manager.appendUser(text)
        val prompt = manager.buildPrompt()

        // 3) stream tokens
        val builder = StringBuilder()
        withContext(Dispatchers.Default) {
            LlamaNative.llamaGenerateStream(
                llamaCtx,
                prompt,
                /* maxTokens = */ 128,
                TokenCallback { token ->
                    trySend(token).isSuccess
                    builder.append(token)
                }
            )
        }

        // 4) after streaming, persist assistant message
        val reply = builder.toString().trim()
        manager.appendAssistant(reply)
        val replyTime = System.currentTimeMillis()
        msgDao.insert(
            MessageEntity(
                conversationId = conversationId,
                text = reply,
                isUser = false,
                timestamp = replyTime
            )
        )
        convDao.upsert(ConversationEntity(conversationId, conversationId, reply, replyTime))

        close() // finish the flow
        awaitClose { /* no-op */ }
    }

    override suspend fun deleteConversation(conversationId: String) {
        convDao.deleteById(conversationId)
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
