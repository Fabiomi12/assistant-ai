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
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.DownloadProgress
import edu.upt.assistant.domain.ModelDownloadManager
import edu.upt.assistant.domain.ModelDownloadWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val WORK_NAME = "model_download"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var currentWorkId: UUID? = null

    init {
        if (downloadManager.isModelAvailable()) {
            _downloadState.value = DownloadState.Completed
        } else {
            restoreWork()
        }
    }

    private fun restoreWork() {
        viewModelScope.launch {
            val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).await()
            val info = infos.firstOrNull()
            if (info != null) {
                currentWorkId = info.id
                handleWorkInfo(info)
                observeWork(info.id)
            }
        }
    }

    fun startDownload() {
        if (_downloadState.value is DownloadState.Downloading) return

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.INPUT_URL to ModelDownloadManager.MODEL_URL))
            .addTag(WORK_NAME)
            .build()
        currentWorkId = request.id
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        observeWork(request.id)
    }

    private fun observeWork(id: UUID) {
        workManager.getWorkInfoByIdLiveData(id)
            .asFlow()
            .onEach { handleWorkInfo(it) }
            .launchIn(viewModelScope)
    }

    private fun handleWorkInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val progress = info.progress
                val bytes = progress.getLong(ModelDownloadWorker.BYTES_DOWNLOADED, 0L)
                val total = progress.getLong(ModelDownloadWorker.TOTAL_BYTES, 0L)
                val percent = progress.getInt(ModelDownloadWorker.PROGRESS, 0)
                _downloadState.value = DownloadState.Downloading(DownloadProgress(bytes, total, percent))
            }
            WorkInfo.State.SUCCEEDED -> _downloadState.value = DownloadState.Completed
            WorkInfo.State.FAILED -> _downloadState.value = DownloadState.Error("Download failed")
            WorkInfo.State.CANCELLED -> _downloadState.value = DownloadState.NotStarted
            else -> {}
        }
    }

    fun cancelDownload() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
    }

    fun deleteModel() {
        viewModelScope.launch {
            downloadManager.deleteModel()
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