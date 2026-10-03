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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nuvio.app.MainActivity
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.vpn.*
import com.wireguard.crypto.KeyPair
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test fun systemVpnConsentWorksWithRemoteAndTvLauncherResolves() = runBlocking {
        assertFalse(VpnPlatform.controller().state.value.enabled)
        if (Build.VERSION.SDK_INT < 29) { assertFalse(VpnPlatform.controller().state.value.supported); return@runBlocking }
        val manager = context.packageManager
        val tv = manager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (tv) {
            val launcher = manager.getLeanbackLaunchIntentForPackage(context.packageName)
            assertNotNull("TV must expose a real leanback launcher", launcher)
            assertEquals("com.nuvio.app.launcher.NuvioTvActivity", launcher!!.component!!.className)
            assertNotEquals(0, manager.getActivityInfo(launcher.component!!, 0).banner)
        }
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN ignore").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val setup = async { VpnPlatform.controller().setup() }
        val accepted = withContext(Dispatchers.IO) {
            val device = UiDevice.getInstance(instrumentation)
            if (!device.wait(Until.hasObject(By.res("android", "button1")), 15_000)) return@withContext false
            repeat(8) {
                val button = device.findObject(By.res("android", "button1"))
                if (button?.isFocused == true) { device.pressDPadCenter(); return@withContext true }
                if (it % 2 == 0) device.pressDPadRight() else device.pressDPadDown()
            }
            false
        }
        assertTrue("System VPN consent must be reachable and accepted using D-pad", accepted)
        withTimeout(15_000) { setup.await() }
        assertNull(android.net.VpnService.prepare(context))
        assertFalse("Granting consent must not turn VPN on", VpnPlatform.controller().state.value.enabled)
    }

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

    @Test fun realTunWithoutHandshakeNeverAuthorizesTorrentAndProfileIsEncrypted() = runBlocking {
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
            assertTrue(controller.state.value.status in setOf(VpnStatus.Blocked, VpnStatus.Connecting))
            assertNotNull("Main process must be pinned to the VPN network", context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
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
