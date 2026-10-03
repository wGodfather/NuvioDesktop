package com.nuvio.app.features.vpn

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Private same-UID IPC; messages contain commands/status, never profile keys. */
internal class AndroidVpnRemote(private val context: Context) {
    companion object { const val CONTROL = "com.nuvio.app.VPN_CONTROL" }
    private val mutex = Mutex()
    private val ids = AtomicInteger()
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<VpnProtection>>()
    @Volatile private var peer: Messenger? = null
    private var attached = false
    private var ready = CompletableDeferred<Unit>()
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        val pending = requests.remove(message.arg1)
        val error = message.data.getString("error")
        if (error != null) pending?.completeExceptionally(VpnOperationException(error))
        else pending?.complete(VpnProtection(VpnStatus.valueOf(message.data.getString("status") ?: "Blocked"),
            message.data.getBoolean("profile"), message.data.getBoolean("protected")))
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            peer = Messenger(binder); ready.complete(Unit)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            peer = null; ready = CompletableDeferred()
            requests.values.forEach { it.completeExceptionally(VpnOperationException("SERVICE_LOST")) }; requests.clear()
        }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) }
    }
    @Suppress("DEPRECATION")
    fun isRunning() = attached || context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == NuvioWireGuardService::class.java.name }

    suspend fun request(command: String): VpnProtection {
        ensureBound()
        val id = ids.incrementAndGet(); val result = CompletableDeferred<VpnProtection>()
        requests[id] = result
        try {
            withContext(Dispatchers.Main) {
                val message = Message.obtain(null, 1, id, 0).apply {
                    replyTo = reply; data = Bundle().apply { putString("command", command) }
                }
                (peer ?: throw VpnOperationException("SERVICE_LOST")).send(message)
            }
            return withTimeout(15000) { result.await() }
        } finally { requests.remove(id) }
    }
    private suspend fun ensureBound() = mutex.withLock {
        if (peer != null) return@withLock
        withContext(Dispatchers.Main) {
            val intent = Intent(context, NuvioWireGuardService::class.java).setAction(CONTROL)
            ContextCompat.startForegroundService(context, intent)
            if (!attached) {
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) throw VpnOperationException("SERVICE_LOST")
                attached = true
            }
        }
        withTimeout(5000) { ready.await() }
    }
    suspend fun unbind() = mutex.withLock {
        withContext(Dispatchers.Main) {
            if (attached) context.unbindService(connection)
            attached = false; peer = null; ready = CompletableDeferred()
        }
    }
}
