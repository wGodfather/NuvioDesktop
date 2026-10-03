package com.nuvio.app.features.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadHttpClient
import com.nuvio.app.features.p2p.P2pStreamingEngine
import java.util.IdentityHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual object VpnPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preferences: AndroidVpnPreferences? = null
    private var instance: VpnController? = null
    @Synchronized fun initialize(context: Context) {
        if (instance != null) return
        val prefs = AndroidVpnPreferences(context.applicationContext)
        val backend = AndroidVpnBackend(context.applicationContext, prefs)
        preferences = prefs
        instance = VpnController(prefs, backend)
        if (!backend.supported) return
        scope.launch {
            controller().initialize()
            var retryDelay = 5000L; var retryAt = 0L
            while (true) {
                delay(2000); controller().refresh()
                if (controller().state.value.status == VpnStatus.Connected) retryDelay = 5000L
                if (controller().shouldReconnect() && android.os.SystemClock.elapsedRealtime() >= retryAt) {
                    controller().connect()
                    retryAt = android.os.SystemClock.elapsedRealtime() + retryDelay
                    retryDelay = (retryDelay * 2).coerceAtMost(60000)
                }
            }
        }
    }
    actual fun controller(): VpnController = checkNotNull(instance) { "VPN policy must be initialized before networking" }
    internal fun enabled() = preferences?.enabled() ?: false
}

internal class AndroidVpnPreferences(context: Context) : VpnPreferences {
    private val prefs = context.getSharedPreferences("vpn-device", Context.MODE_PRIVATE)
    override fun enabled() = prefs.getBoolean("enabled", false)
    override fun autoConnect() = prefs.getBoolean("auto-connect", false)
    override fun saveEnabled(value: Boolean) { if (!prefs.edit().putBoolean("enabled", value).commit()) throw VpnOperationException("PREFERENCE_FAILED") }
    override fun saveAutoConnect(value: Boolean) { if (!prefs.edit().putBoolean("auto-connect", value).commit()) throw VpnOperationException("PREFERENCE_FAILED") }
}

/** Playback and background-download JNI engines stay tracked until shutdown succeeds. */
internal object AndroidVpnTraffic {
    private class Motor(val close: suspend () -> Unit) { val mutex = Mutex(); var stopped = false }
    private val motors = IdentityHashMap<Any, Motor>()
    private val shutdown = Mutex()
    fun register(key: Any, stop: suspend () -> Unit) = synchronized(motors) { motors[key] = Motor(stop) }
    private fun remove(key: Any) = synchronized(motors) { motors.remove(key); Unit }
    suspend fun stop(key: Any) {
        val motor = synchronized(motors) { motors[key] } ?: return
        motor.mutex.withLock {
            if (!motor.stopped) { withTimeout(30000) { motor.close() }; motor.stopped = true; remove(key) }
        }
    }
    suspend fun stopAll() = shutdown.withLock {
        val snapshot = synchronized(motors) { motors.keys.toList() }
        var failed = false
        for (key in snapshot) { try { stop(key) } catch (_: Exception) { failed = true } }
        if (failed) throw VpnOperationException("TORRENT_STILL_RUNNING")
    }
}

internal class AndroidVpnBackend(private val context: Context, private val prefs: AndroidVpnPreferences) : VpnBackend {
    override val supported = Build.VERSION.SDK_INT >= 29 && com.nuvio.app.core.build.AppFeaturePolicy.downloadForegroundServiceEnabled
    override val supportsTextImport = true
    override val requiresSystemLockdown = true
    private val store by lazy { AndroidVpnProfileStore(context) }
    private val remote by lazy { AndroidVpnRemote(context) }
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }
    override suspend fun status(): VpnProtection = withContext(Dispatchers.IO) {
        val present = store.exists()
        if (!supported) return@withContext VpnProtection(VpnStatus.Unsupported, present)
        if (VpnService.prepare(context) != null) return@withContext VpnProtection(VpnStatus.SetupRequired, present)
        if (!prefs.enabled() && !remote.isRunning()) return@withContext VpnProtection(VpnStatus.Off, present)
        val result = remote.request("status")
        val network = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        // Android lockdown exempts the VPN app UID. Bind this process explicitly to
        // the VPN netId; never fall back to a physical network if that netId disappears.
        // WireGuard sockets belong to a separate process and remain unaffected.
        val bound = network != null && connectivity.bindProcessToNetwork(network) && connectivity.boundNetworkForProcess == network
        if (result.status == VpnStatus.Connected && !bound) VpnProtection(VpnStatus.Blocked, present)
        else result.copy(profilePresent = present, protected = result.protected && bound)
    }
    override suspend fun setup() { if (!supported) throw VpnOperationException("ANDROID_VERSION_REQUIRED"); AndroidVpnUi.preparePermission() }
    override suspend fun openSystemSettings() { AndroidVpnUi.openSettings() }
    override suspend fun importProfile() {
        val uri = AndroidVpnUi.selectProfile() ?: return
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(AndroidVpnProfile.MAX_BYTES) }
                ?: throw VpnOperationException("INVALID_PROFILE")
            try { importProfileText(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        }
    }
    override suspend fun importProfileText(text: String) = withContext(Dispatchers.IO) { store.save(AndroidVpnProfile.parse(text)) }
    override suspend fun deleteProfile() = withContext(Dispatchers.IO) { store.delete() }
    override suspend fun arm(): VpnProtection { quiesceTorrentTraffic(); return hold() }
    override suspend fun hold(): VpnProtection {
        requirePermission()
        remote.request("hold")
        return status()
    }
    override suspend fun connect(): VpnProtection {
        if (!store.exists()) throw VpnOperationException("PROFILE_REQUIRED")
        requirePermission(); remote.request("connect"); return status()
    }
    override suspend fun release(): VpnProtection {
        // Only remove explicit process binding after native engines and HTTP jobs
        // have closed, and the service confirms OS always-on/lockdown is disabled.
        if (remote.isRunning()) remote.request("release")
        if (!connectivity.bindProcessToNetwork(null)) throw VpnOperationException("RELEASE_FAILED")
        remote.unbind()
        return VpnProtection(VpnStatus.Off, store.exists())
    }
    override suspend fun quiesceTorrentTraffic() {
        withContext(Dispatchers.Main) { DownloadsRepository.pauseActiveDownloads(); DownloadsPlatformDownloader.pauseForVpnTransition() }
        withContext(Dispatchers.IO) {
            P2pStreamingEngine.stopForVpnTransition()
            AndroidVpnTraffic.stopAll()
            DownloadsPlatformDownloader.awaitVpnTransition()
            downloadHttpClient.connectionPool.evictAll()
        }
    }
    private fun requirePermission() {
        if (!supported) throw VpnOperationException("ANDROID_VERSION_REQUIRED")
        if (VpnService.prepare(context) != null) throw VpnOperationException("VPN_PERMISSION_REQUIRED")
    }
}

internal fun isRecentAndroidVpnHandshake(timestamp: Long, now: Long): Boolean = timestamp > 0 && now - timestamp in -5000..180000
