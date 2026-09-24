package com.zip2apk.builder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BuildForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "Android project" }
                startForeground(PROGRESS_NOTIFICATION_ID, progressNotification("Preparing build", source))
            }
            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "Building APK" }
                val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                notificationManager.notify(PROGRESS_NOTIFICATION_ID, progressNotification(status, detail))
            }
            ACTION_FAILURE -> {
                val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty().ifBlank {
                    "The APK build failed. Open Zip2APK for the complete log."
                }
                notificationManager.notify(FAILURE_NOTIFICATION_ID, failureNotification(detail))
                stopBuildService()
            }
            ACTION_STOP -> stopBuildService()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the task away is the explicit user cancellation gesture requested by Zip2APK.
        BuildSessionRuntime.cancelActive?.invoke()
        stopBuildService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zip2APK:build").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopBuildService() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun progressNotification(status: String, detail: String) = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_build_notification)
        .setContentTitle(status)
        .setContentText(detail.take(160))
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(500)))
        .setContentIntent(openAppPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(0, 0, true)
        .build()

    private fun failureNotification(detail: String) = NotificationCompat.Builder(this, CHANNEL_RESULTS)
        .setSmallIcon(R.drawable.ic_build_notification)
        .setContentTitle("Zip2APK build failed")
        .setContentText(detail.take(160))
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(800)))
        .setContentIntent(openAppPendingIntent())
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Build progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the current local APK build status."
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Build results", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a background APK build fails."
            }
        )
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_PROGRESS = "zip2apk_build_progress"
        private const val CHANNEL_RESULTS = "zip2apk_build_results"
        private const val PROGRESS_NOTIFICATION_ID = 4101
        private const val FAILURE_NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.zip2apk.builder.action.BUILD_START"
        private const val ACTION_UPDATE = "com.zip2apk.builder.action.BUILD_UPDATE"
        private const val ACTION_FAILURE = "com.zip2apk.builder.action.BUILD_FAILURE"
        private const val ACTION_STOP = "com.zip2apk.builder.action.BUILD_STOP"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_DETAIL = "detail"
        private const val MAX_WAKELOCK_MS = 12L * 60L * 60L * 1000L

        fun start(context: Context, sourceName: String?) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE, sourceName ?: "Android project")
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, status: String, detail: String = "") {
            runCatching {
                context.startService(Intent(context, BuildForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS, status)
                    putExtra(EXTRA_DETAIL, detail)
                })
            }
        }

        fun failed(context: Context, detail: String) {
            runCatching {
                context.startService(Intent(context, BuildForegroundService::class.java).apply {
                    action = ACTION_FAILURE
                    putExtra(EXTRA_DETAIL, detail)
                })
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, BuildForegroundService::class.java).apply { action = ACTION_STOP }) }
        }
    }
}
