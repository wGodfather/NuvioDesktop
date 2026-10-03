package com.nuvio.app.features.vpn

import com.nuvio.app.features.downloads.shouldBindAndroidDownloadNetwork
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AndroidVpnTrafficTest {
    @Test fun closesIndependentPlaybackAndDownloadEngines() = runBlocking {
        val player = Any(); val download = Any(); val closed = mutableSetOf<Any>()
        AndroidVpnTraffic.register(player) { closed += player }
        AndroidVpnTraffic.register(download) { closed += download }
        AndroidVpnTraffic.stopAll()
        assertEquals(setOf(player, download), closed)
    }
    @Test fun failedShutdownRetainsEngineAndStillStopsOtherEngines() = runBlocking {
        val failed = Any(); val other = Any(); var fail = true; var otherClosed = false
        AndroidVpnTraffic.register(failed) { if (fail) error("secret") }
        AndroidVpnTraffic.register(other) { otherClosed = true }
        assertEquals("TORRENT_STILL_RUNNING", assertFailsWith<VpnOperationException> { AndroidVpnTraffic.stopAll() }.code)
        assertTrue(otherClosed)
        fail = false; AndroidVpnTraffic.stopAll()
    }
    @Test fun schedulerCloseAndVpnStopCannotShutdownSameEngineTwice() = runBlocking {
        val engine = Any(); var closes = 0
        AndroidVpnTraffic.register(engine) { delay(10); closes++ }
        val close = async { AndroidVpnTraffic.stop(engine) }
        AndroidVpnTraffic.stopAll(); close.await()
        assertEquals(1, closes)
    }
    @Test fun physicalJobNetworkIsNeverBoundWhileVpnIsEnabled() {
        assertTrue(shouldBindAndroidDownloadNetwork(true, false, false))
        for (torrent in listOf(false, true)) assertFalse(shouldBindAndroidDownloadNetwork(true, torrent, true))
        assertFalse(shouldBindAndroidDownloadNetwork(true, true, false))
        assertFalse(shouldBindAndroidDownloadNetwork(false, false, false))
    }
    @Test fun staleOrFutureHandshakeCannotAuthorizeTraffic() {
        val now = 1_000_000L
        assertTrue(isRecentAndroidVpnHandshake(now, now))
        assertFalse(isRecentAndroidVpnHandshake(0, now))
        assertFalse(isRecentAndroidVpnHandshake(now - 180001, now))
        assertFalse(isRecentAndroidVpnHandshake(now + 5001, now))
    }
}
