package edu.upt.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.domain.SettingsViewModel
import edu.upt.assistant.ui.screens.ChatRoute
import edu.upt.assistant.ui.screens.HistoryScreen
import edu.upt.assistant.ui.screens.NewChatScreen
import edu.upt.assistant.ui.screens.SettingsScreen
import java.util.UUID

const val NEW_CHAT_ROUTE = "new_chat"
const val HISTORY_ROUTE  = "history"
const val CHAT_ROUTE     = "chat/{conversationId}"
const val SETTINGS_ROUTE = "settings"

@Composable
fun AppNavGraph(
    navController: NavHostController,
) {
    // grab the VM once at top‐level
    val vm: ChatViewModel = hiltViewModel()

    // collect conversations reactively
    val conversations by vm.conversations.collectAsState()

    NavHost(
        navController = navController,
        startDestination = NEW_CHAT_ROUTE
    ) {
        // 1) Setup / New Chat Screen
        composable(NEW_CHAT_ROUTE) {
            NewChatScreen(
                username        = vm.username,
                onStartChat     = { initial ->
                    // Generate the ID immediately:
                    val newId = UUID.randomUUID().toString()
                    // Tell VM to create + send:
                    // TODO fix message save
                    vm.startNewConversation(newId, initial)
                    // Navigate with that same ID
                    navController.navigate("chat/$newId") {
                        popUpTo(NEW_CHAT_ROUTE) { inclusive = true }
                    }
                },
                onHistoryClick  = { navController.navigate(HISTORY_ROUTE) },
                onSettingsClick = { navController.navigate(SETTINGS_ROUTE) }
            )
        }

        // 2) History Screen
        composable(HISTORY_ROUTE) {
            HistoryScreen(
                conversations = conversations,
                onBack = { navController.popBackStack() },
                onConversationClick = { convId ->
                    navController.navigate("chat/$convId")
                }
            )
        }

        // 3) Chat Screen (reactive!)
        composable(CHAT_ROUTE) { backStackEntry ->
            val convId = backStackEntry.arguments!!.getString("conversationId")!!

            ChatRoute(
                conversationId = convId,
                navController   = navController,
            )
        }

        // 4) Settings Screen (when you build it)
        composable(SETTINGS_ROUTE) {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val username by settingsVm.username.collectAsState()
            val notificationsEnabled by settingsVm.notificationsEnabled.collectAsState()

            SettingsScreen(
                username = username,
                notificationsEnabled = notificationsEnabled,
                onUserNameChange = { settingsVm.setUsername(it) },
                onNotificationsToggle = { settingsVm.setNotificationsEnabled(it) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
