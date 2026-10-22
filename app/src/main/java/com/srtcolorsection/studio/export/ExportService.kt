package com.srtcolorsection.studio.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.srtcolorsection.studio.MainActivity
import com.srtcolorsection.studio.R
import com.srtcolorsection.studio.domain.CancelToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object ExportBus {
    val state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    @Volatile var pending: ExportRequest? = null
    @Volatile var token: CancelToken? = null

    fun cancel() {
        token?.cancel()
        runCatching { FfmpegRunner.cancelActive() }
    }
}

class ExportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportBus.cancel()
            return START_NOT_STICKY
        }
        val request = ExportBus.pending
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(0, "Preparing export")
        val token = CancelToken()
        ExportBus.token = token
        ExportBus.state.value = ExportUiState.Running(
            ExportProgress("Starting", 0, 1, "", 0, 0, 0),
        )
        scope.launch {
            val result = ExportCoordinator(applicationContext).run(request, token) { progress ->
                ExportBus.state.value = ExportUiState.Running(progress)
                startInForeground(progress.percent, progress.phase)
            }
            ExportBus.state.value = result
            ExportBus.pending = null
            ExportBus.token = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(percent: Int, title: String) {
        val notification = buildNotification(percent, title)
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(percent: Int, title: String): Notification {
        ensureChannel()
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, ExportForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("SRT Color Section Studio")
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Timeline video and section image exports"
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "exports"
        const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "com.srtcolorsection.studio.CANCEL_EXPORT"

        fun start(context: Context) {
            val intent = Intent(context, ExportForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
