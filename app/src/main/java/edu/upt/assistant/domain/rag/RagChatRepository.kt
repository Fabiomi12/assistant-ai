package edu.upt.assistant.domain.rag

import android.util.Log
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.ChatRepositoryImpl
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import edu.upt.assistant.data.local.db.MessageEntity
import edu.upt.assistant.data.local.db.ConversationEntity
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.LlamaNative
import edu.upt.assistant.TokenCallback
import edu.upt.assistant.domain.ConversationManager
import edu.upt.assistant.domain.prompts.PromptTemplateFactory
import javax.inject.Inject
import javax.inject.Singleton
import android.os.Process

@Singleton
class RagChatRepository @Inject constructor(
    private val baseRepository: ChatRepositoryImpl,
    private val documentRepository: DocumentRepository,
    private val msgDao: MessageDao,
    private val convDao: ConversationDao
) : ChatRepository {
    
    // Keep a ConversationManager per conversation (similar to base repository)
    private val managers = mutableMapOf<String, ConversationManager>()
    
    // Cache current template and system prompt for RAG mode
    private var currentTemplate = PromptTemplateFactory.getTemplateForModel("")
    private var currentSystemPrompt = PromptTemplateFactory.getSystemPromptForRAG()
    
    init {
        // Initialize template when repository is created
        updateTemplateFromModel()
    }
    
    private fun updateTemplateFromModel() {
        try {
            val modelUrl = runBlocking { baseRepository.getModelUrl() }
            currentTemplate = PromptTemplateFactory.getTemplateForModel(modelUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get model URL, using default template", e)
            currentTemplate = PromptTemplateFactory.getTemplateForModel("")
        }
    }
    
    companion object {
        private const val TAG = "RagChatRepository"
        private const val MAX_CONTEXT_LENGTH = 150 // Further reduced for much faster prefill
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
    
    override fun sendMessage(conversationId: String, text: String): Flow<String> = channelFlow {
        Log.d(TAG, "RAG sendMessage called with: $text")
        
        if (!baseRepository.isModelReady()) {
            Log.e(TAG, "Model not available when sending message")
            throw IllegalStateException("Model not available. Please download the model first.")
        }
        
        try {
            // 1) Save the original user message to database (clean version)
            Log.d(TAG, "TIMING: Starting message processing at ${System.currentTimeMillis()}")
            val now = System.currentTimeMillis()
            msgDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    text = text, // Save original message without RAG context
                    isUser = true,
                    timestamp = now
                )
            )
            convDao.upsert(ConversationEntity(conversationId, conversationId, text, now))
            Log.d(TAG, "TIMING: Database save completed at ${System.currentTimeMillis()}")
            
            // 2) Retrieve RAG context
            Log.d(TAG, "TIMING: Starting RAG context retrieval at ${System.currentTimeMillis()}")
            val context = try {
                retrieveContext(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving context", e)
                ""
            }
            Log.d(TAG, "TIMING: RAG context retrieval completed at ${System.currentTimeMillis()}")
            
            // 3) Prepare enhanced prompt for LLM
            val manager = managers.getOrPut(conversationId) { 
                ConversationManager(currentSystemPrompt, promptTemplate = currentTemplate)
            }
            
            // Build conversation history (without current message) - limit to last 1 exchange for performance
//            val conversationHistory = manager.getHistory().takeLast(1).map { msg ->
//                edu.upt.assistant.domain.prompts.ConversationMessage(
//                    role = when (msg.role) {
//                        edu.upt.assistant.domain.Role.USER -> edu.upt.assistant.domain.prompts.MessageRole.USER
//                        edu.upt.assistant.domain.Role.ASSISTANT -> edu.upt.assistant.domain.prompts.MessageRole.ASSISTANT
//                        else -> edu.upt.assistant.domain.prompts.MessageRole.USER
//                    },
//                    content = msg.content
//                )
//            }

            // Build conversation history: keep it EMPTY for speed/clarity in RAG mode
            val conversationHistory = emptyList<edu.upt.assistant.domain.prompts.ConversationMessage>()


            // Prepare current message with RAG context if available
            val currentMessage = buildString {
                if (context.isNotBlank()) {
                    Log.d(TAG, "Retrieved context for query: $text (len=${context.length})")
                    appendLine("CONTEXT")
                    appendLine(context.trim())
                    appendLine("---")
                } else {
                    Log.d(TAG, "No context found, using original text")
                }
                append(text.trim())
            }


            // Build prompt with history and enhanced current message
            Log.d(TAG, "TIMING: Starting prompt building at ${System.currentTimeMillis()}")
            val promptStartTime = System.currentTimeMillis()
            val prompt = manager.buildPromptWithHistory(conversationHistory, currentMessage)
            val promptEndTime = System.currentTimeMillis()
            
            // Add the ORIGINAL user text to conversation history (not the enhanced version)
            manager.appendUser(text)
            
            val promptTokens = prompt.length / 4  // Rough estimate
            Log.d(TAG, "PERFORMANCE: Prompt building took ${promptEndTime - promptStartTime}ms")
            Log.d(TAG, "PERFORMANCE: Prompt tokens: $promptTokens, n_ctx: 1536, n_batch: 256, n_ubatch: 64")
            Log.d(TAG, "Prompt prepared with ${if (context.isNotEmpty()) "RAG context" else "no context"}")
            Log.d(TAG, "PROMPT LENGTH: ${prompt.length} characters, estimated ~$promptTokens tokens")
            Log.d(TAG, "===== FULL PROMPT SENT TO MODEL =====")
            Log.d(TAG, prompt)
            Log.d(TAG, "===== END OF PROMPT =====")
            
            // 4) Generate response using LLaMA
            Log.d(TAG, "TIMING: Starting LLaMA generation at ${System.currentTimeMillis()}")
            val llamaStartTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            val builder = StringBuilder()
            withContext(Dispatchers.IO) {
                Log.d(TAG, "TIMING: Inside IO context at ${System.currentTimeMillis()}")
                Log.d(TAG, "Starting token generation")
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    LlamaNative.llamaGenerateStream(
                        baseRepository.getLlamaContextPublic(),
                        prompt,
                        32, // Further reduced for much faster first token
                        TokenCallback { token ->
                            // Capture first token timing
                            if (firstTokenTime == null) {
                                firstTokenTime = System.currentTimeMillis()
                                val prefillTime = firstTokenTime!! - llamaStartTime
                                Log.d(TAG, "🚀 PERFORMANCE: First token after ${prefillTime}ms (prefill time)")
                            }
                            
                            Log.d(TAG, "Generated token: $token")
                            val normalized = baseRepository.normalizeTokenPublic(token)
                            val output = if (builder.isEmpty()) normalized.trimStart() else normalized
                            
                            val success = trySend(output).isSuccess
                            if (success) {
                                builder.append(output)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error during streaming", e)
                    trySend("Error: Failed to generate response")
                }
            }
            
            // Clear KV cache
            LlamaNative.llamaKvCacheClear(baseRepository.getLlamaContextPublic())
            
            // 5) Save assistant response
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
            Log.d(TAG, "Assistant response saved")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in RAG sendMessage", e)
            trySend("Error: ${e.message ?: "Unable to generate response"}")
        }
        
        close()
        awaitClose { /* no-op */ }
    }
    
    override suspend fun deleteConversation(conversationId: String) {
        baseRepository.deleteConversation(conversationId)
    }
    
    override fun isModelReady(): Boolean {
        return baseRepository.isModelReady()
    }
    
    private suspend fun retrieveContext(query: String): String {
        return try {
            val retrievedChunks = documentRepository.searchSimilarContent(query, topK = 1) // Reduced from 3 to 1
            
            if (retrievedChunks.isEmpty()) {
                Log.d(TAG, "No relevant context found for query: $query")
                return ""
            }
            
            val contextBuilder = StringBuilder()
            contextBuilder.appendLine("Context:")
            
            retrievedChunks.forEach { chunk ->
                contextBuilder.appendLine(chunk.text.trim())
            }
            
            contextBuilder.appendLine("---")
            
            val context = contextBuilder.toString()
            
            // Truncate if too long
            return if (context.length > MAX_CONTEXT_LENGTH) {
                context.substring(0, MAX_CONTEXT_LENGTH) + "...\n[Context truncated]\n---\n"
            } else {
                context
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving context", e)
            ""
        }
    }
    
    // Additional RAG-specific methods
    suspend fun addDocument(title: String, content: String, contentType: String = "text/plain"): String {
        return documentRepository.addDocument(title, content, contentType)
    }
    
    suspend fun deleteDocument(documentId: String) {
        documentRepository.deleteDocument(documentId)
    }
    
    fun getAllDocuments(): Flow<List<RagDocument>> {
        return documentRepository.getAllDocuments()
    }
    
    suspend fun getDocumentCount(): Int {
        return documentRepository.getDocumentCount()
    }
    
    // Delegate to base repository for model management
    fun getDownloadManager() = baseRepository.getDownloadManager()
}