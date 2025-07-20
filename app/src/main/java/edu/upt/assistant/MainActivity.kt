package edu.upt.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import edu.upt.assistant.domain.ChatViewModel
import edu.upt.assistant.ui.navigation.AppNavGraph
import edu.upt.assistant.ui.theme.EchoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoTheme {
                // 1) NavController for all navigation
                val navController = rememberNavController()

                // 2) Get your Hilt‑provided ViewModel
                val vm: ChatViewModel = hiltViewModel()

                // 4) Compose the NavGraph, wiring in VM state & actions
                AppNavGraph(navController = navController)
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EchoTheme {
        Greeting("Android")
    }
}