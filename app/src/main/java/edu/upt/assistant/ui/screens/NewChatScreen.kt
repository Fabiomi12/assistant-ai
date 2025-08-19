package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    username: String,
    onStartChat: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    onMemoryClick: (() -> Unit)? = null
) {
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                actions = {
                    if (onMemoryClick != null) {
                        IconButton(onClick = onMemoryClick) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Memory"
                            )
                        }
                    }
                    IconButton(onClick = onDocumentsClick) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Documents"
                        )
                    }
                    IconButton(onClick = onHistoryClick) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History"
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Hello, $username!", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type your first message...") },
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onStartChat(inputText.trim())
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Text(text = "Start Chat")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NewChatScreenPreview() {
    NewChatScreen(
        username = "User",
        onStartChat = {},
        onHistoryClick = {},
        onSettingsClick = {},
        onDocumentsClick = {}
    )
}
