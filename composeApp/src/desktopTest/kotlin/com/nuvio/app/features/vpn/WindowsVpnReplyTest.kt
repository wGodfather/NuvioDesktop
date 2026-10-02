package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsVpnReplyTest {
    @Test fun nativeProtectionMustBeExplicit() {
        val status = parseNativeVpnReply("STATE\tConnected\t1\t1")
        assertEquals(VpnStatus.Connected, status.status); assertTrue(status.protected)
    }
    @Test fun malformedRepliesNeverBecomeAConnectedState() {
        for (reply in listOf("Connected", "STATE\tConnected\t1", "STATE\tConnected\t1\tyes",
            "STATE\tReady\t1\t1", "STATE\tConnected\t1\t1\textra", "STATE\tOff\t9\t0")) {
            assertFailsWith<VpnOperationException> { parseNativeVpnReply(reply) }
        }
    }
}
