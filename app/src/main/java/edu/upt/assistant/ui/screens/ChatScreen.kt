package edu.upt.assistant.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Simple data model for a chat message
data class Message(val text: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    onSend: (String) -> Unit,
    initialMessage: String? = null
) {
    var inputText by remember { mutableStateOf("") }

    // Set the initial message when the screen is first composed
    LaunchedEffect(initialMessage) {
        if (initialMessage != null && inputText.isEmpty()) {
            inputText = initialMessage
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
            modifier = Modifier
                .weight(1f)
        ) {
            items(messages) { message ->
                MessageBubble(message)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText.trim())
                        inputText = ""
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
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