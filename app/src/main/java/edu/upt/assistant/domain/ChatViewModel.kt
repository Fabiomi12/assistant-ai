package edu.upt.assistant.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    val conversations: StateFlow<List<Conversation>> =
        repo.getConversations().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    fun messagesFor(conversationId: String): Flow<List<Message>> =
        repo.getMessages(conversationId)

    // this SharedFlow will emit each token as it arrives
    private val _streamedTokens = MutableSharedFlow<String>(replay = 0)
    val streamedTokens: SharedFlow<String> = _streamedTokens.asSharedFlow()

    /**
     * Creates a new conversation and streams the LLM response tokens.
     * Returns a Flow<String> of tokens that UI can collect and use onCompletion.
     */
    fun startNewConversation(
        conversationId: String,
        initialText: String
    ): Flow<String> = flow {
        // 1) persist conversation metadata
        val timestamp = currentTimeLabel()
        repo.createConversation(
            Conversation(
                id = conversationId,
                title = "Chat at $timestamp",
                lastMessage = "",
                timestamp = timestamp
            )
        )
        // 2) emit tokens from LLM stream
        emitAll(
            repo.sendMessage(conversationId, initialText)
                .onEach { token -> _streamedTokens.emit(token) }
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Sends a message to an existing conversation and streams tokens.
     */
    fun sendMessage(
        conversationId: String,
        text: String
    ): Flow<String> =
        repo.sendMessage(conversationId, text)
            .onEach { token -> _streamedTokens.emit(token) }
            .flowOn(Dispatchers.Default)

    fun deleteConversation(conversationId: String) = viewModelScope.launch {
        repo.deleteConversation(conversationId)
    }

    // Helper to format timestamps
    private fun currentTimeLabel(): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
}

