package com.nuvio.app.features.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.nuvio.app.MainActivity
import com.nuvio.app.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Runs in :nuvio_vpn, separate from the process pinned to the VPN network. */
class NuvioWireGuardService : GoBackend.VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime by lazy { AndroidVpnTunnelRuntime(this) }
    private val commands = Messenger(Handler(Looper.getMainLooper()) { request ->
        // Handler recycles Message after this callback; copy fields before dispatching.
        val recipient = request.replyTo
        val id = request.arg1
        val command = request.data.getString("command") ?: ""
        if (request.sendingUid == Process.myUid() && request.what == 1 && recipient != null) scope.launch {
            val response = Message.obtain(null, 1, id, 0)
            response.data = Bundle().apply {
                try {
                    val result = runtime.command(command)
                    putString("status", result.status.name); putBoolean("profile", result.profilePresent); putBoolean("protected", result.protected)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { putString("error", (error as? VpnOperationException)?.code ?: "OPERATION_FAILED") }
            }
            try { recipient.send(response) } catch (_: RemoteException) { }
        }
        true
    })
    override fun onCreate() {
        super.onCreate()
        GoBackend.setAlwaysOnCallback { scope.launch {
            runCatching {
                runtime.command("hold")
                if (AndroidVpnPreferences(this@NuvioWireGuardService).autoConnect()) runtime.command("connect")
            }
        } }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("nuvio-vpn", getString(R.string.vpn_android_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "nuvio-vpn")
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(getString(R.string.vpn_android_title))
            .setContentText(getString(R.string.vpn_android_notification)).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(41030, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(41030, notification)
    }
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == AndroidVpnRemote.CONTROL) commands.binder else super.onBind(intent)
    override fun onRevoke() {
        runtime.markLost()
        scope.launch { runCatching { runtime.command("revoke") } }
        super.onRevoke()
    }
    override fun onDestroy() { runtime.markLost(); super.onDestroy(); scope.cancel() }
}

/** Only this service process creates or protects physical WireGuard sockets. */
private class AndroidVpnTunnelRuntime(private val service: NuvioWireGuardService) {
    private val store by lazy { AndroidVpnProfileStore(service) }
    private val native by lazy { GoBackend(service) }
    private val mutex = Mutex()
    private val forwarding = AtomicBoolean(false)
    private var startedAt = 0L
    private val tunnel = object : Tunnel {
        override fun getName() = "NuvioVpn"
        override fun onStateChange(newState: Tunnel.State) { if (newState == Tunnel.State.DOWN) forwarding.set(false) }
    }
    fun markLost() { forwarding.set(false) }
    suspend fun command(command: String): VpnProtection = mutex.withLock {
        when (command) {
            "status" -> status()
            "hold" -> {
                forwarding.set(false)
                if (store.exists()) native.setState(tunnel, Tunnel.State.UP, AndroidVpnProfile.guard(store.load()))
                VpnProtection(VpnStatus.Blocked, store.exists())
            }
            "connect" -> {
                if (!store.exists()) throw VpnOperationException("PROFILE_REQUIRED")
                forwarding.set(false)
                native.setState(tunnel, Tunnel.State.UP, store.load())
                forwarding.set(true); startedAt = SystemClock.elapsedRealtime(); status()
            }
            "release", "revoke" -> {
                if (command == "release" && (service.isAlwaysOn || service.isLockdownEnabled)) throw VpnOperationException("SYSTEM_VPN_CONTROLS")
                forwarding.set(false)
                native.setState(tunnel, Tunnel.State.DOWN, null)
                VpnProtection(VpnStatus.Off, store.exists())
            }
            else -> throw VpnOperationException("INVALID_COMMAND")
        }
    }
    private fun status(): VpnProtection {
        val present = store.exists()
        if (!present || !forwarding.get() || native.getState(tunnel) != Tunnel.State.UP)
            return VpnProtection(VpnStatus.Blocked, present)
        val config = store.load()
        val timestamp = native.getStatistics(tunnel).peer(config.peers.single().publicKey)?.latestHandshakeEpochMillis() ?: 0
        val verified = isRecentAndroidVpnHandshake(timestamp, System.currentTimeMillis())
        return VpnProtection(if (verified) VpnStatus.Connected else if (SystemClock.elapsedRealtime() - startedAt < 30000)
            VpnStatus.Connecting else VpnStatus.Blocked, present, verified)
    }
}
