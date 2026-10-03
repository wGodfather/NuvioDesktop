package com.nuvio.android

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.vpn.*
import com.wireguard.crypto.KeyPair
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on disposable emulators. No supplied private keys or public provider dependency. */
@RunWith(AndroidJUnit4::class)
class VpnAndroidIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun vpnStartsOffAndTvRemoteCanOpenMaskedProfileEditor() {
        assertFalse(VpnPlatform.controller().state.value.enabled)
        compose.activityRule.scenario.onActivity { it.setContent { NuvioTheme { SettingsScreen(initialPageName = "Vpn") } } }
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT < 29) {
            compose.onNodeWithText("VPN support is not available on this device yet.").assertExists()
            return
        }
        val row = compose.onNodeWithText("Enter a connection profile")
        row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        row.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("PrivateKey").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).assertCountEquals(2)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(context.getExternalFilesDir(null), "vpn-qa/profile-editor.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(VpnPlatform.controller().state.value.enabled)
    }

    @Test fun realTunWithoutSystemLockdownNeverAuthorizesTorrentAndProfileIsEncrypted() = runBlocking {
        val controller = VpnPlatform.controller()
        if (Build.VERSION.SDK_INT < 29) { assertFalse(controller.state.value.supported); return@runBlocking }
        val privateKey = KeyPair().privateKey.toBase64()
        val publicKey = KeyPair().publicKey.toBase64()
        controller.importProfileText("[Interface]\nPrivateKey = $privateKey\nAddress = 10.90.0.2/32\nDNS = 10.90.0.1\n" +
            "[Peer]\nPublicKey = $publicKey\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 192.0.2.1:51820\n")
        assertTrue(controller.state.value.profilePresent)
        val encrypted = File(context.noBackupFilesDir, "vpn/profile.gcm").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("A private key must never be stored in plaintext", encrypted.contains(privateKey))
        // Grant VPN app-op only in this disposable emulator. Real users use system consent.
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN allow").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        try {
            controller.setEnabled(true)
            assertEquals(VpnStatus.Blocked, controller.state.value.status)
            assertEquals("LOCKDOWN_REQUIRED", controller.state.value.errorCode)
            var executed = false
            try { controller.withTorrentPermission { executed = true } } catch (_: VpnRequiredException) { }
            assertFalse(executed)
            val networks = context.getSystemService(ConnectivityManager::class.java)
            assertTrue("The real WireGuard JNI guard TUN must exist", networks.allNetworks.any {
                networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            })
            controller.disconnect(); controller.disconnect() // exercise service replacement race
            assertEquals(VpnStatus.Blocked, controller.state.value.status)
        } finally {
            controller.setEnabled(false); controller.deleteProfile()
        }
        assertFalse(controller.state.value.enabled)
        assertFalse(controller.state.value.profilePresent)
    }
}
