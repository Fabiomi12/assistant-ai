package edu.upt.assistant.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.collect

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadManager: ModelDownloadManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val INPUT_URL = "model_url"
        const val PROGRESS = "progress"
        const val BYTES_DOWNLOADED = "bytes_downloaded"
        const val TOTAL_BYTES = "total_bytes"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(INPUT_URL) ?: return Result.failure()
        setForeground(getForegroundInfo(0))
        return try {
            downloadManager.downloadModel(url).collect { progress ->
                setProgress(
                    workDataOf(
                        PROGRESS to progress.percentage,
                        BYTES_DOWNLOADED to progress.bytesDownloaded,
                        TOTAL_BYTES to progress.totalBytes
                    )
                )
                setForeground(getForegroundInfo(progress.percentage))
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun getForegroundInfo(progress: Int): ForegroundInfo {
        val notification = createNotification(progress)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotification(progress: Int): Notification {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Downloading model")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }
}
