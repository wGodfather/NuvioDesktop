package com.nuvio.app.features.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VpnStatus { Off, Ready, Connecting, Connected, Blocked, SetupRequired, Unsupported, Error }

data class VpnUiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val autoConnect: Boolean = false,
    val profilePresent: Boolean = false,
    val status: VpnStatus = VpnStatus.Off,
    val errorCode: String? = null,
    val busy: Boolean = false,
)

/** Native state is authoritative. A saved preference or a network adapter is not proof of protection. */
data class VpnProtection(val status: VpnStatus, val profilePresent: Boolean = false, val protected: Boolean = false)

interface VpnPreferences {
    fun enabled(): Boolean
    fun autoConnect(): Boolean
    fun saveEnabled(value: Boolean)
    fun saveAutoConnect(value: Boolean)
}

interface VpnBackend {
    val supported: Boolean
    suspend fun status(): VpnProtection
    suspend fun setup()
    suspend fun importProfile()
    suspend fun deleteProfile()
    suspend fun connect(): VpnProtection
    suspend fun arm(): VpnProtection
    suspend fun hold(): VpnProtection
    suspend fun release(): VpnProtection
    /** Stops upload/discovery as well as downloads, and verifies that the native motor exited. */
    suspend fun quiesceTorrentTraffic()
}

class VpnOperationException(val code: String) : Exception(code)
class VpnRequiredException : Exception("VPN_REQUIRED")

/** One device-wide policy, deliberately independent of Nuvio's media profiles. */
class VpnController(private val preferences: VpnPreferences, private val backend: VpnBackend) {
    private val gate = Mutex()
    private var wantsConnection = false
    private val mutableState = MutableStateFlow(
        VpnUiState(supported = backend.supported, enabled = preferences.enabled(),
            autoConnect = preferences.autoConnect(),
            status = if (preferences.enabled()) VpnStatus.Blocked else VpnStatus.Off),
    )
    val state: StateFlow<VpnUiState> = mutableState.asStateFlow()

    suspend fun initialize() {
        refresh()
        if (state.value.enabled && state.value.autoConnect && backend.supported) connect()
    }

    suspend fun refresh() = gate.withLock {
        try {
            apply(backend.status())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun setup() = operation { backend.setup(); apply(backend.status()) }

    suspend fun importProfile() = operation {
        // Replacing a profile must not disconnect a live motor under an old policy.
        backend.quiesceTorrentTraffic()
        if (state.value.enabled) apply(backend.hold())
        backend.importProfile()
        apply(backend.status())
    }

    suspend fun deleteProfile() = operation {
        backend.quiesceTorrentTraffic()
        if (state.value.enabled) apply(backend.hold())
        backend.deleteProfile()
        apply(backend.status())
    }

    suspend fun setEnabled(enabled: Boolean) = operation {
        if (enabled) {
            checkSupported()
            // Persist intent before any suspend point; cancellation must never fall back to cleartext.
            preferences.saveEnabled(true)
            wantsConnection = true
            mutableState.value = state.value.copy(enabled = true, status = VpnStatus.Connecting)
            try { apply(backend.arm()) } finally { backend.quiesceTorrentTraffic() }
            apply(backend.connect())
        } else {
            wantsConnection = false
            backend.quiesceTorrentTraffic()
            val result = backend.release()
            if (result.status != VpnStatus.Off || result.protected) throw VpnOperationException("RELEASE_FAILED")
            preferences.saveEnabled(false)
            mutableState.value = state.value.copy(enabled = false)
            apply(result)
        }
    }

    suspend fun connect() = operation {
        checkSupported()
        if (!state.value.enabled) throw VpnOperationException("ENABLE_FIRST")
        wantsConnection = true
        mutableState.value = state.value.copy(status = VpnStatus.Connecting)
        backend.quiesceTorrentTraffic()
        apply(backend.connect())
    }

    suspend fun disconnect() = operation {
        if (!state.value.enabled) return@operation
        wantsConnection = false
        backend.quiesceTorrentTraffic()
        apply(backend.hold())
    }

    suspend fun setAutoConnect(value: Boolean) = gate.withLock {
        preferences.saveAutoConnect(value)
        mutableState.value = state.value.copy(autoConnect = value)
    }

    /** Explicit disconnects stay disconnected; only a requested, interrupted connection is retried. */
    suspend fun shouldReconnect(): Boolean = gate.withLock {
        wantsConnection && state.value.enabled && state.value.profilePresent && state.value.status == VpnStatus.Blocked
    }

    /** Gate every native motor start AND magnet submission, even when the motor is already running. */
    suspend fun <T> withTorrentPermission(block: suspend () -> T): T = gate.withLock {
        if (preferences.enabled()) {
            val protection = try { backend.status() } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error)
                throw VpnRequiredException()
            }
            apply(protection)
            if (protection.status != VpnStatus.Connected || !protection.protected) throw VpnRequiredException()
        }
        block()
    }

    /** Normal exit releases the system-wide lock only after all torrent processes really stopped. */
    suspend fun shutdown() = operation {
        backend.quiesceTorrentTraffic()
        if (state.value.enabled && backend.supported) apply(backend.release())
        // Keep the user's intent; autoConnect=false means torrents wait for an explicit connection next time.
    }

    private fun checkSupported() {
        if (!backend.supported) throw VpnOperationException("UNSUPPORTED")
    }

    private suspend fun operation(block: suspend () -> Unit) = gate.withLock {
        mutableState.value = state.value.copy(busy = true, errorCode = null)
        try { block() } catch (error: CancellationException) {
            if (preferences.enabled()) mutableState.value = state.value.copy(status = VpnStatus.Blocked)
            throw error
        } catch (error: Exception) { fail(error) } finally {
            mutableState.value = state.value.copy(busy = false)
        }
    }

    private fun apply(result: VpnProtection) {
        // A crashed app or cleared media settings must not hide a surviving native lock.
        if (result.status in setOf(VpnStatus.Connected, VpnStatus.Connecting, VpnStatus.Blocked) && !preferences.enabled())
            preferences.saveEnabled(true)
        val enabled = preferences.enabled()
        val status = when {
            result.status == VpnStatus.Connected && !result.protected -> VpnStatus.Blocked
            enabled && result.status in setOf(VpnStatus.Off, VpnStatus.Ready) -> VpnStatus.Blocked
            else -> result.status
        }
        mutableState.value = state.value.copy(enabled = enabled, profilePresent = result.profilePresent,
            status = status, errorCode = null)
    }

    private fun fail(error: Exception) {
        // Do not forward exception messages: parser/driver errors may include private keys or endpoints.
        val code = (error as? VpnOperationException)?.code ?: "OPERATION_FAILED"
        mutableState.value = state.value.copy(enabled = preferences.enabled(),
            status = if (preferences.enabled()) VpnStatus.Blocked else VpnStatus.Error, errorCode = code)
    }
}

expect object VpnPlatform {
    fun controller(): VpnController
}
