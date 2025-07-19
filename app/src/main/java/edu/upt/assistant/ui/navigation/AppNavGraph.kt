package edu.upt.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.upt.assistant.ui.screens.ChatScreen
import edu.upt.assistant.ui.screens.Conversation
import edu.upt.assistant.ui.screens.HistoryScreen
import edu.upt.assistant.ui.screens.Message
import edu.upt.assistant.ui.screens.NewChatScreen

private const val NEW_CHAT_ROUTE   = "new_chat"
private const val CHAT_ROUTE = "chat/{conversationId}"
private const val HISTORY_ROUTE = "history"

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    username: String,
    conversations: List<Conversation>,
    getMessagesFor: (String) -> List<Message>,
    onSendMessage: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onStartNewChat: (String) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = NEW_CHAT_ROUTE
    ) {
        // 1) NEW CHAT SCREEN
        composable(NEW_CHAT_ROUTE) {
            NewChatScreen(
                userName = username,
                onStartChat = { initialMessage ->
                    // a) generate a new ID
                    val newId = (conversations.size + 1).toString()
                    // b) let your host (MainActivity/ViewModel) add it to the history list
                    onStartNewChat(newId)
                    // c) send that first message into the new convo
                    onSendMessage(initialMessage)
                    // d) navigate into the chat view, popping the new_chat screen
                    navController.navigate("chat/$newId") {
                        popUpTo(NEW_CHAT_ROUTE) { inclusive = true }
                    }
                }
            )
        }

        // 2) HISTORY SCREEN
        composable(HISTORY_ROUTE) {
            HistoryScreen(
                conversations = conversations,
                onBack = { navController.popBackStack() },
                onConversationClick = { id ->
                    navController.navigate("chat/$id")
                }
            )
        }

        // 3) CHAT SCREEN
        composable(CHAT_ROUTE) { backStackEntry ->
            val conversationId = backStackEntry.arguments!!.getString("conversationId")!!
            ChatScreen(
                messages        = getMessagesFor(conversationId),
                onSend          = { text -> onSendMessage(text) },
                onHistoryClick  = { navController.navigate(HISTORY_ROUTE) },
                onSettingsClick = onSettingsClick
            )
        }
    }
}

