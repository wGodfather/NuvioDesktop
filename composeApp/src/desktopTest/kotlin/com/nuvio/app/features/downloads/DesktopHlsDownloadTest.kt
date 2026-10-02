package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHlsDownloadTest {
    @Test
    fun transient_segment_errors_retry_then_complete_without_duplicate_bytes() = runBlocking {
        fixture { server, dir ->
            val segmentRequests = AtomicInteger()
            serve(server, "/playlist.m3u8", "#EXTM3U\n#EXTINF:6,\nfirst.ts\n#EXTINF:6,\nsecond.ts\n#EXT-X-ENDLIST".toByteArray())
            serve(server, "/first.ts", "first".toByteArray())
            server.createContext("/second.ts") { exchange ->
                assertEquals("https://provider.example/", exchange.requestHeaders.getFirst("Referer"))
                val attempt = segmentRequests.incrementAndGet()
                val body = if (attempt < 3) "busy".toByteArray() else "second".toByteArray()
                exchange.responseHeaders.set("Retry-After", "1")
                exchange.sendResponseHeaders(when (attempt) { 1 -> 429; 2 -> 503; else -> 200 }, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            download(server, dir)
            assertEquals(3, segmentRequests.get())
            assertContentEquals("firstsecond".toByteArray(), File(dir, "video.mp4").readBytes())
            assertFalse(File(dir, "video.mp4.part").exists())
            assertFalse(File(dir, "video.mp4.hls_state").exists())
        }
    }

    @Test
    fun permanent_error_preserves_checkpoint_and_resume_does_not_duplicate_completed_segment() = runBlocking {
        fixture { server, dir ->
            val firstRequests = AtomicInteger()
            val secondRequests = AtomicInteger()
            serve(server, "/playlist.m3u8", "#EXTM3U\n#EXTINF:6,\nfirst.ts\n#EXTINF:6,\nsecond.ts\n#EXT-X-ENDLIST".toByteArray())
            server.createContext("/first.ts") { exchange ->
                firstRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, 5)
                exchange.responseBody.use { it.write("first".toByteArray()) }
            }
            server.createContext("/second.ts") { exchange ->
                val body = "second".toByteArray()
                exchange.sendResponseHeaders(if (secondRequests.incrementAndGet() == 1) 404 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            val failure = runCatching { download(server, dir) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure.message.orEmpty().contains("HLS segment 1 (HTTP 404"))
            assertEquals(1, secondRequests.get())
            assertFalse(File(dir, "video.mp4").exists())
            assertEquals("0", File(dir, "video.mp4.hls_state").readText())
            assertContentEquals("first".toByteArray(), File(dir, "video.mp4.part").readBytes())
            download(server, dir)
            assertEquals(2, firstRequests.get()) // The previous segment is verified, never appended twice.
            assertContentEquals("firstsecond".toByteArray(), File(dir, "video.mp4").readBytes())
        }
    }

    @Test
    fun method_none_stops_decryption_after_encrypted_segment() = runBlocking {
        fixture { server, dir ->
            val key = ByteArray(16) { it.toByte() }
            val clear = "encrypted segment".toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            serve(server, "/playlist.m3u8", ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" +
                "#EXTINF:6,\nencrypted.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\nclear.ts\n#EXT-X-ENDLIST").toByteArray())
            serve(server, "/key", key)
            serve(server, "/encrypted.ts", cipher.doFinal(clear))
            serve(server, "/clear.ts", "clear segment".toByteArray())
            download(server, dir)
            assertContentEquals(clear + "clear segment".toByteArray(), File(dir, "video.mp4").readBytes())
        }
    }

    @Test
    fun pause_during_retry_wait_stops_requests_without_completing_file() = runBlocking {
        fixture { server, dir ->
            val firstAttempt = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            serve(server, "/playlist.m3u8", "#EXTM3U\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST".toByteArray())
            server.createContext("/segment.ts") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(503, 4)
                exchange.responseBody.use { it.write("busy".toByteArray()) }
                firstAttempt.complete(Unit)
            }
            val job = launch { download(server, dir) }
            withTimeout(5_000) { firstAttempt.await() }
            job.cancelAndJoin()
            assertEquals(1, requests.get())
            assertFalse(File(dir, "video.mp4").exists())
            assertEquals(0L, File(dir, "video.mp4.part").length())
        }
    }

    @Test
    fun expired_playlist_refreshes_url_and_verifies_existing_bytes_before_resuming() = runBlocking {
        fixture { server, dir ->
            server.createContext("/playlist.m3u8") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
            serve(server, "/renewed.m3u8", "#EXTM3U\n#EXTINF:6,\nfirst.ts\n#EXTINF:6,\nsecond.ts\n#EXT-X-ENDLIST".toByteArray())
            serve(server, "/first.ts", "first".toByteArray())
            serve(server, "/second.ts", "second".toByteArray())
            File(dir, "video.mp4.part").writeText("first")
            File(dir, "video.mp4.hls_state").writeText("0")
            val refreshes = AtomicInteger()
            val renewed = request(server, "/renewed.m3u8")
            download(server, dir, request(server).copy(refreshHlsSource = {
                refreshes.incrementAndGet()
                renewed
            }))
            assertEquals(1, refreshes.get())
            assertContentEquals("firstsecond".toByteArray(), File(dir, "video.mp4").readBytes())
        }
    }

    @Test
    fun renewed_source_with_different_content_preserves_partial_and_does_not_complete() = runBlocking {
        fixture { server, dir ->
            server.createContext("/playlist.m3u8") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
            serve(server, "/renewed.m3u8", "#EXTM3U\n#EXTINF:6,\nfirst.ts\n#EXT-X-ENDLIST".toByteArray())
            serve(server, "/first.ts", "different-content".toByteArray())
            File(dir, "video.mp4.part").writeText("original-content")
            File(dir, "video.mp4.hls_state").writeText("0")
            val failure = runCatching {
                download(server, dir, request(server).copy(refreshHlsSource = { request(server, "/renewed.m3u8") }))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure.message.orEmpty().contains("partial download preserved"))
            assertEquals("original-content", File(dir, "video.mp4.part").readText())
            assertEquals("0", File(dir, "video.mp4.hls_state").readText())
            assertFalse(File(dir, "video.mp4").exists())
        }
    }

    @Test
    fun renewal_matches_same_provider_and_source_and_rejects_ambiguous_matches() {
        val item = DownloadItem(
            id = "fixture", contentType = "movie", parentMetaId = "fixture", parentMetaType = "movie",
            videoId = "fixture", title = "Fixture", streamTitle = "Server 1 1080p", providerName = "Fixture",
            providerAddonId = "plugin:boat", sourceUrl = "https://old.example/master.m3u8", fileName = "video.mp4",
            status = DownloadStatus.Failed, createdAtEpochMs = 0, updatedAtEpochMs = 0, isHlsDownload = true,
        )
        val selected = StreamItem(name = item.streamTitle, addonName = "Fixture", addonId = "plugin:boat",
            url = "https://new.example/master.m3u8")
        assertEquals(selected, matchingDownloadStream(item, listOf(
            selected.copy(addonId = "plugin:other"), selected.copy(name = "Server 2 720p"), selected,
        )))
        assertEquals(null, matchingDownloadStream(item, listOf(selected, selected.copy(url = "https://ambiguous.example/master.m3u8"))))
        assertEquals(null, matchingDownloadStream(item, listOf(selected.copy(url = "magnet:?xt=urn:btih:abc"))))
    }

    @Test
    fun retry_after_supports_seconds_and_http_date_with_bounded_delay() {
        assertEquals(4_000L, hlsRetryAfterMillis("4"))
        assertEquals(120_000L, hlsRetryAfterMillis("999999999"))
        assertEquals(4_000L, hlsRetryAfterMillis("Thu, 01 Jan 1970 00:00:04 GMT", 0))
        assertEquals(null, hlsRetryAfterMillis("invalid"))
    }

    private fun request(server: HttpServer, path: String = "/playlist.m3u8") = DownloadPlatformRequest(DownloadItem(
            id = "fixture", contentType = "movie", parentMetaId = "fixture", parentMetaType = "movie",
            videoId = "fixture", title = "Fixture", streamTitle = "Fixture", providerName = "Fixture",
            sourceUrl = "http://127.0.0.1:${server.address.port}$path",
            sourceHeaders = mapOf("Referer" to "https://provider.example/"),
            fileName = "video.mp4", status = DownloadStatus.Downloading, createdAtEpochMs = 0,
            updatedAtEpochMs = 0, isHlsDownload = true,
        ))

    private suspend fun download(server: HttpServer, dir: File, request: DownloadPlatformRequest = request(server)) {
        withTimeout(20_000) {
            with(DownloadsPlatformDownloader) {
                CoroutineScope(currentCoroutineContext()).downloadHlsWithSourceRefresh(request, dir, { _, _ -> }, { _, _ -> })
            }
        }
    }

    private fun serve(server: HttpServer, path: String, body: ByteArray) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private suspend fun fixture(block: suspend (HttpServer, File) -> Unit) {
        val dir = Files.createTempDirectory("nuvio-hls-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server, dir)
        } finally {
            server.stop(0)
            check(dir.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            check(dir.name.startsWith("nuvio-hls-test"))
            dir.deleteRecursively()
        }
    }
}
