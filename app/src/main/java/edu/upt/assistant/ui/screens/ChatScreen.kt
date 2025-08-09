package edu.upt.assistant.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import edu.upt.assistant.domain.ChatViewModel

// Simple data model for a chat message
data class Message(val text: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    initialMessage: String? = null,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    Log.d("ChatScreen", "ChatScreen created for conversation: $conversationId, initial: $initialMessage")

    // 1) Collect persisted messages from database
    val messages by viewModel.messagesFor(conversationId)
        .collectAsState(initial = emptyList())

    // 2) Collect streamed tokens from SharedFlow
    val streamedTokens by viewModel.streamedTokens.collectAsState(initial = "")

    // 3) Track which conversation is currently streaming
    val currentStreamingConversation by viewModel.currentStreamingConversation
        .collectAsState(initial = null)

    // 4) Local state for partial reply and input
    var partialReply by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var hasProcessedInitialMessage by remember { mutableStateOf(false) }

    // 5) Collect streamed tokens and accumulate them
    LaunchedEffect(streamedTokens, currentStreamingConversation) {
        if (currentStreamingConversation == conversationId && streamedTokens.isNotBlank()) {
            Log.d("ChatScreen", "Received token for $conversationId: $streamedTokens")
            partialReply += streamedTokens
        }
    }

    // 6) Clear partial reply when streaming stops
    LaunchedEffect(currentStreamingConversation) {
        if (currentStreamingConversation != conversationId) {
            Log.d("ChatScreen", "Streaming stopped for $conversationId, clearing partial reply")
            partialReply = ""
        }
    }

    // 7) Handle initial message
    LaunchedEffect(initialMessage, conversationId) {
        if (!initialMessage.isNullOrBlank() && !hasProcessedInitialMessage) {
            Log.d("ChatScreen", "Processing initial message: $initialMessage")
            partialReply = ""
            viewModel.startNewConversation(conversationId, initialMessage)
            hasProcessedInitialMessage = true
        }
    }

    val listState = rememberLazyListState()

    // 8) Auto-scroll when messages or partial reply changes
    LaunchedEffect(messages.size, partialReply) {
        val totalItems = messages.size + if (partialReply.isNotEmpty()) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        // Debug info
        Text(
            text = "Conv: $conversationId | Messages: ${messages.size} | Streaming: ${currentStreamingConversation == conversationId}",
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Messages display
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            // Show persisted messages
            items(messages) { msg ->
                MessageBubble(Message(msg.text, msg.isUser))
                Spacer(Modifier.height(8.dp))
            }

            // Show streaming partial reply
            if (partialReply.isNotEmpty() && currentStreamingConversation == conversationId) {
                item {
                    MessageBubble(Message(partialReply, isUser = false))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Input section
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = currentStreamingConversation != conversationId // Disable while streaming
            )

            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && currentStreamingConversation != conversationId) {
                        val messageToSend = inputText.trim()
                        inputText = ""
                        partialReply = ""

                        Log.d("ChatScreen", "Sending message: $messageToSend")
                        viewModel.sendMessage(conversationId, messageToSend)
                    }
                },
                enabled = inputText.isNotBlank() && currentStreamingConversation != conversationId
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .align(alignment)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}