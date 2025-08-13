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
import java.net.URI
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
        const val DEFAULT_MODEL_URL = "https://huggingface.co/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_1.gguf?download=true"
    }

    private val modelsDir = File(context.filesDir, "models")

    private fun fileNameFrom(url: String): String {
        val path = URI(url).path
        return path.substringAfterLast('/')
    }

    private fun modelFile(url: String): File = File(modelsDir, fileNameFrom(url))

    fun isModelAvailable(url: String = DEFAULT_MODEL_URL): Boolean = modelFile(url).exists()

    fun getModelPath(url: String = DEFAULT_MODEL_URL): String = modelFile(url).absolutePath

    fun downloadModel(url: String = DEFAULT_MODEL_URL): Flow<DownloadProgress> = flow {
        val file = modelFile(url)
        if (file.exists()) {
            emit(DownloadProgress(file.length(), file.length(), 100))
            return@flow
        }

        modelsDir.mkdirs()

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

    suspend fun deleteModel(url: String = DEFAULT_MODEL_URL) = withContext(Dispatchers.IO) {
        val file = modelFile(url)
        if (file.exists()) {
            file.delete()
        }
    }
}