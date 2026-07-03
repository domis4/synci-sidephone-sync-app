package com.wnderlvst.sidephonesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SynciForegroundService : Service() {

    companion object {
        const val ACTION_STOP_SYNC = "com.wnderlvst.sidephonesync.STOP_SYNC"
        private const val CHANNEL_ID = "synci_server"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SYNC) {
            SynciRuntime.stopFromNotification(applicationContext)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification()
        )

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_synci)
            .setContentTitle("Synci is running")
            .setContentText(SynciRuntime.currentUrl() ?: "Sidephone sync server active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_stat_synci,
                "Stop Sync",
                stopSyncIntent()
            )
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopSyncIntent(): PendingIntent {
        val intent = Intent(this, SynciForegroundService::class.java).apply {
            action = ACTION_STOP_SYNC
        }

        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Synci Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the Synci sync server is running."
            setShowBadge(false)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
