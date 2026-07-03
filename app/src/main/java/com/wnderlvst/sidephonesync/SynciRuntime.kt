package com.wnderlvst.sidephonesync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

object SynciRuntime {
    private var server: SyncServer? = null

    var requestMediaDelete: ((List<Uri>) -> Unit)? = null

    fun start(context: Context) {
        if (server == null) {
            server = SyncServer(
                context = context.applicationContext,
                requestMediaDelete = { uris ->
                    requestMediaDelete?.invoke(uris)
                }
            ).also {
                it.start()
            }
        }

        startForegroundNotification(context.applicationContext)
    }

    fun stop(context: Context? = null) {
        server?.stop()
        server = null

        if (context != null) {
            val intent = Intent(context.applicationContext, SynciForegroundService::class.java)
            context.applicationContext.stopService(intent)
        }
    }

    fun stopFromNotification(context: Context) {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean {
        return server != null
    }

    fun currentUrl(): String? {
        val ip = SyncServer.getLocalIpAddress()
        return if (ip == null) null else "http://$ip:8080"
    }

    private fun startForegroundNotification(context: Context) {
        val intent = Intent(context, SynciForegroundService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}