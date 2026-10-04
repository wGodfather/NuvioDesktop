package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import java.io.File
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Starts the packaged TorrServer only on a disposable Windows CI machine. */
class DesktopTorrentEpisodePackTest {
    @Test fun samePackStreamsAndDownloadsTheRequestedEpisodeDespiteStaleHints() = runBlocking {
        assumeTrue(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_ENVIRONMENT") == "github-hosted")
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val qa = File(root, "build/torrent-pack-qa").apply { mkdirs() }
        val fixture = File(qa, "fixture.json").absoluteFile
        fixture.delete()
        File(qa, "result.txt").delete()
        val proxyPort = ServerSocket(0).use { it.localPort }
        val seed = ProcessBuilder("python", "-u", "tools/qa_torrent_seed.py").apply {
            directory(root)
            environment()["NUVIO_QA_EPISODE_PACK"] = "1"
            environment()["NUVIO_QA_ADVERTISE_IP"] = "127.0.0.1"
            environment()["NUVIO_QA_FIXTURE_OUTPUT"] = fixture.path
            environment()["NUVIO_QA_SEED_PROXY_PORT"] = "$proxyPort"
            redirectErrorStream(true)
            redirectOutput(File(qa, "seed.log"))
        }.start()
        val handles = mutableListOf<DownloadsTaskHandle>()
        var bridge: Process? = null
        try {
            val data = withTimeout(15_000) {
                var parsed: kotlinx.serialization.json.JsonObject? = null
                while (parsed == null) {
                    check(seed.isAlive)
                    parsed = runCatching { Json.parseToJsonElement(fixture.readText()).jsonObject }.getOrNull()
                    if (parsed == null) delay(100)
                }
                parsed
            }
            val bridgeLog = File(qa, "bridge.log")
            val activeBridge = ProcessBuilder(File(root, "build/torrent-pack-peer.exe").absolutePath,
                "-loopback-seed", "-fixture", fixture.path).redirectErrorStream(true).redirectOutput(bridgeLog).start()
            bridge = activeBridge
            withTimeout(15_000) {
                while (!bridgeLog.isFile || !bridgeLog.readText().contains("bridge ready")) {
                    check(activeBridge.isAlive); delay(100)
                }
            }
            val hash = data.getValue("info_hash").jsonPrimitive.content
            val tracker = data.getValue("tracker_url").jsonPrimitive.content
            val expected = data.getValue("episodes").jsonObject
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build()
            for (episode in listOf(8, 9)) {
                val url = P2pStreamingEngine.startStream(P2pStreamRequest(
                    infoHash = hash, fileIdx = 1, filename = "Suits.S02E08.mp4", trackers = listOf(tracker),
                    seasonNumber = 2, episodeNumber = episode,
                ))
                assertTrue(url.contains("index=${if (episode == 8) 2 else 3}&"))
                val reply = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, reply.statusCode())
                assertEquals(expected.getValue("$episode").jsonObject.getValue("sha256").jsonPrimitive.content, sha(reply.body()))
            }
            P2pStreamingEngine.stopStream()
            val results = listOf(8, 9).map { episode ->
                val complete = CompletableDeferred<String>()
                val item = DownloadItem(
                    id = "pack-$episode", contentType = "series", parentMetaId = "pack-qa", parentMetaType = "series",
                    videoId = "pack-qa:2:$episode", title = "Generated season pack", seasonNumber = 2, episodeNumber = episode,
                    streamTitle = "Generated pack", providerName = "Local QA", sourceUrl = data.getValue("magnet").jsonPrimitive.content,
                    fileName = "pack-qa-S02E$episode.mp4", status = DownloadStatus.Downloading, createdAtEpochMs = 1, updatedAtEpochMs = 1,
                    p2pInfoHash = hash, p2pFileIdx = 1, p2pFilename = "Suits.S02E08.mp4", p2pTrackers = listOf(tracker),
                )
                handles += DownloadsPlatformDownloader.start(DownloadPlatformRequest(item), onProgress = { _, _ -> },
                    onSuccess = { uri, _ -> complete.complete(uri) },
                    onFailure = { complete.completeExceptionally(IllegalStateException(it)) },
                    onPaused = { complete.completeExceptionally(IllegalStateException("Download paused")) })
                episode to complete
            }
            val hashes = mutableListOf<String>()
            withTimeout(180_000) {
                for ((episode, complete) in results) {
                    val actual = sha(File(URI(complete.await())).readBytes())
                    assertEquals(expected.getValue("$episode").jsonObject.getValue("sha256").jsonPrimitive.content, actual)
                    hashes += actual
                }
            }
            assertNotEquals(hashes[0], hashes[1])
            File(qa, "result.txt").writeText("PASS: E08/E09 playback and concurrent downloads match distinct expected SHA-256 values\n")
        } finally {
            handles.forEach { it.cancel() }
            P2pStreamingEngine.shutdown()
            seed.destroy()
            if (!seed.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) seed.destroyForcibly()
            bridge?.destroy()
            bridge?.let { if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
