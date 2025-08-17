package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import edu.upt.assistant.domain.ModelState
import edu.upt.assistant.domain.SettingsViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    onModelReady: () -> Unit,
    modelUrl: String,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val downloadManager = settingsViewModel.getDownloadManager()
    val downloadProgress by downloadManager.downloadProgress.collectAsState()

    val modelInfo = downloadManager.getModelInfo(modelUrl)
    val progress = downloadProgress[modelUrl]

    // Auto-navigate when model becomes available
    LaunchedEffect(modelInfo.state) {
        if (modelInfo.state == ModelState.Available) {
            delay(1000) // Show success briefly
            onModelReady()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Setup") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (modelInfo.state) {
                ModelState.NotDownloaded -> {
                    Text(
                        text = "AI Model Required",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "This app requires downloading an AI model for offline chat functionality.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { settingsViewModel.startDownload(modelUrl) }
                    ) {
                        Text("Download Model")
                    }
                }

                ModelState.Downloading -> {
                    Text(
                        text = "Downloading Model...",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.percentage / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${progress.percentage}% - ${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { settingsViewModel.cancelDownload(modelUrl) }
                        ) {
                            Text("Cancel Download")
                        }
                    } else {
                        CircularProgressIndicator()
                        Text("Initializing download...")
                    }
                }

                ModelState.Available -> {
                    Text(
                        text = "Model Ready!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Starting app...")
                }

                is ModelState.Error -> {
                    Text(
                        text = "Download Failed",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = modelInfo.state.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { settingsViewModel.startDownload(modelUrl) }
                    ) {
                        Text("Retry Download")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onModelReady
                    ) {
                        Text("Skip for now")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}
