package edu.upt.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.domain.SettingsViewModel
import edu.upt.assistant.ui.screens.ChatRoute
import edu.upt.assistant.ui.screens.HistoryScreen
import edu.upt.assistant.ui.screens.NewChatScreen
import edu.upt.assistant.ui.screens.SettingsScreen
import edu.upt.assistant.ui.screens.SetupRoute
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID

@Composable
fun AppNavGraph(
    navController: NavHostController,
) {
    // grab the VM once at top‐level
    val vm: ChatViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    // collect conversations reactively
    val conversations by vm.conversations.collectAsState()
    val username by settingsVm.username.collectAsState(initial = "")
    val notifications by settingsVm.notificationsEnabled.collectAsState(initial = false)
    val setupDone by settingsVm.setupDone.collectAsState(initial = false)

    // pick start based on whether onboarding completed
    val startRoute = if (setupDone) NEW_CHAT_ROUTE else SETUP_ROUTE

    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        // 0) Onboarding / Setup
        composable(SETUP_ROUTE) {
            SetupRoute(
                onFinish = {
                    navController.navigate(NEW_CHAT_ROUTE)
                }
            )
        }

        // 1) Setup / New Chat Screen
        composable(NEW_CHAT_ROUTE) {
            NewChatScreen(
                username = username,
                onStartChat = { initial ->
                    // Generate the ID immediately:
                    val newId = UUID.randomUUID().toString()
                    vm.startNewConversation(newId, initial)

                    // Navigate with the initial message encoded
                    val encodedMessage = URLEncoder.encode(initial, "UTF-8")
                    navController.navigate("chat/$newId?initialMessage=$encodedMessage")
                },
                onHistoryClick = { navController.navigate(HISTORY_ROUTE) },
                onSettingsClick = { navController.navigate(SETTINGS_ROUTE) }
            )
        }

        // 2) History Screen
        composable(HISTORY_ROUTE) {
            HistoryScreen(
                conversations = conversations,
                onBack = { navController.popBackStack() },
                onConversationClick = { convId ->
                    // Navigate without initial message for existing conversations
                    navController.navigate("chat/$convId")
                },
                onDeleteChat = {
                    vm.deleteConversation(it)
                }
            )
        }

        // 3) Chat Screen (reactive!)
        composable(
            route = CHAT_ROUTE,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("initialMessage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val convId = backStackEntry.arguments?.getString("conversationId")
            val initialMessage = backStackEntry.arguments?.getString("initialMessage")

            if (convId != null) {
                ChatRoute(
                    conversationId = convId,
                    navController = navController,
                    initialMessage = initialMessage?.let {
                        URLDecoder.decode(it, "UTF-8")
                    }
                )
            }
        }

        // 4) Settings Screen
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