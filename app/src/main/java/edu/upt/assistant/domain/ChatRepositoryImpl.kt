package edu.upt.assistant.domain

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.LlamaNative
import edu.upt.assistant.TokenCallback
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.ConversationEntity
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.MessageEntity
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.Runtime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val convDao: ConversationDao,
    private val msgDao: MessageDao,
    private val modelDownloadManager: ModelDownloadManager,
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val appContext: Context
) : ChatRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var llamaCtxDeferred: Deferred<Long>? = null

    private suspend fun getModelUrl(): String {
        return dataStore.data.map { prefs -> prefs[SettingsKeys.SELECTED_MODEL] ?: ModelDownloadManager.DEFAULT_MODEL_URL }.first()
    }

    private fun initLlamaContext(): Deferred<Long> {
        return scope.async {
            Log.d("ChatRepository", "Initializing llama context")
            val url = getModelUrl()
            if (!modelDownloadManager.isModelAvailableUrl(url)) {
                Log.e("ChatRepository", "Model not available")
                throw IllegalStateException("Model not available. Please download the model first.")
            }

            val modelPath = modelDownloadManager.getModelPathUrl(url)
            Log.d("ChatRepository", "Model path: $modelPath")
            val ctx = LlamaNative.llamaCreate(
                modelPath,
                Runtime.getRuntime().availableProcessors()
            )
            if (ctx == 0L) {
                Log.e("ChatRepository", "Failed to create llama context")
                throw IllegalStateException("Failed to create llama context")
            }
            Log.d("ChatRepository", "Llama context created: $ctx")
            ctx
        }.also { llamaCtxDeferred = it }
    }

    // Lazily initialize the native llama context
    private suspend fun getLlamaContext(): Long {
        val deferred = llamaCtxDeferred ?: initLlamaContext()
        return deferred.await()
    }

    // Keep a ConversationManager per conversation
    private val managers = mutableMapOf<String, ConversationManager>()

    override fun getConversations(): Flow<List<Conversation>> {
        Log.d("ChatRepository", "Getting conversations")
        return convDao.getAllConversations()
            .map { list ->
                Log.d("ChatRepository", "Found ${list.size} conversations")
                list.map { it.toDomain() }
            }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        Log.d("ChatRepository", "Getting messages for conversation: $conversationId")
        return msgDao.getMessagesFor(conversationId)
            .map { list ->
                Log.d("ChatRepository", "Found ${list.size} messages for $conversationId")
                list.map { it.toDomain() }
            }
    }

    override suspend fun createConversation(conversation: Conversation) {
        Log.d("ChatRepository", "Creating conversation: ${conversation.id}")
        convDao.upsert(conversation.toEntity())
    }

    /**
     * Streams tokens from the LLM. Saves user message immediately.
     * Once streaming completes, saves the full assistant message.
     */
    override fun sendMessage(conversationId: String, text: String): Flow<String> = channelFlow {
        Log.d("ChatRepository", "Sending message to $conversationId: $text")

        val url = getModelUrl()
        if (!modelDownloadManager.isModelAvailableUrl(url)) {
            Log.e("ChatRepository", "Model not available when sending message")
            throw IllegalStateException("Model not available. Please download the model first.")
        }

        try {
            val ctx = getLlamaContext()
            Log.d("ChatRepository", "Got llama context: $ctx")

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
            Log.d("ChatRepository", "User message saved")

            // 2) prepare prompt
            val manager = managers.getOrPut(conversationId) { ConversationManager() }
            val prompt = manager.buildPrompt(text)
            manager.appendUser(text)
            Log.d("ChatRepository", "Prompt prepared: $prompt")

            // 3) stream tokens
            val builder = StringBuilder()
            withContext(Dispatchers.IO) {
                Log.d("ChatRepository", "Starting token generation")
                try {
                    LlamaNative.llamaGenerateStream(
                        ctx,
                        prompt,
                        /* maxTokens = */ 128,
                        TokenCallback { token ->
                            Log.d("ChatRepository", "Generated token: $token")

                            val normalized = normalizeToken(token)
                            val output = if (builder.isEmpty()) normalized.trimStart() else normalized

                            val success = trySend(output).isSuccess
                            if (success) {
                                builder.append(output)
                            }
                            // Keep the callback alive
                        }
                    )
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Error during streaming", e)
                    trySend("Error: Failed to generate response")
                }
            }

            LlamaNative.llamaKvCacheClear(ctx)

            // 4) after streaming, persist assistant message
            val reply = builder.toString()
            val cleanReply = reply.trim()
            Log.d("ChatRepository", "Complete reply: $cleanReply")
            manager.appendAssistant(cleanReply)
            val replyTime = System.currentTimeMillis()
            msgDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    text = cleanReply,
                    isUser = false,
                    timestamp = replyTime
                )
            )
            convDao.upsert(ConversationEntity(conversationId, conversationId, cleanReply, replyTime))
            Log.d("ChatRepository", "Assistant message saved")

        } catch (e: Exception) {
            Log.e("ChatRepository", "Error in sendMessage", e)
            throw e
        }

        close() // finish the flow
        awaitClose { /* no-op */ }
    }

    override suspend fun deleteConversation(conversationId: String) {
        Log.d("ChatRepository", "Deleting conversation: $conversationId")
        convDao.deleteById(conversationId)
    }

    override fun isModelReady(): Boolean {
        val url = runBlocking { getModelUrl() }
        val isReady = modelDownloadManager.isModelAvailableUrl(url)
        Log.d("ChatRepository", "Model ready check: $isReady")
        return isReady
    }

    fun getDownloadManager(): ModelDownloadManager = modelDownloadManager

    private fun normalizeToken(token: String): String {
        return token
            .replace("▁", " ")            // SentencePiece space
            .replace("Ġ", " ")            // BPE space
            .replace("Ċ", "\n")          // BPE newline
            .replace("Äł", " ")           // Qwen space artefact
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