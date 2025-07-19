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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
  private val repo: ChatRepository
) : ViewModel() {

  val username = "Fabio"

  val conversations: StateFlow<List<Conversation>> =
    repo.getConversations().stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      emptyList()
    )

  private val _currentId = MutableStateFlow<String?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val messages: StateFlow<List<Message>> = _currentId
    .filterNotNull()
    .flatMapLatest { repo.getMessages(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun startNewConversation(initialText: String) {
    val id = UUID.randomUUID().toString()
    viewModelScope.launch {
      repo.createConversation(Conversation(id, "Chat $id", "", ""))
      repo.sendMessage(id, initialText)
      _currentId.value = id
    }
  }

  fun selectConversation(id: String) {
    _currentId.value = id
  }

  fun sendMessage(text: String) {
    val convId = _currentId.value ?: return
    viewModelScope.launch {
      repo.sendMessage(convId, text)
    }
  }
}

