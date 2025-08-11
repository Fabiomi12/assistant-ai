package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.ui.navigation.HISTORY_ROUTE
import edu.upt.assistant.ui.navigation.NEW_CHAT_ROUTE
import edu.upt.assistant.ui.navigation.SETTINGS_ROUTE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    conversationId: String,
    navController: NavHostController,
    vm: ChatViewModel = hiltViewModel(),
    initialMessage: String? = null
) {
    // 1️⃣ collect the cold Flow from the VM
    val messages by vm
        .messagesFor(conversationId)
        .collectAsState(initial = emptyList())

    var streamingReply by remember { mutableStateOf("") }
    val currentStreaming by vm.currentStreamingConversation.collectAsState()

    LaunchedEffect(currentStreaming) {
        if (currentStreaming == conversationId) {
            streamingReply = ""
            vm.streamedTokens.collect { token ->
                streamingReply += token
            }
        } else {
            streamingReply = ""
        }
    }

    val displayedMessages = if (streamingReply.isNotEmpty()) {
        messages + Message(streamingReply, isUser = false)
    } else {
        messages
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
                .padding(paddingValues),      // ← apply Scaffold insets
            messages = displayedMessages,
            onSend = { vm.sendMessage(conversationId, it) },
            initialMessage = initialMessage
        )
    }
}