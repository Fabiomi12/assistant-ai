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
    vm: ChatViewModel = hiltViewModel()
) {

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
            conversationId = conversationId,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            vm
        )
    }
}
