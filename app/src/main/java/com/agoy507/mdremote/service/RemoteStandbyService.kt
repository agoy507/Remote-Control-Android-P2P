package com.agoy507.mdremote.service

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.agoy507.mdremote.MainActivity
import com.agoy507.mdremote.R

/**
 * Foreground Service untuk menjaga koneksi WebSocket signaling dan kesiapan P2P
 * tetap aktif terus-menerus 24/7 di latar belakang meskipun aplikasi diminimize.
 */
class RemoteStandbyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_STANDBY -> {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_STANDBY -> {
                acquireWakeLock()
                val text = intent.getStringExtra(EXTRA_STATUS_TEXT)
                    ?: getString(R.string.notification_standby_connecting)
                val notification = buildNotification(text)
                startForegroundCompat(notification)
            }
            ACTION_UPDATE_STATUS -> {
                val text = intent.getStringExtra(EXTRA_STATUS_TEXT)
                    ?: getString(R.string.notification_standby_online)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MDRemote:StandbyWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 3600 * 1000L) // 24 jam max
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_standby),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_standby_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteStandbyService::class.java).apply { action = ACTION_STOP_STANDBY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_standby_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.action_close_app), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mdremote_standby_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START_STANDBY = "com.agoy507.mdremote.action.START_STANDBY"
        const val ACTION_STOP_STANDBY = "com.agoy507.mdremote.action.STOP_STANDBY"
        const val ACTION_UPDATE_STATUS = "com.agoy507.mdremote.action.UPDATE_STANDBY_STATUS"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context, initialText: String? = null) {
            val intent = Intent(context, RemoteStandbyService::class.java).apply {
                action = ACTION_START_STANDBY
                if (initialText != null) putExtra(EXTRA_STATUS_TEXT, initialText)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun updateStatus(context: Context, statusText: String) {
            val intent = Intent(context, RemoteStandbyService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteStandbyService::class.java).apply {
                action = ACTION_STOP_STANDBY
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
