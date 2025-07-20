package edu.upt.assistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// Data model for a conversation summary
data class Conversation(
    val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    onBack: () -> Unit,
    onConversationClick: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(conversations, key = { it.id }) { convo ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConversationClick(convo.id) },
                    headlineContent   = { Text(convo.title) },
                    supportingContent = { Text(convo.lastMessage) },
                    trailingContent = {
                        IconButton(onClick = { onDeleteChat(convo.id) }) {
                            Icon(
                                imageVector   = Icons.Default.Delete,
                                contentDescription = "Delete chat"
                            )
                        }
                    }
                )
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HistoryScreenPreview() {
    HistoryScreen(
        conversations = listOf(
            Conversation("1", "Chat with Assistant", "Sure, let me help with that...", "Jul 19, 10:00 AM"),
            Conversation("2", "First Chat", "Welcome to the chat app", "Jul 18, 3:45 PM")
        ),
        onBack = {},
        onConversationClick = {},
        onDeleteChat = {}
    )
}
