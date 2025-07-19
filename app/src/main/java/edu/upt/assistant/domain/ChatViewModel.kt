package edu.upt.assistant.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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

  val username = "Fabio"

  // track it internally
  private val _currentId = MutableStateFlow<String?>(null)
  val currentConversationId: String
    get() = _currentId.value ?: throw IllegalStateException("No conversation yet")

  val conversations: StateFlow<List<Conversation>> =
    repo.getConversations().stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      emptyList()
    )

  @OptIn(ExperimentalCoroutinesApi::class)
  val messages: StateFlow<List<Message>> = _currentId
    .filterNotNull()
    .flatMapLatest { repo.getMessages(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Create a conversation with the given ID *synchronously*,
   * then send the first message.
   */
  fun startNewConversation(conversationId: String, initialText: String) {
    // 1️⃣ Tell the UI to start observing this convo immediately:
    _currentId.value = conversationId

    viewModelScope.launch {
      repo.createConversation(
        Conversation(conversationId, "Chat at ${currentTimeLabel()}", "", currentTimeLabel())
      )
      repo.sendMessage(conversationId, initialText)
    }
  }

  // Helper to format timestamps
  private fun currentTimeLabel(): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
      .format(Date(System.currentTimeMillis()))

  fun selectConversation(id: String) {
    _currentId.value = id
  }

  fun sendMessage(conversationId: String, text: String) {
    viewModelScope.launch { repo.sendMessage(conversationId, text) }
  }

  /**
   * Public helper to get the messages Flow for any conversation ID.
   */
  fun messagesFor(conversationId: String) =
    repo.getMessages(conversationId)

}

