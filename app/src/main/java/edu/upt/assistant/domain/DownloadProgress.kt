package edu.upt.assistant.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val MODEL_URL = "https://huggingface.co/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_1.gguf?download=true"
        private const val MODEL_FILENAME = "gemma-3n-E4B-it-Q4_1.gguf"
    }
    
    private val modelsDir = File(context.filesDir, "models")
    private val modelFile = File(modelsDir, MODEL_FILENAME)
    
    fun isModelAvailable(): Boolean = modelFile.exists()
    
    fun getModelPath(): String = modelFile.absolutePath
    
    private val _progress = MutableSharedFlow<DownloadProgress>(replay = 1, extraBufferCapacity = 1)
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (isModelAvailable()) {
            _progress.emit(
                DownloadProgress(modelFile.length(), modelFile.length(), 100)
            )
            return@withContext
        }

        modelsDir.mkdirs()

        val url = URL(MODEL_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()

        val totalBytes = connection.contentLengthLong
        var bytesDownloaded = 0L

        _progress.emit(DownloadProgress(0, totalBytes, 0))

        connection.inputStream.use { input ->
            FileOutputStream(modelFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    val percentage = if (totalBytes > 0) {
                        ((bytesDownloaded * 100) / totalBytes).toInt()
                    } else 0

                    _progress.emit(DownloadProgress(bytesDownloaded, totalBytes, percentage))
                }
            }
        }

        connection.disconnect()

        _progress.emit(DownloadProgress(bytesDownloaded, totalBytes, 100))
    }
    
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        if (modelFile.exists()) {
            modelFile.delete()
        }
    }
}