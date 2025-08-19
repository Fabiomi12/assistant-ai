package edu.upt.assistant.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Simple data model for a chat message
data class Message(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    streamingMessage: String?,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    initialMessage: String? = null,
    onSaveToMemory: ((String) -> Unit)? = null
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Set the initial message when the screen is first composed
    LaunchedEffect(initialMessage) {
        if (initialMessage != null && inputText.isEmpty()) {
            onSend(initialMessage)
        }
    }

    // Auto-scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingMessage) {
        if (messages.isNotEmpty() || streamingMessage != null) {
            listState.animateScrollToItem(
                index = if (streamingMessage != null) messages.size else messages.size - 1
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Message list
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    onSaveToMemory = onSaveToMemory
                )
            }

            // Show streaming message if present
            if (streamingMessage != null && streamingMessage.isNotEmpty()) {
                item {
                    MessageBubble(
                        message = Message(
                            text = streamingMessage,
                            isUser = false,
                            isStreaming = true
                        ),
                        onSaveToMemory = onSaveToMemory
                    )
                }
            }

            // Show loading indicator if streaming but no content yet
            if (isStreaming && streamingMessage.isNullOrEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp)
                            )
                            Text("Generating response...")
                        }
                    }
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = !isStreaming // Disable input while streaming
            )
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isStreaming
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onSaveToMemory: ((String) -> Unit)? = null
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .align(alignment)
                .combinedClickable(
                    onClick = { /* Regular click - no action */ },
                    onLongClick = {
                        if (onSaveToMemory != null && !message.isStreaming) {
                            showContextMenu = true
                        }
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "● Generating...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // Context menu for saving to memory
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("⭐ Save to Memory") },
                onClick = {
                    showContextMenu = false
                    showSaveDialog = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Save to Memory"
                    )
                }
            )
        }
    }
    
    // Save to memory confirmation dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save to Memory") },
            text = { 
                Text("Save this message as a personal memory?\n\n\"${message.text.take(100)}${if (message.text.length > 100) "..." else ""}\"")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveToMemory?.invoke(message.text)
                        showSaveDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}