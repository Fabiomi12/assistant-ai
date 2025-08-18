package edu.upt.assistant.ui.screens


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.domain.rag.DocumentRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

// UI state holding onboarding inputs
data class SetupUiState(
    val username: String = "",
    val documentsAdded: Int = 0,
    val isAddingDocument: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val documentRepository: DocumentRepository
) : ViewModel() {
    var uiState by mutableStateOf(SetupUiState())
        private set

    fun onUsernameChange(name: String) {
        uiState = uiState.copy(username = name)
    }
    
    fun onDocumentAdded() {
        uiState = uiState.copy(documentsAdded = uiState.documentsAdded + 1)
    }
    
    fun setAddingDocument(isAdding: Boolean) {
        uiState = uiState.copy(isAddingDocument = isAdding)
    }
    
    fun addDocument(title: String, content: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            setAddingDocument(true)
            try {
                documentRepository.addDocument(title, content)
                onDocumentAdded()
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to add document")
            } finally {
                setAddingDocument(false)
            }
        }
    }

    fun persist(onComplete: () -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.USERNAME] = uiState.username
                prefs[SettingsKeys.SETUP_DONE] = true
            }
            onComplete()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupStep1(
    username: String,
    onUsernameChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Welcome to your AI Assistant!",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Text(
                "Let's start by setting up your name and adding some documents to help me assist you better.",
                style = MaterialTheme.typography.bodyLarge
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Your Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onNext,
                enabled = username.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Next")
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupStep2(
    documentsAdded: Int,
    isAddingDocument: Boolean,
    onAddDocument: (String, String) -> Unit,
    onFinish: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var documentTitle by remember { mutableStateOf("") }
    var documentContent by remember { mutableStateOf("") }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Add Your Documents",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Text(
                "Add documents, notes, or any text that you'd like me to reference when helping you. This enables RAG (Retrieval-Augmented Generation) for more personalized responses.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Documents Added: $documentsAdded",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            enabled = !isAddingDocument
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                            Text("Add Document")
                        }
                    }
                    
                    if (documentsAdded > 0) {
                        Text(
                            "Great! You've added $documentsAdded document${if (documentsAdded != 1) "s" else ""}. You can always add more later in the Documents section.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = onFinish,
                enabled = true,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Finish Setup")
            }
        }
    }
    
    // Add document dialog
    if (showAddDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { 
            showAddDialog = false 
            documentTitle = ""
            documentContent = ""
        }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Add Document", style = MaterialTheme.typography.titleLarge)
                    
                    OutlinedTextField(
                        value = documentTitle,
                        onValueChange = { documentTitle = it },
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = documentContent,
                        onValueChange = { documentContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { 
                                showAddDialog = false
                                documentTitle = ""
                                documentContent = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = {
                                onAddDocument(documentTitle, documentContent)
                                showAddDialog = false
                                documentTitle = ""
                                documentContent = ""
                            },
                            enabled = documentTitle.isNotBlank() && documentContent.isNotBlank() && !isAddingDocument,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SetupRoute(
    onFinish: () -> Unit
) {
    val vm: SetupViewModel = hiltViewModel()
    val state = vm.uiState
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Drive the flow with a simple step counter:
    var step by remember { mutableIntStateOf(1) }

    when (step) {
        1 -> SetupStep1(
            username = state.username,
            onUsernameChange = vm::onUsernameChange,
            onNext = { step = 2 }
        )

        2 -> SetupStep2(
            documentsAdded = state.documentsAdded,
            isAddingDocument = state.isAddingDocument,
            onAddDocument = { title, content ->
                vm.addDocument(
                    title = title,
                    content = content,
                    onSuccess = { /* Document added successfully */ },
                    onError = { error -> errorMessage = error }
                )
            },
            onFinish = { vm.persist { onFinish() } }
        )
    }
    
    // Show error message if any
    errorMessage?.let { error ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { errorMessage = null }) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Error", style = MaterialTheme.typography.titleLarge)
                    Text(error)
                    Button(
                        onClick = { errorMessage = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}


