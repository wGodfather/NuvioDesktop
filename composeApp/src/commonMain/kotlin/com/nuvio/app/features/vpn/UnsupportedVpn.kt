package com.nuvio.app.features.vpn

internal fun unsupportedVpnController() = VpnController(object : VpnPreferences {
    override fun enabled() = false
    override fun autoConnect() = false
    override fun saveEnabled(value: Boolean) = Unit
    override fun saveAutoConnect(value: Boolean) = Unit
}, object : VpnBackend {
    override val supported = false
    override suspend fun status() = VpnProtection(VpnStatus.Unsupported)
    override suspend fun setup() { throw VpnOperationException("UNSUPPORTED") }
    override suspend fun importProfile() { throw VpnOperationException("UNSUPPORTED") }
    override suspend fun deleteProfile() { throw VpnOperationException("UNSUPPORTED") }
    override suspend fun connect(): VpnProtection = throw VpnOperationException("UNSUPPORTED")
    override suspend fun arm(): VpnProtection = throw VpnOperationException("UNSUPPORTED")
    override suspend fun hold(): VpnProtection = throw VpnOperationException("UNSUPPORTED")
    override suspend fun release() = VpnProtection(VpnStatus.Off)
    override suspend fun quiesceTorrentTraffic() = Unit
})
