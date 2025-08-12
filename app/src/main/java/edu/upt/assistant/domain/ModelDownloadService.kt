package edu.upt.assistant.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject
    lateinit var downloadManager: ModelDownloadManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(0))

        serviceScope.launch {
            try {
                downloadManager.downloadModel()
                sendBroadcast(Intent(ACTION_DOWNLOAD_COMPLETE).putExtra(EXTRA_SUCCESS, true))
            } catch (e: Exception) {
                sendBroadcast(
                    Intent(ACTION_DOWNLOAD_COMPLETE)
                        .putExtra(EXTRA_SUCCESS, false)
                        .putExtra(EXTRA_ERROR, e.message)
                )
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }

        serviceScope.launch {
            downloadManager.progress.collectLatest { progress ->
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(progress.percentage)
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading model")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_DOWNLOAD_COMPLETE = "edu.upt.assistant.ACTION_DOWNLOAD_COMPLETE"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_ERROR = "extra_error"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1
    }
}
