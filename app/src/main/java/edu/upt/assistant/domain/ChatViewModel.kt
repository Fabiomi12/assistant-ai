package edu.upt.assistant.domain

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
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
    private val repo: ChatRepository
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

    // Helper to format timestamps
    private fun currentTimeLabel(): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
}