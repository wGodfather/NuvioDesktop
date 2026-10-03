package com.nuvio.android

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.vpn.*
import com.wireguard.crypto.KeyPair
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ephemeral local peer on disposable CI only. No provider/user keys are used. */
@RunWith(AndroidJUnit4::class)
class VpnPeerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun encryptedPeerDnsIpv6NativePlayerAndDownloadStayInsideVpn() = runBlocking {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val controller = VpnPlatform.controller()
            assertFalse(controller.state.value.enabled)
            assertEquals("ready", http("http://10.0.2.2:8765/ready"))
            val keys = KeyPair()
            val enrolled = JSONObject(http("http://10.0.2.2:8765/enroll", keys.publicKey.toBase64()))
            val fixture = enrolled.getJSONObject("fixture")
            val hash = fixture.getString("sha256")
            val bytes = fixture.getLong("bytes")
            val tracker = fixture.getString("tracker_url")
            controller.importProfileText("[Interface]\nPrivateKey = ${keys.privateKey.toBase64()}\nAddress = 10.90.0.2/32, fd90::2/128\nDNS = 10.90.0.1\n" +
                "[Peer]\nPublicKey = ${enrolled.getString("publicKey")}\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 10.0.2.2:${enrolled.getInt("port")}\n")
            shell("appops set ${context.packageName} ACTIVATE_VPN allow")
            try {
                controller.setEnabled(true)
                controller.connect()
                awaitConnected(controller)
                val network = context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess
                assertNotNull("Native process must have a VPN network binding", network)
                assertEquals("10.90.0.2", JSONObject(http("http://10.90.0.1:8765/probe")).getString("source"))
                assertEquals("fd90::2", JSONObject(http("http://[fd90::1]:8765/probe")).getString("source"))
                val resolved = withContext(Dispatchers.IO) { InetAddress.getAllByName("vpn-fixture.test").map { it.hostAddress } }
                assertTrue("Fixture DNS must resolve through the peer", resolved.any { it == "10.90.0.1" })
                assertTrue(JSONObject(http("http://vpn-fixture.test:8765/probe")).getString("source") in setOf("10.90.0.2", "fd90::2"))
                assertPhysicalControlBlocked()

                // Separate JNI sessions, started together: UI player and persistent downloader.
                scenario.onActivity {
                    DownloadsRepository.enqueueFromStream(
                        contentType = "movie", videoId = "vpn-native-fixture", parentMetaId = "vpn-native-fixture", parentMetaType = "movie",
                        title = "VPN QA fixture", logo = null, poster = null, background = null, seasonNumber = null,
                        episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                        stream = StreamItem(url = fixture.getString("magnet"), fileIdx = 0, addonName = "Local VPN QA", addonId = "local.vpn.qa",
                            behaviorHints = StreamBehaviorHints(filename = "fixture.mp4")),
                    )
                }
                val localUrl = withTimeout(120_000) {
                    P2pStreamingEngine.startStream(P2pStreamRequest(fixture.getString("info_hash"), 0, "fixture.mp4", listOf(tracker)))
                }
                val played = withContext(Dispatchers.IO) {
                    val connection = URL(localUrl).openConnection().apply { connectTimeout = 10_000; readTimeout = 60_000 }
                    connection.getInputStream().use { it.readBytes() }
                }
                assertEquals(bytes, played.size.toLong())
                assertEquals(hash, sha256(played))
                withTimeout(120_000) {
                    while (DownloadsRepository.uiState.value.items.none { it.videoId == "vpn-native-fixture" &&
                            it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) }) delay(200)
                }
                val downloaded = DownloadsRepository.uiState.value.items.single { it.videoId == "vpn-native-fixture" }
                assertEquals(downloaded.errorMessage, DownloadStatus.Completed, downloaded.status)
                val stored = withContext(Dispatchers.IO) { File(URI(requireNotNull(downloaded.localFileUri))).readBytes() }
                assertEquals(bytes, stored.size.toLong()); assertEquals(hash, sha256(stored))

                repeat(10) {
                    controller.disconnect()
                    assertEquals(VpnStatus.Blocked, controller.state.value.status)
                    assertPhysicalControlBlocked()
                    var ran = false
                    try { controller.withTorrentPermission { ran = true } } catch (_: VpnRequiredException) { }
                    assertFalse("Hold must reject new native work", ran)
                    controller.connect(); awaitConnected(controller)
                    assertEquals("10.90.0.2", JSONObject(http("http://10.90.0.1:8765/probe")).getString("source"))
                }
                // Service death must never clear the main process netId to a physical default.
                val serviceProcess = "${context.packageName}:nuvio_vpn"
                val servicePid = shell("pidof $serviceProcess").trim().toInt()
                assertTrue(servicePid > 0 && servicePid != android.os.Process.myPid())
                shell("am crash $servicePid")
                withTimeout(10_000) {
                    while (shell("pidof $serviceProcess").trim().split(' ').any { it == servicePid.toString() }) delay(100)
                }
                assertPhysicalControlBlocked()
                assertNotNull(context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
                controller.connect(); awaitConnected(controller)
                val soakSeconds = InstrumentationRegistry.getArguments().getString("vpnSoakSeconds")?.toInt() ?: 0
                repeat(soakSeconds / 10) {
                    delay(10_000); controller.refresh(); assertEquals(VpnStatus.Connected, controller.state.value.status)
                    assertEquals("10.90.0.2", JSONObject(http("http://10.90.0.1:8765/probe")).getString("source"))
                }
            } finally {
                P2pStreamingEngine.stopStream()
                controller.setEnabled(false); controller.deleteProfile()
            }
            assertEquals("ready", http("http://10.0.2.2:8765/ready"))
        }
    }

    private suspend fun awaitConnected(controller: VpnController) = withTimeout(60_000) {
        while (true) { controller.refresh(); if (controller.state.value.status == VpnStatus.Connected) break; delay(500) }
    }
    private suspend fun assertPhysicalControlBlocked() {
        val result = runCatching { http("http://10.0.2.2:8765/ready") }
        assertTrue("Physical host listener must be unreachable from the protected process", result.isFailure)
    }
    private suspend fun http(url: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2500; connection.readTimeout = 5000
        try {
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
