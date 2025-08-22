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
import edu.upt.assistant.data.metrics.GenerationMetrics
import edu.upt.assistant.data.metrics.MetricsLogger
import edu.upt.assistant.domain.prompts.PromptTemplateFactory
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private val modelDownloadManager: ModelDownloadManager,
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val appContext: Context
) : ChatRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var llamaCtxDeferred: Deferred<Long>? = null
    private var threadCount: Int = 0
    private var modelName: String = ""

    init {
        observeModelChanges()
    }

    companion object {
        private const val N_BATCH = 256
        private const val N_UBATCH = 64
        private val LONG_RESPONSE_REGEX = Regex("6\\s*(?:-|\u2013)\\s*7\\s+sentences", RegexOption.IGNORE_CASE)
    }
    suspend fun getModelUrl(): String {
        return dataStore.data.map { prefs -> prefs[SettingsKeys.SELECTED_MODEL] ?: ModelDownloadManager.DEFAULT_MODEL_URL }.first()
    }

    private fun initLlamaContext(): Deferred<Long> {
        return scope.async {
            Log.d("ChatRepository", "Initializing llama context")
            val url = getModelUrl()
            if (!modelDownloadManager.isModelAvailable(url)) {
                Log.e("ChatRepository", "Model not available")
                throw IllegalStateException("Model not available. Please download the model first.")
            }

            val modelPath = modelDownloadManager.getModelPath(url)
            Log.d("ChatRepository", "Model path: $modelPath")
            // Use optimized thread count (6-8) instead of all processors for better performance
            val optimalThreads = minOf(8, maxOf(6, Runtime.getRuntime().availableProcessors() / 2))
            threadCount = optimalThreads
            modelName = java.io.File(modelPath).name
            val ctx = LlamaNative.llamaCreate(
                modelPath,
                threadCount
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

    private fun observeModelChanges() {
        scope.launch {
            dataStore.data
                .map { prefs -> prefs[SettingsKeys.SELECTED_MODEL] ?: ModelDownloadManager.DEFAULT_MODEL_URL }
                .distinctUntilChanged()
                .collect { newModelUrl ->
                    Log.d("ChatRepository", "Model changed to $newModelUrl, resetting llama context")
                    destroyLlamaContext()
                    // Update template for new model
                    currentTemplate = PromptTemplateFactory.getTemplateForModel(newModelUrl)
                    // Clear managers so they get recreated with new template
                    managers.clear()
                }
        }
    }

    private fun destroyLlamaContext() {
        llamaCtxDeferred?.let {
            if (it.isCompleted) {
                val ctx = runBlocking { it.await() }
                if (ctx != 0L) {
                    try {
                        LlamaNative.llamaFree(ctx)
                        Log.d("ChatRepository", "Destroyed old llama context: $ctx")
                    } catch (e: Exception) {
                        Log.e("ChatRepository", "Failed to destroy llama context", e)
                    }
                }
            }
            // Cancel the deferred if still active
            it.cancel()
        }
        llamaCtxDeferred = null
    }

    // Keep a ConversationManager per conversation
    private val managers = mutableMapOf<String, ConversationManager>()
    
    // Cache current template based on model
    private var currentTemplate = PromptTemplateFactory.getTemplateForModel(ModelDownloadManager.DEFAULT_MODEL_URL)
    private var currentSystemPrompt = PromptTemplateFactory.getSystemPromptForNormal()

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

        val expectsLongAnswer = LONG_RESPONSE_REGEX.containsMatchIn(text)

        val url = getModelUrl()
        if (!modelDownloadManager.isModelAvailable(url)) {
            Log.e("ChatRepository", "Model not available when sending message")
            throw IllegalStateException("Model not available. Please download the model first.")
        }

        try {
            val ctx = getLlamaContext()
            Log.d("ChatRepository", "Got llama context: $ctx")

            val startBattery = MetricsLogger.batteryLevel(appContext)
            val startTemp = MetricsLogger.deviceTemperature(appContext)
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
            val manager = managers.getOrPut(conversationId) { 
                ConversationManager(currentSystemPrompt, promptTemplate = currentTemplate)
            }
            val prompt = manager.buildPrompt(text)
            manager.appendUser(text)
            Log.d("ChatRepository", "Prompt prepared: $prompt")

            // 3) stream tokens
            val llamaStartTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            var tokenCount = 0
            val promptTokens = prompt.length / 4
            Log.d("ChatRepository", "PERFORMANCE: Prompt tokens: $promptTokens, n_ctx: 1536, n_batch: $N_BATCH, n_ubatch: $N_UBATCH")

            val builder = StringBuilder()
            var generationFailed = false
            withContext(Dispatchers.IO) {
                Log.d("ChatRepository", "Starting token generation at ${System.currentTimeMillis()}")
                try {
                    LlamaNative.llamaGenerateStream(
                        ctx,
                        prompt,
                        /* maxTokens = */ 48,  // Reduced for faster first token
                        TokenCallback { token ->
                            // Capture first token timing
                            if (firstTokenTime == null) {
                                firstTokenTime = System.currentTimeMillis()
                                val prefillTime = firstTokenTime!! - llamaStartTime
                                Log.d("ChatRepository", "🚀 PERFORMANCE: First token after ${prefillTime}ms (prefill time)")
                            }

                            Log.d("ChatRepository", "Generated token: $token")
                            tokenCount++

                            val normalized = normalizeToken(token)
                            val output = if (builder.isEmpty()) normalized.trimStart() else normalized

                            val success = trySend(output).isSuccess
                            if (success) {
                                builder.append(output)
                            }
                            // Keep the callback alive
                        }
                    )
                } catch (e: Throwable) {
                    generationFailed = true
                    Log.e("ChatRepository", "Error during streaming", e)
                    val msg = if (e.message?.contains("llama_decode") == true || e is OutOfMemoryError) {
                        "Sorry, I ran out of memory. Could you try again with a shorter context?"
                    } else {
                        "Error: Failed to generate response"
                    }
                    builder.clear()
                    builder.append(msg)
                    trySend(msg)
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

            if (!generationFailed && expectsLongAnswer && tokenCount < 20) {
                val followUp = "Want me to continue?"
                val followUpTime = System.currentTimeMillis()
                msgDao.insert(
                    MessageEntity(
                        conversationId = conversationId,
                        text = followUp,
                        isUser = false,
                        timestamp = followUpTime
                    )
                )
                convDao.upsert(ConversationEntity(conversationId, conversationId, followUp, followUpTime))
                trySend("\n" + followUp)
            }

            val endBattery = MetricsLogger.batteryLevel(appContext)
            val endTemp = MetricsLogger.deviceTemperature(appContext)
            val prefillTime = (firstTokenTime ?: replyTime) - llamaStartTime
            val decodeTimeMs = replyTime - (firstTokenTime ?: replyTime)
            val decodeSpeed = if (decodeTimeMs > 0) tokenCount / (decodeTimeMs / 1000.0) else 0.0
            val metrics = GenerationMetrics(
                timestamp = replyTime,
                prefillTimeMs = prefillTime,
                firstTokenDelayMs = prefillTime,
                decodeSpeed = decodeSpeed,
                batteryDelta = startBattery - endBattery,
                startTempC = startTemp,
                endTempC = endTemp,
                promptChars = prompt.length,
                promptTokens = promptTokens,
                nThreads = threadCount,
                nBatch = N_BATCH,
                nUbatch = N_UBATCH,
                modelName = modelName,
                modelQuant = modelName
            )
            MetricsLogger.log(appContext, metrics)

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
        val isReady = modelDownloadManager.isModelAvailable(url)
        Log.d("ChatRepository", "Model ready check: $isReady")
        return isReady
    }

    fun getDownloadManager(): ModelDownloadManager = modelDownloadManager
    
    // Public methods for RAG repository access
    suspend fun getLlamaContextPublic(): Long = getLlamaContext()
    
    fun normalizeTokenPublic(token: String): String = normalizeToken(token)

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