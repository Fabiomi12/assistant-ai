package edu.upt.assistant.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.domain.utils.ModelUtils.fileNameFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int
)

data class ModelInfo(
    val url: String,
    val fileName: String,
    val state: ModelState,
    val downloadProgress: DownloadProgress? = null
)

sealed class ModelState {
    object NotDownloaded : ModelState()
    object Downloading : ModelState()
    object Available : ModelState()
    data class Error(val message: String) : ModelState()
}

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val DEFAULT_MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q3_k_m.gguf"
    }

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    // Persistent download state that survives navigation
    private val _downloadStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelState>> = _downloadStates.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    // Track active download jobs
    private val activeDownloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun modelFile(fileName: String): File = File(modelsDir, fileName)

    fun getModelInfo(url: String): ModelInfo {
        val fileName = fileNameFrom(url)
        val file = modelFile(fileName)

        // Check persistent state first, then file system
        val state = _downloadStates.value[url] ?: run {
            if (file.exists() && file.length() > 0) ModelState.Available
            else ModelState.NotDownloaded
        }

        return ModelInfo(
            url = url,
            fileName = fileName,
            state = state,
            downloadProgress = _downloadProgress.value[url]
        )
    }

    fun getAllModelInfo(urls: Set<String>): List<ModelInfo> {
        return urls.map { getModelInfo(it) }
    }

    fun isModelAvailable(url: String): Boolean {
        val fileName = fileNameFrom(url)
        val file = modelFile(fileName)
        return file.exists() && file.length() > 0 && _downloadStates.value[url] != ModelState.Downloading
    }

    fun getModelPath(url: String): String {
        val fileName = fileNameFrom(url)
        return modelFile(fileName).absolutePath
    }

    fun isDownloading(url: String): Boolean {
        return _downloadStates.value[url] == ModelState.Downloading
    }

    suspend fun startDownload(url: String, scope: kotlinx.coroutines.CoroutineScope): Boolean {
        // Check if already downloading
        if (isDownloading(url)) return false

        val fileName = fileNameFrom(url)
        val file = modelFile(fileName)

        // If file already exists and is complete, no need to download
        if (file.exists() && file.length() > 0) {
            _downloadStates.value = _downloadStates.value + (url to ModelState.Available)
            return true
        }

        // Start download
        val job = scope.launch(Dispatchers.IO) {
            try {
                _downloadStates.value = _downloadStates.value + (url to ModelState.Downloading)

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                val totalBytes = connection.contentLengthLong
                var bytesDownloaded = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            // Check if download was cancelled
                            if (!isDownloading(url)) {
                                file.delete()
                                return@launch
                            }

                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            val percentage = if (totalBytes > 0) {
                                ((bytesDownloaded * 100) / totalBytes).toInt()
                            } else 0

                            _downloadProgress.value = _downloadProgress.value + (url to DownloadProgress(bytesDownloaded, totalBytes, percentage))
                        }
                    }
                }

                connection.disconnect()

                // Download completed successfully
                _downloadStates.value = _downloadStates.value + (url to ModelState.Available)
                _downloadProgress.value = _downloadProgress.value - url

            } catch (e: Exception) {
                file.delete()
                _downloadStates.value = _downloadStates.value + (url to ModelState.Error(e.message ?: "Download failed"))
                _downloadProgress.value = _downloadProgress.value - url
            }
        }

        activeDownloadJobs[url] = job
        return true
    }

    suspend fun cancelDownload(url: String) {
        activeDownloadJobs[url]?.cancel()
        activeDownloadJobs.remove(url)

        _downloadStates.value = _downloadStates.value - url
        _downloadProgress.value = _downloadProgress.value - url

        withContext(Dispatchers.IO) {
            val fileName = fileNameFrom(url)
            val file = modelFile(fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    suspend fun deleteModel(url: String) {
        // Cancel download if ongoing
        cancelDownload(url)

        withContext(Dispatchers.IO) {
            val fileName = fileNameFrom(url)
            val file = modelFile(fileName)
            if (file.exists()) {
                file.delete()
            }
        }

        // Update state
        _downloadStates.value = _downloadStates.value - url
    }

    // Get all active downloads (useful for showing global download status)
    fun getActiveDownloads(): List<ModelInfo> {
        return _downloadStates.value.entries
            .filter { it.value == ModelState.Downloading }
            .map { getModelInfo(it.key) }
    }
}