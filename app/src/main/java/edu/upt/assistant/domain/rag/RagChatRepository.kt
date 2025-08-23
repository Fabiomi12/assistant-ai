package edu.upt.assistant.domain.rag

import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.LlamaNative
import edu.upt.assistant.StreamCallback
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.ConversationEntity
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.MessageEntity
import edu.upt.assistant.data.metrics.GenerationMetrics
import edu.upt.assistant.data.metrics.MetricsLogger
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.ChatRepositoryImpl
import edu.upt.assistant.domain.ConversationManager
import edu.upt.assistant.domain.memory.KeywordExtractor
import edu.upt.assistant.domain.memory.MemoryRepository
import edu.upt.assistant.domain.prompts.ConversationMessage
import edu.upt.assistant.domain.prompts.MessageRole
import edu.upt.assistant.domain.prompts.PromptTemplateFactory
import edu.upt.assistant.domain.utils.ModelUtils.fileNameFrom
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// NEW: DataStore imports
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import edu.upt.assistant.data.SettingsKeys

@Singleton
class RagChatRepository @Inject constructor(
    private val baseRepository: ChatRepositoryImpl,
    private val documentRepository: DocumentRepository,
    private val memoryRepository: MemoryRepository,
    private val msgDao: MessageDao,
    private val convDao: ConversationDao,
    @ApplicationContext private val appContext: Context,
    private val dataStore: DataStore<Preferences>
) : ChatRepository {

    companion object {
        private const val TAG = "RagChatRepository"
        private const val MAX_CTX_TOKENS = 220
        private const val MAX_MEM_TOKENS = 80
        private const val N_BATCH = 256
        private const val N_UBATCH = 64

        private fun estTokens(s: String): Int = (s.length / 4).coerceAtLeast(1)

        private fun extractQuant(modelUrl: String): String {
            val file = modelUrl.substringAfterLast('/').substringAfterLast('\\')
            val q = Regex("-(Q[^-.]+)").find(file)?.groupValues?.getOrNull(1)
            return q ?: file
        }

        /** Trim to a safe sentence boundary (., ?, !, or newline). If none, return whole. */
        private fun trimToSentenceBoundary(s: String): String {
            val idx = s.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            return if (idx >= 0 && idx < s.length - 1) s.substring(0, idx + 1) else s
        }
    }

    private val managers = mutableMapOf<String, ConversationManager>()

    // pick at runtime (Hybrid for Gemma, Normal for others)
    private var currentTemplate = PromptTemplateFactory.getTemplateForModel("")
    private var currentSystemPrompt = PromptTemplateFactory.getSystemPromptForNormal()

    init {
        updateTemplateFromModel()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                addTestMemories()
                Log.d(TAG, "Memory system ready with ${memoryRepository.getMemoryCount()} memories")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing memory system", e)
            }
        }
    }

    private fun isGemma(modelUrl: String): Boolean {
        val name = modelUrl.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return "gemma" in name
    }

    private fun updateTemplateFromModel() {
        try {
            val modelUrl = runBlocking { baseRepository.getModelUrl() }
            currentTemplate = PromptTemplateFactory.getTemplateForModel(modelUrl)
            currentSystemPrompt = if (isGemma(modelUrl)) {
                Log.d(TAG, "Using HYBRID system prompt (Gemma detected)")
                PromptTemplateFactory.getSystemPromptForHybrid()
            } else {
                Log.d(TAG, "Using NORMAL system prompt")
                PromptTemplateFactory.getSystemPromptForNormal()
            }
            Log.d(TAG, "Updated template for model: $modelUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get model URL, falling back to NORMAL prompt", e)
            currentTemplate = PromptTemplateFactory.getTemplateForModel("")
            currentSystemPrompt = PromptTemplateFactory.getSystemPromptForNormal()
        }
    }

    override fun getConversations(): Flow<List<Conversation>> = baseRepository.getConversations()
    override fun getMessages(conversationId: String): Flow<List<Message>> = baseRepository.getMessages(conversationId)
    override suspend fun createConversation(conversation: Conversation) = baseRepository.createConversation(conversation)
    override suspend fun deleteConversation(conversationId: String) = baseRepository.deleteConversation(conversationId)
    override fun isModelReady(): Boolean = baseRepository.isModelReady()

    override fun sendMessage(conversationId: String, text: String): Flow<String> = channelFlow {
        Log.d(TAG, "RAG sendMessage called with: $text")

        if (!baseRepository.isModelReady()) {
            Log.e(TAG, "Model not available when sending message")
            throw IllegalStateException("Model not available. Please download the model first.")
        }

        try {
            // --- Read runtime config from DataStore (NEW)
            val prefs = dataStore.data.first()
            val ragEnabled   = prefs[SettingsKeys.RAG_ENABLED]    ?: true
            val memoryEnabled= prefs[SettingsKeys.MEMORY_ENABLED] ?: true
            val maxTokensCfg = prefs[SettingsKeys.MAX_TOKENS]     ?: guessMaxTokens(text)
            val modelUrlPref = runBlocking { baseRepository.getModelUrl() }
            val nThreadsCfg  = prefs[SettingsKeys.nThreadsForModel(modelUrlPref)]
                ?: prefs[SettingsKeys.N_THREADS]
                ?: minOf(8, maxOf(6, Runtime.getRuntime().availableProcessors() / 2))

            // persist user message
            val now = System.currentTimeMillis()
            msgDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    text = text,
                    isUser = true,
                    timestamp = now
                )
            )
            convDao.upsert(ConversationEntity(conversationId, conversationId, text, now))

            // fetch memory + optional doc ctx
            Log.d(TAG, "TIMING: Starting memory/doc retrieval at ${System.currentTimeMillis()}")
            val docTopK = 4
            val memoryHits = try {
                if (memoryEnabled) memoryRepository.search(text, topK = 3) else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving memory", e); emptyList()
            }
            val docCtx = try {
                if (ragEnabled) retrieveContext(text) else ""
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving context", e); ""
            }
            Log.d(TAG, "TIMING: Memory/doc retrieval completed at ${System.currentTimeMillis()}")
            Log.d(TAG, "Memory hits: ${memoryHits.size}, Doc ctx: ${if (docCtx.isNotEmpty()) "yes" else "no"}")

            // refresh prompt template & system prompt (model may have changed)
            updateTemplateFromModel()

            // Tiny history continuity (last 2 messages)
            val prevHistoryMsgs = managers[conversationId]?.getHistory()?.takeLast(2).orEmpty()
            val prevHistory = prevHistoryMsgs.map { msg ->
                ConversationMessage(
                    role = when (msg.role) {
                        edu.upt.assistant.domain.Role.USER -> MessageRole.USER
                        edu.upt.assistant.domain.Role.ASSISTANT -> MessageRole.ASSISTANT
                        else -> MessageRole.USER
                    },
                    content = msg.content
                )
            }
            val historyTokens = prevHistory.sumOf { it.content.length / 4 }
            val retrievedCtxTokens = if (docCtx.isNotBlank()) docCtx.length / 4 else 0

            val manager = ConversationManager(currentSystemPrompt, promptTemplate = currentTemplate)
            prevHistory.forEach {
                when (it.role) {
                    MessageRole.USER -> manager.appendUser(it.content)
                    MessageRole.ASSISTANT -> manager.appendAssistant(it.content)
                }
            }
            managers[conversationId] = manager

            // Build current message (PERSONAL MEMORY + optional CONTEXT + question)
            val currentMessage = buildString {
                if (memoryEnabled && memoryHits.isNotEmpty()) {
                    appendLine("PERSONAL MEMORY")
                    var memBudget = MAX_MEM_TOKENS
                    for (m in memoryHits) {
                        val line = "- " + trimToSentenceBoundary(m.content.trim())
                        val tks = estTokens(line)
                        if (tks > memBudget) break
                        appendLine(line)
                        memBudget -= tks
                    }
                    appendLine("---")
                }
                if (ragEnabled && docCtx.isNotBlank()) {
                    appendLine("CONTEXT")
                    appendLine(docCtx) // retrieveContext() returns raw text only
                    appendLine("---")
                }
                appendLine("Question:")
                append(text.trim())
            }

            // Build final prompt
            val tPromptStart = System.currentTimeMillis()
            val prompt = manager.buildPromptWithHistory(prevHistory, currentMessage)
            val tPromptEnd = System.currentTimeMillis()
            val promptTokens = prompt.length / 4
            Log.d(TAG, "PERFORMANCE: Prompt build ${tPromptEnd - tPromptStart}ms, ~${promptTokens} tok (history ${historyTokens} ctx ${retrievedCtxTokens})")
            Log.d(TAG, "===== FULL PROMPT SENT TO MODEL =====")
            Log.d(TAG, prompt)
            Log.d(TAG, "===== END OF PROMPT =====")

            // --- Metrics pre state
            val startBattery = MetricsLogger.batteryLevel(appContext)
            val startTempC   = MetricsLogger.deviceTemperature(appContext)
            val modelUrl     = runBlocking { baseRepository.getModelUrl() }

            val llamaStart = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            var nativePrefill: Long = 0
            var nativeFirstSample: Long = 0
            var pieceCount = 0 // streamed pieces (approx)
            val builder = StringBuilder()

            withContext(Dispatchers.IO) {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    LlamaNative.llamaGenerateStream(
                        baseRepository.getLlamaContextPublic(),
                        prompt,
                        maxTokensCfg,
                        object : StreamCallback {
                            override fun onTimings(prefillMs: Long, firstSampleDelayMs: Long) {
                                nativePrefill = prefillMs
                                nativeFirstSample = firstSampleDelayMs
                            }
                            override fun onToken(tokenPiece: String) {
                                if (firstTokenTime == null) {
                                    firstTokenTime = System.currentTimeMillis()
                                    Log.d(TAG, "PERFORMANCE: First token after ${firstTokenTime!! - llamaStart}ms")
                                }
                                if (tokenPiece.contains("<end_of_turn>") || tokenPiece.contains("<|im_end|>")) {
                                    return
                                }
                                pieceCount++

                                val normalized = baseRepository.normalizeTokenPublic(tokenPiece)
                                val out = if (builder.isEmpty()) normalized.trimStart() else normalized
                                if (trySend(out).isSuccess) builder.append(out)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error during streaming", e)
                    trySend("Error: Failed to generate response")
                }
            }

            val reply = builder.toString().trim()
            manager.appendUser(text)
            manager.appendAssistant(reply)

            // --- Metrics post
            val replyTime  = System.currentTimeMillis()
            val endBattery = MetricsLogger.batteryLevel(appContext)
            val endTempC   = MetricsLogger.deviceTemperature(appContext)

            val prefillMs       = if (nativePrefill > 0) nativePrefill else (firstTokenTime ?: replyTime) - llamaStart
            val firstSample     = nativeFirstSample
            val firstTokenTotal = prefillMs + firstSample
            val decodeMs        = replyTime - (firstTokenTime ?: replyTime)

            val outputTokApprox = estTokens(builder.toString()).coerceAtLeast(pieceCount)
            val decodeSpeed     = if (decodeMs > 0) outputTokApprox / (decodeMs / 1000.0) else 0.0

            try {
                MetricsLogger.log(
                    appContext,
                    GenerationMetrics(
                        timestamp         = replyTime,
                        prefillTimeMs     = prefillMs,
                        firstSampleDelayMs = firstSample,
                        firstTokenTimeMs  = firstTokenTotal,
                        decodeTimeMs      = decodeMs,
                        decodeSpeed       = decodeSpeed,
                        batteryDelta      = startBattery - endBattery,
                        startTempC        = startTempC,
                        endTempC          = endTempC,
                        promptChars       = prompt.length,
                        promptTokens      = promptTokens,
                        historyTokens     = historyTokens,
                        retrievedCtxTokens= retrievedCtxTokens,
                        outputTokens      = outputTokApprox,
                        promptId          = conversationId,
                        category          = "",
                        ragEnabled        = ragEnabled,
                        memoryEnabled     = memoryEnabled,
                        topK              = if (ragEnabled) docTopK else 0,
                        maxTokens         = maxTokensCfg,
                        nThreads          = nThreadsCfg,
                        nBatch            = N_BATCH,
                        nUbatch           = N_UBATCH,
                        model             = fileNameFrom(modelUrl)
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to write metrics: ${t.message}")
            }

            // persist assistant reply
            msgDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    text = reply,
                    isUser = false,
                    timestamp = replyTime
                )
            )
            convDao.upsert(ConversationEntity(conversationId, conversationId, reply, replyTime))
            Log.d(TAG, "Assistant response saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error in RAG sendMessage", e)
            trySend("Error: ${e.message ?: "Unable to generate response"}")
        }

        close()
        awaitClose { /* no-op */ }
    }

    private suspend fun retrieveContext(query: String): String {
        return try {
            val retrieved = documentRepository.searchSimilarContent(query, topK = 4)
            if (retrieved.isEmpty()) {
                Log.d(TAG, "No relevant context found for query: $query")
                return ""
            }
            var budget = MAX_CTX_TOKENS
            val sb = StringBuilder()
            for (chunk in retrieved) {
                val text = chunk.text.trim()
                val tks = estTokens(text)
                if (tks > budget) {
                    val trimmed = trimToSentenceBoundary(text.take(budget * 4))
                    if (trimmed.isNotBlank() && estTokens(trimmed) <= budget) {
                        sb.appendLine(trimmed)
                    }
                    break
                } else {
                    sb.appendLine(text)
                    budget -= tks
                }
            }
            sb.toString().trim() // raw context only
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving context", e)
            ""
        }
    }

    // ===== Memory management passthroughs =====

    suspend fun addDocument(title: String, content: String, contentType: String = "text/plain"): String =
        documentRepository.addDocument(title, content, contentType)

    suspend fun addMemory(
        content: String,
        title: String? = null,
        tags: List<String> = listOf("personal"),
        keywords: List<String> = emptyList(),
        importance: Int = 3
    ): String = memoryRepository.addMemory(content, title, tags, keywords, importance)

    fun getAllMemories(): Flow<List<edu.upt.assistant.data.local.db.MemoryEntity>> =
        memoryRepository.getAllMemories()

    suspend fun deleteMemory(id: String) = memoryRepository.deleteMemory(id)

    suspend fun addMemoryFromMessage(messageText: String, importance: Int = 3): String {
        val keywords = KeywordExtractor.extract(messageText)
        return memoryRepository.addMemory(
            content = messageText,
            keywords = keywords,
            importance = importance
        )
    }

    private suspend fun addTestMemories() {
        try {
            val existing = memoryRepository.getMemoryCount()
            if (existing == 0) {
                Log.d(TAG, "Adding test memories for demo")
                memoryRepository.addMemory(
                    content = "My hobbies are playing video games and reading webnovels",
                    title = "Personal Hobbies",
                    tags = listOf("personal", "hobbies"),
                    keywords = listOf("video games", "webnovels", "reading", "gaming"),
                    importance = 4
                )
                memoryRepository.addMemory(
                    content = "I prefer Android development over iOS development",
                    title = "Development Preferences",
                    tags = listOf("personal", "preferences", "programming"),
                    keywords = listOf("android", "development", "programming"),
                    importance = 3
                )
                memoryRepository.addMemory(
                    content = "I usually code in Kotlin and sometimes Java",
                    title = "Programming Languages",
                    tags = listOf("personal", "programming"),
                    keywords = listOf("kotlin", "java", "programming"),
                    importance = 3
                )
                Log.d(TAG, "Test memories added")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding test memories", e)
        }
    }

    private fun guessMaxTokens(userText: String): Int {
        val m = Regex("""(\d+)\s*-\s*(\d+)\s*sentences""", RegexOption.IGNORE_CASE).find(userText)
        val single = Regex("""(\d+)\s*sentences?""", RegexOption.IGNORE_CASE).find(userText)
        return when {
            m != null -> {
                val (a, b) = m.destructured
                ((a.toInt() + b.toInt()) / 2 * 20).coerceIn(64, 256)
            }
            single != null -> {
                val n = single.groupValues[1].toInt()
                (n * 20).coerceIn(64, 256)
            }
            else -> 96 // default
        }
    }
}
