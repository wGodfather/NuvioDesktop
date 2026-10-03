package com.nuvio.app.features.vpn

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual object VpnPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instance by lazy {
        if (!windowsX64()) unsupportedVpnController() else VpnController(object : VpnPreferences {
            private val store get() = DesktopStorage.store("vpn-device")
            override fun enabled() = store.getBoolean("enabled") ?: false
            override fun autoConnect() = store.getBoolean("auto-connect") ?: false
            override fun saveEnabled(value: Boolean) = store.putBoolean("enabled", value)
            override fun saveAutoConnect(value: Boolean) = store.putBoolean("auto-connect", value)
        }, WindowsVpnBackend())
    }
    actual fun controller(): VpnController = instance
    fun initialize() {
        if (!windowsX64()) return
        scope.launch {
            instance.initialize()
            var retryAt = 0L
            var retryDelay = 5000L
            while (true) {
                delay(2500)
                instance.refresh()
                if (instance.state.value.status == VpnStatus.Connected) retryDelay = 5000L
                if (instance.shouldReconnect() && System.nanoTime() >= retryAt) {
                    instance.connect()
                    retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60000L)
                }
            }
        }
    }
    suspend fun shutdown() {
        if (windowsX64()) instance.shutdown() else {
            withContext(Dispatchers.Main) { DownloadsRepository.pauseActiveDownloads() }
            withContext(Dispatchers.IO) { P2pStreamingEngine.stopForVpnTransition() }
        }
    }
    private fun windowsX64() = System.getProperty("os.name", "").lowercase().contains("windows") &&
        System.getProperty("os.arch", "").lowercase() in setOf("amd64", "x86_64")
}

internal class WindowsVpnBackend : VpnBackend {
    override val supported = true

    override suspend fun status(): VpnProtection = withContext(Dispatchers.IO) {
        if (!File(System.getenv("ProgramFiles"), "NuvioVpn/NuvioVpn.exe").exists())
            return@withContext VpnProtection(VpnStatus.SetupRequired)
        val reply = try { request("status") } catch (error: VpnOperationException) {
            if (error.code == "SETUP_REQUIRED" || error.code == "RUNTIME_MISSING") return@withContext VpnProtection(VpnStatus.SetupRequired)
            throw error
        }
        parseNativeVpnReply(reply)
    }

    override suspend fun setup() = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(runtime().absolutePath, "setup").redirectErrorStream(true).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); throw VpnOperationException("SETUP_FAILED") }
        if (process.exitValue() != 0) throw VpnOperationException("SETUP_FAILED")
    }

    override suspend fun importProfile() {
        val file = withContext(Dispatchers.Main) {
            val chooser = JFileChooser().apply {
                dialogTitle = "WireGuard (.conf)"
                fileFilter = FileNameExtensionFilter("WireGuard", "conf")
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        } ?: return
        withContext(Dispatchers.IO) {
            // Limit bytes before reading. Contents travel only through stdin and an ACL-protected local pipe.
            Files.newInputStream(file.toPath()).use { stream ->
                val bytes = stream.readNBytes(16385)
                if (bytes.size > 16384) throw VpnOperationException("INVALID_PROFILE")
                try { request("import\t" + Base64.getEncoder().encodeToString(bytes)) }
                finally { bytes.fill(0) }
            }
        }
    }
    override suspend fun deleteProfile() { request("delete") }
    override suspend fun connect() = parseNativeVpnReply(request("connect"))
    override suspend fun arm() = parseNativeVpnReply(request("arm"))
    override suspend fun hold() = parseNativeVpnReply(request("hold"))
    override suspend fun release(): VpnProtection {
        return try { parseNativeVpnReply(request("off")) } catch (error: VpnOperationException) {
            // No installed helper implies no owned system rules only when the service was never installed.
            if (error.code == "SETUP_REQUIRED" && !File(System.getenv("ProgramFiles"), "NuvioVpn/NuvioVpn.exe").exists())
                VpnProtection(VpnStatus.Off) else throw error
        }
    }
    override suspend fun quiesceTorrentTraffic() {
        withContext(Dispatchers.Main) { DownloadsRepository.pauseActiveDownloads() }
        withContext(Dispatchers.IO) { P2pStreamingEngine.stopForVpnTransition() }
    }

    private suspend fun request(command: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(runtime().absolutePath, "client").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command); it.newLine() }
        if (!process.waitFor(50, TimeUnit.SECONDS)) { process.destroyForcibly(); throw VpnOperationException("SERVICE_TIMEOUT") }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty() }
        if (output.startsWith("ERROR\t")) {
            val code = output.substringAfter('\t')
            throw VpnOperationException(code.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "OPERATION_FAILED")
        }
        if (process.exitValue() != 0) throw VpnOperationException("OPERATION_FAILED")
        output
    }

    private fun runtime(): File {
        val loader = WindowsVpnBackend::class.java.classLoader
        val manifest = Properties().apply {
            loader.getResourceAsStream("vpn/windows-x64/runtime.sha256")?.use(::load)
                ?: throw VpnOperationException("RUNTIME_MISSING")
        }
        val directory = DesktopStorage.cacheDir.resolve("vpn/windows-x64").toFile().apply { mkdirs() }
        for (name in listOf("NuvioVpn.exe", "wireguard.exe", "wg.exe")) {
            val expected = manifest.getProperty(name) ?: throw VpnOperationException("RUNTIME_INVALID")
            val file = File(directory, name)
            if (!file.exists() || sha256(file) != expected) {
                loader.getResourceAsStream("vpn/windows-x64/$name")?.use { input ->
                    file.outputStream().use(input::copyTo)
                } ?: throw VpnOperationException("RUNTIME_MISSING")
            }
            if (sha256(file) != expected) throw VpnOperationException("RUNTIME_INVALID")
        }
        return File(directory, "NuvioVpn.exe")
    }
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun parseNativeVpnReply(reply: String): VpnProtection {
    val fields = reply.split('\t')
    if (fields.size != 4 || fields[0] != "STATE" || fields[2] !in setOf("0", "1") || fields[3] !in setOf("0", "1"))
        throw VpnOperationException("INVALID_SERVICE_REPLY")
    val status = VpnStatus.entries.firstOrNull { it.name == fields[1] && it in setOf(VpnStatus.Off, VpnStatus.Connecting, VpnStatus.Connected, VpnStatus.Blocked) }
        ?: throw VpnOperationException("INVALID_SERVICE_REPLY")
    return VpnProtection(status, fields[2] == "1", fields[3] == "1")
}
