package com.wnderlvst.sidephonesync

import android.content.Context
import android.net.Uri

object SynciRuntime {
    private var server: SyncServer? = null

    var requestMediaDelete: ((List<Uri>) -> Unit)? = null

    fun start(context: Context) {
        if (server != null) return

        server = SyncServer(
            context = context.applicationContext,
            requestMediaDelete = { uris ->
                requestMediaDelete?.invoke(uris)
            }
        ).also {
            it.start()
        }
    }

    fun stop() {
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
}
