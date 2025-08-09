package edu.upt.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import edu.upt.assistant.domain.ChatRepositoryImpl
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.domain.SettingsViewModel
import edu.upt.assistant.ui.screens.ChatRoute
import edu.upt.assistant.ui.screens.HistoryScreen
import edu.upt.assistant.ui.screens.ModelDownloadScreen
import edu.upt.assistant.ui.screens.NewChatScreen
import edu.upt.assistant.ui.screens.SettingsScreen
import edu.upt.assistant.ui.screens.SetupRoute
import java.util.UUID
import javax.inject.Inject

@Composable
fun AppNavGraph(
    navController: NavHostController,
) {
    // grab the VMs once at top‐level
    val vm: ChatViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    // For checking model status, we need access to the repository
    // You can either inject ChatRepositoryImpl directly or create a method in ChatViewModel
    // For now, let's add a method to ChatViewModel to check model status

    // collect state reactively
    val conversations by vm.conversations.collectAsState()
    val username by settingsVm.username.collectAsState(initial = "")
    val notifications by settingsVm.notificationsEnabled.collectAsState(initial = false)
    val setupDone by settingsVm.setupDone.collectAsState(initial = false)

    // Check if model is ready (you'll need to add this method to ChatViewModel)
    val isModelReady by vm.isModelReady.collectAsState(initial = false)

    // Determine start route based on setup and model status
    val startRoute = when {
        !setupDone -> SETUP_ROUTE
        !isModelReady -> MODEL_DOWNLOAD_ROUTE
        else -> NEW_CHAT_ROUTE
    }

    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        // 0) Onboarding / Setup
        composable(SETUP_ROUTE) {
            SetupRoute(
                onFinish = {
                    // After setup, check if model is ready
                    val nextRoute = if (vm.isModelReadySync()) {
                        NEW_CHAT_ROUTE
                    } else {
                        MODEL_DOWNLOAD_ROUTE
                    }
                    navController.navigate(nextRoute) {
                        popUpTo(SETUP_ROUTE) { inclusive = true }
                    }
                }
            )
        }

        // 1) Model Download Screen
        composable(MODEL_DOWNLOAD_ROUTE) {
            ModelDownloadScreen(
                onModelReady = {
                    navController.navigate(NEW_CHAT_ROUTE) {
                        popUpTo(MODEL_DOWNLOAD_ROUTE) { inclusive = true }
                    }
                }
            )
        }

        // 2) New Chat Screen
        composable(NEW_CHAT_ROUTE) {
            NewChatScreen(
                username        = username,
                onStartChat     = { initial ->
                    // Generate the ID immediately:
                    val newId = UUID.randomUUID().toString()
                    vm.startNewConversation(newId, initial)
                    navController.navigate("chat/$newId")
                },
                onHistoryClick  = { navController.navigate(HISTORY_ROUTE) },
                onSettingsClick = { navController.navigate(SETTINGS_ROUTE) }
            )
        }

        // 3) History Screen
        composable(HISTORY_ROUTE) {
            HistoryScreen(
                conversations = conversations,
                onBack = { navController.popBackStack() },
                onConversationClick = { convId ->
                    navController.navigate("chat/$convId")
                },
                onDeleteChat = {
                    vm.deleteConversation(it)
                }
            )
        }

        // 4) Chat Screen (reactive!)
        composable(CHAT_ROUTE) { backStackEntry ->
            val convId = backStackEntry.arguments!!.getString("conversationId")!!

            ChatRoute(
                conversationId = convId,
                navController   = navController,
            )
        }

        // 5) Settings Screen
        composable(SETTINGS_ROUTE) {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val username by settingsVm.username.collectAsState()
            val notificationsEnabled by settingsVm.notificationsEnabled.collectAsState()

            SettingsScreen(
                username = username,
                notificationsEnabled = notificationsEnabled,
                onUserNameChange = { settingsVm.setUsername(it) },
                onNotificationsToggle = { settingsVm.setNotificationsEnabled(it) },
                onBack = { navController.popBackStack() },
                onDownloadModel = { navController.navigate(MODEL_DOWNLOAD_ROUTE) }
            )
        }
    }
}