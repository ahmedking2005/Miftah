package com.ahmed.miftah.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R

/** Foreground service hosting the local OpenAI-compatible server. Off by default; user toggles it in Settings. */
class ServerService : Service() {
    private var server: LocalServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MiftahApp
        val port = app.settings.serverPort
        startForeground(NOTIF_ID, buildNotification(port))
        if (server == null) {
            server = LocalServer(
                port = port,
                router = app.router,
                unifiedKey = { app.settings.unifiedKey(app.vault) },
                chainModels = { LocalServer.chainModelPairs(app.settings.chain) }
            ).apply { start() }
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.server_notification, port))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .build()
    }

    companion object {
        private const val CHANNEL = "miftah_server"
        private const val NOTIF_ID = 41
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
