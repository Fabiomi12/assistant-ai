package edu.upt.assistant.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.DownloadProgress
import edu.upt.assistant.domain.ModelDownloadManager
import kotlinx.coroutines.Job
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

    private var downloadJob: Job? = null

    fun setModelUrl(url: String) {
        _currentUrl.value = url
        _downloadState.value = if (downloadManager.isModelAvailableUrl(url)) {
            DownloadState.Completed
        } else {
            DownloadState.NotStarted
        }
    }

    fun startDownload() {
        val url = _currentUrl.value
        if (url.isBlank() || _downloadState.value is DownloadState.Downloading) return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(DownloadProgress(0, 0, 0))

            try {
                if (downloadManager.isModelAvailableUrl(url)) {
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
            _currentUrl.value = ""
        }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        super.onCleared()
    }
}

sealed class DownloadState {
    object NotStarted : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}
