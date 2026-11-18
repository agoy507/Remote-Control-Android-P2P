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
import androidx.core.app.NotificationCompat
import com.agoy507.mdremote.MainActivity
import com.agoy507.mdremote.R

class RemoteHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_HOST -> {
                RemoteSessionCoordinator.getInstance(applicationContext).stopHostMode()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_HOST -> {
                val notification = buildNotification(getString(R.string.notification_host_waiting))
                startForegroundCompat(notification)
                val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_INTENT)
                }
                if (projectionIntent != null) {
                    RemoteSessionCoordinator.getInstance(applicationContext).onHostServiceReady(projectionIntent)
                } else {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val text = intent.getStringExtra(EXTRA_STATUS_TEXT)
                    ?: getString(R.string.notification_host_waiting)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
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
                getString(R.string.notification_channel_host),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_host_desc)
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
            Intent(this, RemoteHostService::class.java).apply { action = ACTION_STOP_HOST },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_host_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.action_stop_host), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kendalip2p_host_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START_HOST = "com.agoy507.mdremote.action.START_HOST"
        const val ACTION_STOP_HOST = "com.agoy507.mdremote.action.STOP_HOST"
        const val ACTION_UPDATE_NOTIFICATION = "com.agoy507.mdremote.action.UPDATE_NOTIFICATION"
        const val EXTRA_PROJECTION_INTENT = "extra_projection_intent"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context, projectionDataIntent: Intent) {
            val intent = Intent(context, RemoteHostService::class.java).apply {
                action = ACTION_START_HOST
                putExtra(EXTRA_PROJECTION_INTENT, projectionDataIntent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateStatus(context: Context, statusText: String) {
            val intent = Intent(context, RemoteHostService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteHostService::class.java).apply {
                action = ACTION_STOP_HOST
            }
            context.startService(intent)
        }
    }
}
