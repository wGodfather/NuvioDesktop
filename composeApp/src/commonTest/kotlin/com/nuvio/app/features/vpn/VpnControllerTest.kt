package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class VpnControllerTest {
    private class Preferences : VpnPreferences {
        var enabledValue = false
        var auto = false
        override fun enabled() = enabledValue
        override fun autoConnect() = auto
        override fun saveEnabled(value: Boolean) { enabledValue = value }
        override fun saveAutoConnect(value: Boolean) { auto = value }
    }
    private class Backend : VpnBackend {
        override var supported = true
        var protection = VpnProtection(VpnStatus.Off, profilePresent = true)
        var exception: Exception? = null
        var releaseFails = false
        val events = mutableListOf<String>()
        var quiesceEntered: CompletableDeferred<Unit>? = null
        var quiesceContinue: CompletableDeferred<Unit>? = null
        override suspend fun status(): VpnProtection { exception?.let { throw it }; return protection }
        override suspend fun setup() { events += "setup" }
        override suspend fun importProfile() { events += "import" }
        override suspend fun importProfileText(text: String) { events += "import-text" }
        override suspend fun deleteProfile() { events += "delete"; protection = protection.copy(profilePresent = false) }
        override suspend fun connect(): VpnProtection {
            events += "connect"; exception?.let { throw it }
            protection = VpnProtection(VpnStatus.Connected, true, true); return protection
        }
        override suspend fun hold(): VpnProtection { events += "hold"; protection = VpnProtection(VpnStatus.Blocked, true); return protection }
        override suspend fun arm(): VpnProtection { events += "arm"; protection = VpnProtection(VpnStatus.Blocked, true); return protection }
        override suspend fun release(): VpnProtection {
            events += "release"; if (releaseFails) throw VpnOperationException("RELEASE_FAILED")
            protection = VpnProtection(VpnStatus.Off, true); return protection
        }
        override suspend fun quiesceTorrentTraffic() {
            events += "stop"; quiesceEntered?.complete(Unit); quiesceContinue?.await()
        }
    }

    @Test fun vpnOffDoesNotRequireAServiceOrProfile() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { exception = VpnOperationException("SETUP_REQUIRED") }
        val controller = VpnController(prefs, backend)
        assertEquals(7, controller.withTorrentPermission { 7 })
        assertFalse(controller.state.value.enabled)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun enablingStopsExistingSocketsBeforeConnecting() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true)
        assertEquals(listOf("arm", "stop", "connect"), backend.events)
        assertTrue(prefs.enabledValue)
        assertEquals(7, controller.withTorrentPermission { 7 })
    }

    @Test fun failedConnectionKeepsIntentAndRejectsTorrentStarts() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { exception = VpnOperationException("TUNNEL_FAILED") }
        val controller = VpnController(prefs, backend)
        controller.setEnabled(true)
        assertTrue(prefs.enabledValue)
        assertEquals(VpnStatus.Blocked, controller.state.value.status)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission { error("must not execute") } }
    }

    @Test fun disconnectKeepsProtectionAndSavedPreference() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.events.clear(); controller.disconnect()
        assertEquals(listOf("stop", "hold"), backend.events)
        assertTrue(prefs.enabledValue)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission {} }
    }

    @Test fun disablingCannotRemoveIntentWhenReleaseFails() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.events.clear(); backend.releaseFails = true; controller.setEnabled(false)
        assertEquals(listOf("stop", "release"), backend.events)
        assertTrue(prefs.enabledValue)
        assertEquals("RELEASE_FAILED", controller.state.value.errorCode)
    }

    @Test fun adapterOrUnprotectedConnectedLabelDoesNotAuthorizeTraffic() = runBlocking<Unit> {
        val prefs = Preferences().apply { enabledValue = true }; val backend = Backend()
        backend.protection = VpnProtection(VpnStatus.Connected, true, false)
        val controller = VpnController(prefs, backend)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission {} }
        assertEquals(VpnStatus.Blocked, controller.state.value.status)
    }

    @Test fun deletingProfileNeverTurnsProtectionOff() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.events.clear(); controller.deleteProfile()
        assertEquals(listOf("stop", "hold", "delete"), backend.events)
        assertTrue(prefs.enabledValue); assertFalse(controller.state.value.profilePresent)
    }

    @Test fun exitStopsMotorsBeforeReleaseAndRemembersUserIntent() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.events.clear(); controller.shutdown()
        assertEquals(listOf("stop", "release"), backend.events)
        assertTrue(prefs.enabledValue)
        assertEquals(VpnStatus.Blocked, controller.state.value.status)
    }

    @Test fun disablingCannotRaceANewTorrentStart() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true)
        backend.quiesceEntered = CompletableDeferred(); backend.quiesceContinue = CompletableDeferred()
        val disable = async { controller.setEnabled(false) }; backend.quiesceEntered!!.await()
        val torrent = async { controller.withTorrentPermission { backend.events += "torrent" } }
        assertFalse(torrent.isCompleted)
        backend.quiesceContinue!!.complete(Unit); disable.await(); torrent.await()
        assertTrue(backend.events.indexOfLast { it == "release" } < backend.events.indexOf("torrent"))
    }

    @Test fun arbitraryFailureDetailsAreNotDisplayedOrSaved() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { exception = Exception("PrivateKey=do-not-publish") }
        val controller = VpnController(prefs, backend); controller.setEnabled(true)
        assertEquals("OPERATION_FAILED", controller.state.value.errorCode)
        assertFalse(controller.state.value.toString().contains("PrivateKey"))
    }

    @Test fun cancellationDuringConnectDoesNotAllowCleartextFallback() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { exception = CancellationException("cancel") }
        val controller = VpnController(prefs, backend)
        assertFailsWith<CancellationException> { controller.setEnabled(true) }
        assertTrue(prefs.enabledValue); assertFalse(controller.state.value.busy)
        assertEquals(VpnStatus.Blocked, controller.state.value.status)
    }

    @Test fun unsupportedPlatformCannotBeEnabled() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { supported = false }; val controller = VpnController(prefs, backend)
        controller.setEnabled(true)
        assertFalse(prefs.enabledValue); assertEquals("UNSUPPORTED", controller.state.value.errorCode)
    }

    @Test fun restartDoesNotConnectWithoutTheAutoConnectPreference() = runBlocking<Unit> {
        val prefs = Preferences().apply { enabledValue = true }; val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.initialize()
        assertTrue(backend.events.isEmpty()); assertEquals(VpnStatus.Blocked, controller.state.value.status)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission {} }
    }

    @Test fun survivingNativeLockIsVisibleAfterPreferencesWereCleared() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend().apply { protection = VpnProtection(VpnStatus.Blocked, true) }
        val controller = VpnController(prefs, backend); controller.refresh()
        assertTrue(prefs.enabledValue); assertTrue(controller.state.value.enabled)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission {} }
    }

    @Test fun explicitDisconnectNeverTriggersAutomaticReconnect() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.protection = VpnProtection(VpnStatus.Blocked, true); controller.refresh()
        assertTrue(controller.shouldReconnect())
        controller.disconnect(); assertFalse(controller.shouldReconnect())
    }

    @Test fun lostProtectionStopsPreviouslyAuthorizedMotorsEvenAfterARejectedStart() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); controller.withTorrentPermission { }; backend.events.clear()
        backend.protection = VpnProtection(VpnStatus.Blocked, true)
        assertFailsWith<VpnRequiredException> { controller.withTorrentPermission { } }
        controller.refresh()
        assertEquals(listOf("stop"), backend.events)
    }

    @Test fun nativeStatusFailureStopsAuthorizedTrafficAndRedactsDetails() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); controller.withTorrentPermission { }; backend.events.clear()
        backend.exception = Exception("secret profile")
        controller.refresh()
        assertEquals(listOf("stop"), backend.events)
        assertEquals(VpnStatus.Blocked, controller.state.value.status)
        assertEquals("OPERATION_FAILED", controller.state.value.errorCode)
    }

    @Test fun textImportStopsMotorsAndHoldsBeforeReplacingProfile() = runBlocking<Unit> {
        val prefs = Preferences(); val backend = Backend(); val controller = VpnController(prefs, backend)
        controller.setEnabled(true); backend.events.clear(); controller.importProfileText("private profile")
        assertEquals(listOf("stop", "hold", "import-text"), backend.events)
        assertTrue(prefs.enabledValue)
    }
}
