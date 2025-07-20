package edu.upt.assistant.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * Create a conversation with the given ID *synchronously*,
     * then send the first message.
     */
    fun startNewConversation(conversationId: String, initialText: String) =
        viewModelScope.launch {
            repo.createConversation(
                Conversation(
                    id = conversationId,
                    title = "Chat at ${currentTimeLabel()}",
                    lastMessage = "",
                    timestamp = currentTimeLabel()
                )
            )
            repo.sendMessage(conversationId, initialText)
        }

    fun sendMessage(conversationId: String, text: String) {
        viewModelScope.launch { repo.sendMessage(conversationId, text) }
    }

    fun deleteConversation(conversationId: String) = viewModelScope.launch {
        repo.deleteConversation(conversationId)
    }

    // Helper to format timestamps
    private fun currentTimeLabel(): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
}

