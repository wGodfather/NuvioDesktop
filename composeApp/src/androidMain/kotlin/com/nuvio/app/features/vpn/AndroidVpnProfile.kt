package com.nuvio.app.features.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.wireguard.config.Config
import com.wireguard.config.InetAddresses
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only a full tunnel with literal endpoint/DNS; never return an exception containing input. */
internal object AndroidVpnProfile {
    const val MAX_BYTES = 16384
    fun parse(text: String): Config {
        try {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES || '\u0000' in text) invalid()
            var section = ""
            var interfaces = 0
            var peers = 0
            val seen = mutableSetOf<String>()
            for (original in text.lineSequence()) {
                val line = original.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line == "[Interface]") { section = "Interface"; interfaces++; continue }
                if (line == "[Peer]") { section = "Peer"; peers++; continue }
                if (section.isEmpty() || '=' !in line) invalid()
                val field = line.substringBefore('=').trim()
                val allowed = if (section == "Interface") setOf("PrivateKey", "Address", "DNS", "MTU", "ListenPort")
                    else setOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive")
                if (field !in allowed) throw VpnOperationException("UNSUPPORTED_PROFILE_FIELD")
                if (!seen.add("$section.$field")) throw VpnOperationException("DUPLICATE_PROFILE_FIELD")
                // Validate before Config.parse can resolve a DNS search domain or endpoint.
                if (field == "DNS") line.substringAfter('=').split(',').forEach { literal(it.trim()) }
                if (field == "Endpoint") {
                    val endpoint = line.substringAfter('=').trim()
                    if (':' !in endpoint) invalid()
                    literal(endpoint.substringBeforeLast(':').removeSurrounding("[", "]"))
                }
            }
            if (interfaces != 1 || peers != 1) throw VpnOperationException("SINGLE_PEER_REQUIRED")
            val config = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            val iface = config.`interface`
            if (iface.addresses.isEmpty() || iface.dnsServers.isEmpty() || iface.dnsServers.size > 4 ||
                iface.includedApplications.isNotEmpty() || iface.excludedApplications.isNotEmpty()) invalid()
            iface.mtu.ifPresent { if (it !in 1280..1500) invalid() }
            val peer = config.peers.single()
            if (iface.keyPair.privateKey.bytes.all { it == 0.toByte() } || peer.publicKey.bytes.all { it == 0.toByte() }) invalid()
            val routes = peer.allowedIps.map { it.toString() }.toSet()
            if ("0.0.0.0/0" !in routes || routes.any { it !in setOf("0.0.0.0/0", "::/0", "0:0:0:0:0:0:0:0/0") })
                throw VpnOperationException("FULL_TUNNEL_REQUIRED")
            val endpoint = peer.endpoint.orElseThrow { VpnOperationException("INVALID_ENDPOINT") }
            literal(endpoint.host)
            val normalized = Peer.Builder().addAllowedIps(peer.allowedIps).setPublicKey(peer.publicKey)
                .setEndpoint(endpoint).setPersistentKeepalive(25)
            peer.preSharedKey.ifPresent { normalized.setPreSharedKey(it) }
            return Config.Builder().setInterface(iface).addPeer(normalized.build()).build()
        } catch (error: VpnOperationException) { throw error }
        catch (_: Exception) { throw VpnOperationException("INVALID_PROFILE") }
    }

    /** An established TUN without any reachable peer. The system lockdown covers TUN replacement. */
    fun guard(profile: Config): Config = Config.Builder().setInterface(com.wireguard.config.Interface.Builder()
        .setKeyPair(KeyPair()).addAddresses(profile.`interface`.addresses).addDnsServers(profile.`interface`.dnsServers).build())
        .addPeer(Peer.Builder().setPublicKey(KeyPair().publicKey).parseAllowedIPs("0.0.0.0/0, ::/0").build()).build()

    private fun literal(host: String) {
        try {
            if ('%' in host || host.isBlank()) throw VpnOperationException("LITERAL_IP_REQUIRED")
            val address = InetAddresses.parse(host)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress)
                throw VpnOperationException("INVALID_ENDPOINT")
        } catch (error: VpnOperationException) { throw error }
        catch (_: Exception) { throw VpnOperationException("LITERAL_IP_REQUIRED") }
    }
    private fun invalid(): Nothing = throw VpnOperationException("INVALID_PROFILE")
}

/** AES-GCM key remains in Android Keystore; the ciphertext is excluded from backup. */
internal class AndroidVpnProfileStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn/profile.gcm").also { it.parentFile?.mkdirs() })
    private val alias = "nuvio-wireguard-profile-v1"
    fun exists() = file.baseFile.isFile
    fun save(config: Config) {
        val plaintext = config.toWgQuickString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plaintext)
            val blob = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
                .put(1.toByte()).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
            val stream = file.startWrite()
            try { stream.write(blob); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } finally { plaintext.fill(0) }
    }
    fun load(): Config {
        try {
            val blob = file.openRead().use { it.readBytesLimited(AndroidVpnProfile.MAX_BYTES + 128) }
            if (blob.size < 30 || blob[0] != 1.toByte() || blob[1] != 12.toByte()) throw VpnOperationException("PROFILE_STORAGE_FAILED")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(2, 14)))
            val plaintext = cipher.doFinal(blob, 14, blob.size - 14)
            try { return AndroidVpnProfile.parse(plaintext.toString(Charsets.UTF_8)) }
            finally { plaintext.fill(0) }
        } catch (error: VpnOperationException) { throw error }
        catch (_: Exception) { throw VpnOperationException("PROFILE_STORAGE_FAILED") }
    }
    fun delete() { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        if (output.size() > limit) throw VpnOperationException("INVALID_PROFILE")
    }
    return output.toByteArray()
}
