package com.srtcolorsection.studio.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.srtcolorsection.studio.MainActivity
import com.srtcolorsection.studio.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Optional whole-display recorder. This is not used by timeline export.
 * MediaProjection always includes system bars and other apps; the notification
 * and the system recording indicator stay visible.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording(success = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    state.value = RecordState.Failed("Screen capture permission was not granted.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                try {
                    begin(code, data)
                } catch (error: Exception) {
                    DiagLog.add("Screen record failed: ${error.message}")
                    state.value = RecordState.Failed(error.message ?: "Could not start screen recording")
                    releaseQuietly()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val metrics = displayMetrics()
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (width > 1920) {
            val scale = 1920f / width
            width = 1920
            height = (metrics.heightPixels * scale).toInt()
        }
        width = width and 1.inv()
        height = height and 1.inv()
        if (width < 2 || height < 2) error("Display size is not usable")
        val file = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir, "screen-${System.currentTimeMillis()}.mp4")
        output = file
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(width, height)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoEncodingBitRate(6_000_000)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        recorder = mediaRecorder
        val created = manager.getMediaProjection(resultCode, data)
        projection = created
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording(success = true)
            }
        }, Handler(Looper.getMainLooper()))
        display = created.createVirtualDisplay(
            "SRTStudioRecord",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null,
        )
        mediaRecorder.start()
        state.value = RecordState.Recording(
            "Recording the whole display at ${width}×${height}. System bars and other apps are included. This is not the clean timeline export.",
        )
        DiagLog.add("Screen recording started ${file.name}")
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val window = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = window.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            window.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun stopRecording(success: Boolean) {
        val file = output
        releaseQuietly()
        if (success && file != null && file.exists() && file.length() > 0L) {
            state.value = RecordState.Saved(file.absolutePath, "Recording saved in the app Movies folder. It includes the full display, not only this app.")
            DiagLog.add("Screen recording saved ${file.length()} bytes")
        } else {
            file?.delete()
            if (state.value !is RecordState.Failed) {
                state.value = RecordState.Idle
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseQuietly() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("Recording the screen")
            .setContentText("Whole display. Tap stop to finish.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "screen-record"
        const val NOTIFICATION_ID = 43
        const val ACTION_START = "com.srtcolorsection.studio.RECORD_START"
        const val ACTION_STOP = "com.srtcolorsection.studio.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        val state = MutableStateFlow<RecordState>(RecordState.Idle)

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}

sealed class RecordState {
    data object Idle : RecordState()
    data class Recording(val message: String) : RecordState()
    data class Saved(val path: String, val message: String) : RecordState()
    data class Failed(val message: String) : RecordState()
}
