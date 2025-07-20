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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import edu.upt.assistant.domain.ChatViewModel
import kotlinx.coroutines.flow.onCompletion

// Simple data model for a chat message
data class Message(val text: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    // 1) Collect your persisted history
    val messages by viewModel.messagesFor(conversationId)
        .collectAsState(initial = emptyList())

    // 2) Hold the partial “typing” reply locally
    var partialReply by remember { mutableStateOf("") }

    // 3) A one‑off trigger whenever we send a new message
    var sendTrigger by remember { mutableStateOf<String?>(null) }

    // 4) Kick off the streaming when sendTrigger changes
    LaunchedEffect(sendTrigger) {
        sendTrigger?.let { text ->
            partialReply = ""
            viewModel
                .sendMessage(conversationId, text)        // returns Flow<String>
                .onCompletion {                           // this works here
                    /* optionally clear or finalize */
                }
                .collect { token ->
                    partialReply += token                   // accumulate tokens
                }
            sendTrigger = null                          // reset trigger
        }
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 5) Auto‑scroll whenever history or partialReply changes
    LaunchedEffect(messages.size, partialReply) {
        val lastIndex = messages.size + if (partialReply.isNotEmpty()) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex - 1)
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(messages) { msg ->
                MessageBubble(Message(msg.text, msg.isUser))
                Spacer(Modifier.height(8.dp))
            }
            if (partialReply.isNotEmpty()) {
                item {
                    MessageBubble(Message(partialReply, isUser = false))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            IconButton(onClick = {
                if (inputText.isNotBlank()) {
                    sendTrigger = inputText.trim()   // this kicks off LaunchedEffect
                    inputText = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
