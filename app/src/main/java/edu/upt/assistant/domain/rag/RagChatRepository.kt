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
        private const val MAX_CONTEXT_LENGTH = 1500
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
            Log.d(TAG, "Original user message saved to database")
            
            // 2) Retrieve RAG context
            val context = try {
                retrieveContext(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving context", e)
                ""
            }
            
            // 3) Prepare enhanced prompt for LLM
            val manager = managers.getOrPut(conversationId) { 
                ConversationManager(currentSystemPrompt, promptTemplate = currentTemplate)
            }
            val enhancedText = if (context.isNotEmpty()) {
                Log.d(TAG, "Retrieved context for query: $text")
                Log.d(TAG, "Context length: ${context.length}")
                "$context\n\nUser Question: $text"
            } else {
                Log.d(TAG, "No context found, using original text")
                text
            }
            
            val prompt = manager.buildPrompt(enhancedText)
            manager.appendUser(text) // Add original text to conversation history
            Log.d(TAG, "Prompt prepared with ${if (context.isNotEmpty()) "RAG context" else "no context"}")
            Log.d(TAG, "===== FULL PROMPT SENT TO MODEL =====")
            Log.d(TAG, prompt)
            Log.d(TAG, "===== END OF PROMPT =====")
            
            // 4) Generate response using LLaMA
            val builder = StringBuilder()
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Starting token generation")
                try {
                    LlamaNative.llamaGenerateStream(
                        baseRepository.getLlamaContextPublic(),
                        prompt,
                        128, // maxTokens
                        TokenCallback { token ->
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
            val retrievedChunks = documentRepository.searchSimilarContent(query, topK = 3)
            
            if (retrievedChunks.isEmpty()) {
                Log.d(TAG, "No relevant context found for query: $query")
                return ""
            }
            
            val contextBuilder = StringBuilder()
            contextBuilder.appendLine("Context Information:")
            contextBuilder.appendLine("---")
            
            retrievedChunks.forEachIndexed { index, chunk ->
                contextBuilder.appendLine("${index + 1}. From '${chunk.documentTitle}' (similarity: ${String.format("%.3f", chunk.similarity)}):")
                contextBuilder.appendLine(chunk.text.trim())
                contextBuilder.appendLine()
            }
            
            contextBuilder.appendLine("---")
            contextBuilder.appendLine("Please use the above context to help answer the following question:")
            
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