package edu.upt.assistant.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.rag.DocumentRepository
import edu.upt.assistant.domain.rag.RagChatRepository
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.rag.RagDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "DocumentsViewModel"
    }
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Use stateIn to create a robust StateFlow from the repository
    val documents: StateFlow<List<RagDocument>> = documentRepository.getAllDocuments()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    init {
        Log.d(TAG, "DocumentsViewModel created")
        Log.d(TAG, "ChatRepository type: ${chatRepository::class.java.simpleName}")
        initialize()
    }
    
    private fun initialize() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Initializing DocumentsViewModel")
                documentRepository.initialize()
                Log.d(TAG, "DocumentRepository initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing document repository", e)
            }
        }
    }
    
    fun addDocument(title: String, content: String) {
        if (title.isBlank() || content.isBlank()) {
            Log.w(TAG, "Cannot add document with empty title or content")
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Log.d(TAG, "Adding document: $title")
                
                // Try to use RagChatRepository if available
                val documentId = if (chatRepository is RagChatRepository) {
                    Log.d(TAG, "Using RagChatRepository to add document")
                    chatRepository.addDocument(title.trim(), content.trim())
                } else {
                    Log.d(TAG, "Using DocumentRepository directly to add document")
                    documentRepository.addDocument(title.trim(), content.trim())
                }
                
                Log.d(TAG, "Document added successfully: $documentId")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding document", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Deleting document: $documentId")
                documentRepository.deleteDocument(documentId)
                Log.d(TAG, "Document deleted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting document", e)
            }
        }
    }
}