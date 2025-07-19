package edu.upt.assistant.ui.screens

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.ui.navigation.HISTORY_ROUTE
import edu.upt.assistant.ui.navigation.SETTINGS_ROUTE

@Composable
fun ChatRoute(
    conversationId: String,
    navController: NavHostController
) {
    // get the same VM instance
    val vm: ChatViewModel = hiltViewModel()

    val messages by vm
        .messagesFor(conversationId)
        .collectAsState(initial = emptyList())

    ChatScreen(
      messages        = messages,
      onSend          = { text -> vm.sendMessage(conversationId, text) },
      onHistoryClick  = { navController.navigate(HISTORY_ROUTE) },
      onSettingsClick = { navController.navigate(SETTINGS_ROUTE) }
    )
}
