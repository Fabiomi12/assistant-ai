package edu.upt.assistant.domain

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.LlamaNative
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import edu.upt.assistant.domain.rag.RagChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: ChatRepository,
    /**
     * The conditional repository used for chat operations does not expose
     * memory APIs. Inject the [RagChatRepository] directly so memory features
     * (viewing/adding/deleting memories) work regardless of whether RAG is
     * enabled for chatting.
     */
    private val ragRepo: RagChatRepository
) : ViewModel() {

    init {
        Log.d("ChatViewModel", "ChatViewModel created")
        Log.d("ChatViewModel", "Repository type: ${repo::class.java.simpleName}")
    }

    val conversations: StateFlow<List<Conversation>> =
        repo.getConversations().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    // Model readiness state
    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    // This SharedFlow will emit each token as it arrives
    private val _streamedTokens = MutableSharedFlow<String>(replay = 0)
    val streamedTokens: SharedFlow<String> = _streamedTokens.asSharedFlow()
    
    // Memory suggestion state
    private val _memorySuggestion = MutableStateFlow<String?>(null)
    val memorySuggestion: StateFlow<String?> = _memorySuggestion.asStateFlow()

    // Track current conversation being streamed
    private val _currentStreamingConversation = MutableStateFlow<String?>(null)
    val currentStreamingConversation: StateFlow<String?> = _currentStreamingConversation.asStateFlow()

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            try {
                val isReady = (repo as? ChatRepositoryImpl)?.isModelReady() ?: false
                Log.d("ChatViewModel", "Model ready status: $isReady")
                _isModelReady.value = isReady
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error checking model status", e)
                _isModelReady.value = false
            }
        }
    }

    fun messagesFor(conversationId: String): Flow<List<Message>> {
        Log.d("ChatViewModel", "Getting messages for conversation: $conversationId")
        return repo.getMessages(conversationId)
    }

    /**
     * Creates a new conversation and starts streaming.
     * UI should collect from streamedTokens SharedFlow.
     */
    fun startNewConversation(conversationId: String, initialText: String) {
        Log.d("ChatViewModel", "Starting new conversation: $conversationId with message: $initialText")
        _currentStreamingConversation.value = conversationId

        viewModelScope.launch {
            try {
                // Clear KV cache for new conversation
                clearKvCacheIfPossible()
                
                // 1) Create conversation metadata
                val timestamp = currentTimeLabel()
                repo.createConversation(
                    Conversation(
                        id = conversationId,
                        title = "Chat at $timestamp",
                        lastMessage = "",
                        timestamp = timestamp
                    )
                )
                Log.d("ChatViewModel", "Conversation created, starting message stream")

                // 2) Start streaming and emit tokens to SharedFlow
                repo.sendMessage(conversationId, initialText)
                    .flowOn(Dispatchers.Default)
                    .onEach { token ->
                        Log.d("ChatViewModel", "Token received: $token")
                        _streamedTokens.emit(token)
                    }
                    .collect { /* All tokens are emitted via onEach */ }

                Log.d("ChatViewModel", "Conversation streaming completed")

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error starting new conversation", e)
                _streamedTokens.emit("Error: ${e.message ?: "Unable to generate response"}")
            } finally {
                _currentStreamingConversation.value = null
            }
        }
    }

    /**
     * Sends a message to an existing conversation and starts streaming.
     * UI should collect from streamedTokens SharedFlow.
     */
    fun sendMessage(conversationId: String, text: String) {
        Log.d("ChatViewModel", "Sending message to $conversationId: $text")
        _currentStreamingConversation.value = conversationId

        // Check if this looks like a personal statement worth saving
        checkForMemorySuggestion(text)

        viewModelScope.launch {
            try {
                repo.sendMessage(conversationId, text)
                    .flowOn(Dispatchers.Default)
                    .onEach { token ->
                        Log.d("ChatViewModel", "Token received: $token")
                        _streamedTokens.emit(token)
                    }
                    .collect { /* All tokens are emitted via onEach */ }

                Log.d("ChatViewModel", "Message streaming completed")

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
                _streamedTokens.emit("Error: ${e.message ?: "Unable to generate response"}")
            } finally {
                _currentStreamingConversation.value = null
            }
        }
    }

    fun deleteConversation(conversationId: String) = viewModelScope.launch {
        Log.d("ChatViewModel", "Deleting conversation: $conversationId")
        repo.deleteConversation(conversationId)
    }

    /**
     * Clears the KV cache when switching conversations or starting new ones
     */
    fun clearKvCacheForConversation(conversationId: String) {
        Log.d("ChatViewModel", "Clearing KV cache for conversation: $conversationId")
        clearKvCacheIfPossible()
    }
    
    private fun clearKvCacheIfPossible() {
        viewModelScope.launch {
            try {
                val repoImpl = repo as? ChatRepositoryImpl
                if (repoImpl != null) {
                    val ctx = repoImpl.getLlamaContextPublic()
                    LlamaNative.llamaKvCacheClear(ctx)
                    Log.d("ChatViewModel", "KV cache cleared successfully")
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to clear KV cache: ${e.message}")
            }
        }
    }

    // Memory-related methods
    private fun checkForMemorySuggestion(text: String) {
        val lowerText = text.lowercase()
        
        // Check for personal statements that might be worth saving as memory
        val personalPatterns = listOf(
            "my hobbies?\\s+(?:are?|include)",
            "i (?:like|enjoy|love|prefer)",
            "my favorite",
            "i usually",
            "i'm (?:into|interested in)",
            "i live in",
            "my name is",
            "i study",
            "i work",
            "i'm a"
        )
        
        val hasPersonalStatement = personalPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(lowerText)
        }
        
        if (hasPersonalStatement && text.length > 10) {
            _memorySuggestion.value = text
            Log.d("ChatViewModel", "Personal statement detected, suggesting memory save: $text")
        }
    }
    
    fun saveToMemory(content: String) {
        viewModelScope.launch {
            try {
                val memoryId = ragRepo.addMemoryFromMessage(content)
                Log.d("ChatViewModel", "Saved memory with ID: $memoryId")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error saving memory", e)
            }
        }
    }
    
    fun dismissMemorySuggestion() {
        _memorySuggestion.value = null
    }
    
    fun getMemories(): Flow<List<edu.upt.assistant.data.local.db.MemoryEntity>> =
        ragRepo.getAllMemories()
    
    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            try {
                ragRepo.deleteMemory(memoryId)
                Log.d("ChatViewModel", "Deleted memory: $memoryId")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error deleting memory", e)
            }
        }
    }
    
    fun addMemory(content: String, title: String?, importance: Int) {
        viewModelScope.launch {
            try {
                val keywords = edu.upt.assistant.domain.memory.KeywordExtractor.extract(content)
                val memoryId = ragRepo.addMemory(
                    content,
                    title,
                    listOf("personal"),
                    keywords,
                    importance
                )
                Log.d("ChatViewModel", "Added memory with ID: $memoryId")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error adding memory", e)
            }
        }
    }

    // Helper to format timestamps
    private fun currentTimeLabel(): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
}