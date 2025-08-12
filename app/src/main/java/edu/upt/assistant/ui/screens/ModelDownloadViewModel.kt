package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.DownloadProgress
import edu.upt.assistant.domain.ModelDownloadManager
import edu.upt.assistant.domain.ModelDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val app: Application,
    private val downloadManager: ModelDownloadManager
) : ViewModel() {

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(ModelDownloadService.EXTRA_SUCCESS, false) ?: false
            if (success) {
                _downloadState.value = DownloadState.Completed
            } else {
                val msg = intent?.getStringExtra(ModelDownloadService.EXTRA_ERROR) ?: "Download failed"
                _downloadState.value = DownloadState.Error(msg)
            }
        }
    }

    init {
        if (downloadManager.isModelAvailable()) {
            _downloadState.value = DownloadState.Completed
        }

        viewModelScope.launch {
            downloadManager.progress.collect { progress ->
                _downloadState.value = DownloadState.Downloading(progress)
            }
        }

        app.registerReceiver(
            downloadReceiver,
            IntentFilter(ModelDownloadService.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    fun startDownload() {
        if (_downloadState.value is DownloadState.Downloading) return

        val intent = Intent(app, ModelDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }

        _downloadState.value = DownloadState.Downloading(DownloadProgress(0, 0, 0))
    }

    fun deleteModel() {
        viewModelScope.launch {
            downloadManager.deleteModel()
            _downloadState.value = DownloadState.NotStarted
        }
    }

    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(downloadReceiver)
    }
}

sealed class DownloadState {
    object NotStarted : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    onModelReady: () -> Unit,
    viewModel: ModelDownloadViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()

    // Navigate automatically when model is ready (only for first-time setup)
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            // Small delay to show completion state
            kotlinx.coroutines.delay(1000)
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
            when (val state = downloadState) {
                is DownloadState.NotStarted -> {
                    Text(
                        text = "AI Model Required",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "This app requires downloading an AI model (~2GB) for offline chat functionality.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.startDownload() }
                    ) {
                        Text("Download Model")
                    }
                }

                is DownloadState.Downloading -> {
                    Text(
                        text = "Downloading Model...",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { state.progress.percentage / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "${state.progress.percentage}% - ${formatBytes(state.progress.bytesDownloaded)} / ${formatBytes(state.progress.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is DownloadState.Completed -> {
                    Text(
                        text = "Model Ready!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Initializing...")
                }

                is DownloadState.Error -> {
                    Text(
                        text = "Download Failed",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.startDownload() }
                    ) {
                        Text("Retry Download")
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