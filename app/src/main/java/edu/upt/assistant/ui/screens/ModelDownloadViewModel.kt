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
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.DownloadProgress
import edu.upt.assistant.domain.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager
) : ViewModel() {

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    fun fileNameFrom(url: String): String = downloadManager.fileNameFrom(url)

    fun isModelAvailable(url: String): Boolean = downloadManager.isModelAvailable(url)

    fun setModelUrl(url: String) {
        _currentUrl.value = url
        _downloadState.value = if (downloadManager.isModelAvailable(url)) {
            DownloadState.Completed
        } else {
            DownloadState.NotStarted
        }
    }

    fun startDownload() {
        val url = _currentUrl.value
        if (url.isBlank() || _downloadState.value is DownloadState.Downloading) return

        viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(DownloadProgress(0, 0, 0))

            try {
                if (downloadManager.isModelAvailable(url)) {
                    downloadManager.deleteModel(url)
                }

                downloadManager.downloadModel(url).collect { progress ->
                    _downloadState.value = DownloadState.Downloading(progress)

                    if (progress.percentage >= 100) {
                        _downloadState.value = DownloadState.Completed
                    }
                }
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun deleteModel() {
        val url = _currentUrl.value
        if (url.isBlank()) return
        viewModelScope.launch {
            downloadManager.deleteModel(url)
            _downloadState.value = DownloadState.NotStarted
        }
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
    modelUrl: String,
    viewModel: ModelDownloadViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()

    LaunchedEffect(modelUrl) {
        if (modelUrl.isNotBlank()) {
            viewModel.setModelUrl(modelUrl)
        }
    }

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
                        text = "This app requires downloading an AI model (~4GB) for offline chat functionality.",
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