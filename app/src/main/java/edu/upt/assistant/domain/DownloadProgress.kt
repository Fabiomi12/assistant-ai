package edu.upt.assistant.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    
    fun downloadModel(): Flow<DownloadProgress> = flow {
        if (isModelAvailable()) {
            emit(DownloadProgress(modelFile.length(), modelFile.length(), 100))
            return@flow
        }
        
        modelsDir.mkdirs()
        
        val url = URL(MODEL_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()
        
        val totalBytes = connection.contentLengthLong
        var bytesDownloaded = 0L
        
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
                    
                    emit(DownloadProgress(bytesDownloaded, totalBytes, percentage))
                }
            }
        }
        
        connection.disconnect()
    }.flowOn(Dispatchers.IO)
    
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        if (modelFile.exists()) {
            modelFile.delete()
        }
    }
}