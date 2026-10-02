package com.nuvio.app.features.vpn

actual object VpnPlatform {
    private val instance = unsupportedVpnController()
    actual fun controller(): VpnController = instance
}
