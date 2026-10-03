package com.nuvio.app.features.vpn

import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidVpnProfileTest {
    private fun profile(routes: String = "0.0.0.0/0, ::/0", endpoint: String = "192.0.2.1:51820", dns: String = "1.1.1.1") =
        "[Interface]\nPrivateKey = ${KeyPair().privateKey.toBase64()}\nAddress = 10.90.0.2/32\nDNS = $dns\n" +
            "[Peer]\nPublicKey = ${KeyPair().publicKey.toBase64()}\nAllowedIPs = $routes\nEndpoint = $endpoint\n"

    @Test fun fullTunnelPreservesAddressesAndForcesKeepalive() {
        val config = AndroidVpnProfile.parse(profile())
        assertEquals("10.90.0.2/32", config.`interface`.addresses.single().toString())
        assertEquals(25, config.peers.single().persistentKeepalive.get())
    }
    @Test fun ipv4OnlyIsAllowedWithoutOptingIntoIpv6Bypass() {
        assertEquals(setOf("0.0.0.0/0"), AndroidVpnProfile.parse(profile(routes = "0.0.0.0/0")).peers.single().allowedIps.map { it.toString() }.toSet())
    }
    @Test fun splitRoutesAreRejected() {
        for (route in listOf("10.0.0.0/8", "::/0", "0.0.0.0/0, 10.0.0.0/8"))
            assertEquals("FULL_TUNNEL_REQUIRED", assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(profile(routes = route)) }.code)
    }
    @Test fun hostnamesAndScopedAddressesCannotTriggerResolver() {
        for (endpoint in listOf("vpn.example:51820", "[fe80::1%wlan0]:51820"))
            assertEquals("LITERAL_IP_REQUIRED", assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(profile(endpoint = endpoint)) }.code)
        assertEquals("LITERAL_IP_REQUIRED", assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(profile(dns = "private.example")) }.code)
    }
    @Test fun duplicateKeysAndHooksAreRejectedWithoutEchoingInput() {
        assertEquals("DUPLICATE_PROFILE_FIELD", assertFailsWith<VpnOperationException> {
            AndroidVpnProfile.parse(profile() + "Endpoint = 192.0.2.2:51820\n")
        }.code)
        assertEquals("UNSUPPORTED_PROFILE_FIELD", assertFailsWith<VpnOperationException> {
            AndroidVpnProfile.parse(profile().replace("[Peer]", "PostUp = secret-command\n[Peer]"))
        }.code)
    }
    @Test fun multiplePeersCannotEnableBypassFamilies() {
        assertEquals("SINGLE_PEER_REQUIRED", assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(profile() + "[Peer]\n") }.code)
    }
    @Test fun zeroKeyAndOversizedOrNulInputNeverPersist() {
        val conf = profile()
        val zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(conf.replace(Regex("PrivateKey = .*"), "PrivateKey = $zero")) }
        assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(conf + "#".repeat(AndroidVpnProfile.MAX_BYTES)) }
        assertFailsWith<VpnOperationException> { AndroidVpnProfile.parse(conf + '\u0000') }
    }
    @Test fun guardedTunnelUsesFreshKeysAndHasNoEndpoint() {
        val original = AndroidVpnProfile.parse(profile())
        val guard = AndroidVpnProfile.guard(original)
        assertNotEquals(original.`interface`.keyPair.privateKey, guard.`interface`.keyPair.privateKey)
        assertFalse(guard.peers.single().endpoint.isPresent)
        assertEquals(2, guard.peers.single().allowedIps.size)
    }
    @Test fun boundedReadDoesNotAcceptTruncatedLargeProfile() {
        assertEquals(16, ByteArrayInputStream(ByteArray(16)).readBytesLimited(16).size)
        assertFailsWith<VpnOperationException> { ByteArrayInputStream(ByteArray(17)).readBytesLimited(16) }
    }
}
