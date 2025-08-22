package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.domain.SettingsViewModel
import edu.upt.assistant.ui.navigation.HISTORY_ROUTE
import edu.upt.assistant.ui.navigation.MEMORY_ROUTE
import edu.upt.assistant.ui.navigation.NEW_CHAT_ROUTE
import edu.upt.assistant.ui.navigation.SETTINGS_ROUTE
import kotlinx.coroutines.flow.filter
import edu.upt.assistant.LlamaNative
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    conversationId: String,
    navController: NavHostController,
    vm: ChatViewModel,
    initialMessage: String? = null
) {
    // Collect the messages from the VM
    val messages by vm
        .messagesFor(conversationId)
        .collectAsState(initial = emptyList())

    // Track streaming state for this conversation
    val currentStreamingConversation by vm.currentStreamingConversation.collectAsState()
    val isStreaming = currentStreamingConversation == conversationId

    // Settings for auto-save
    val settingsVm: SettingsViewModel = hiltViewModel()
    val autoSaveMemories by settingsVm.autoSaveMemories.collectAsState()
    val memorySuggestion by vm.memorySuggestion.collectAsState()

    // Accumulate streamed tokens for the current response
    var streamingMessage by remember { mutableStateOf("") }

    // Collect tokens from the SharedFlow when streaming this conversation
    LaunchedEffect(conversationId, currentStreamingConversation) {
        vm.streamedTokens
            .filter { currentStreamingConversation == conversationId }
            .collect { token ->
                // Check if token is an error message
                if (token.startsWith("Error:")) {
                    streamingMessage = token
                } else {
                    // Accumulate tokens, replacing underscores with spaces
                    streamingMessage += token.replace("▁", " ")
                }
            }
    }

    // Clear streaming message when streaming stops
    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            streamingMessage = ""
        }
    }

    // Automatically save detected facts when enabled
    LaunchedEffect(memorySuggestion, autoSaveMemories) {
        if (autoSaveMemories && memorySuggestion != null) {
            vm.saveToMemory(memorySuggestion!!)
            vm.dismissMemorySuggestion()
        }
    }
    
    // Clear KV cache when conversation changes
    LaunchedEffect(conversationId) {
        vm.clearKvCacheForConversation(conversationId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.navigate(NEW_CHAT_ROUTE)
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(MEMORY_ROUTE) }) {
                        Icon(Icons.Default.Star, contentDescription = "Memory")
                    }
                    IconButton(onClick = { navController.navigate(HISTORY_ROUTE) }) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        ChatScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            messages = messages,
            streamingMessage = streamingMessage.trim(),
            isStreaming = isStreaming,
            onSend = { text ->
                if (initialMessage != null && text == initialMessage) {
                    // This is the initial message, already being processed
                    return@ChatScreen
                }
                vm.sendMessage(conversationId, text)
            },
            initialMessage = initialMessage,
            onSaveToMemory = { messageText ->
                vm.saveToMemory(messageText)
            },
            memorySuggestion = if (autoSaveMemories) null else memorySuggestion,
            onMemorySuggestionSave = { vm.saveToMemory(it); vm.dismissMemorySuggestion() },
            onMemorySuggestionDismiss = { vm.dismissMemorySuggestion() }
        )
    }
}