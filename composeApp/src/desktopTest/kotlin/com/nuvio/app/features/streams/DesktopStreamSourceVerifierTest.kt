package com.nuvio.app.features.streams

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopStreamSourceVerifierTest {
    @Test fun `reads actual video dimensions and size with required headers`() = runBlocking {
        val headers = AtomicReference<String>()
        withServer { server, url ->
            val bytes = javaClass.getResourceAsStream("/verification/short-video.mp4")!!.use { it.readBytes() }
            server.createContext("/video") { exchange ->
                headers.set(exchange.requestHeaders.getFirst("Referer"))
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val stream = StreamItem(name = "Provider [2160p HLS]", addonName = "Test", addonId = "test", url = "$url/video",
                behaviorHints = StreamBehaviorHints(videoSize = 100_000_000_000, proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://example.invalid/"))))
            val verified = StreamSourceVerifier.verify(stream, StreamVerificationContext("movie", "test1", listOf("Example Movie"), 2024))
            assertNotNull(verified)
            assertEquals(1280, verified.verifiedMedia?.width)
            assertEquals(720, verified.verifiedMedia?.height)
            assertEquals(bytes.size.toLong(), verified.verifiedMedia?.sizeBytes)
            assertEquals("https://example.invalid/", headers.get())
            assertEquals("Provider [1280×720]", verified.verifiedDisplayLabel)
            assertEquals("Provider [2160p HLS]", verified.streamLabel) // Download refresh keeps the provider's stable identity.
        }
    }

    @Test fun `rejects HTTP error and HTML masquerading as a movie`() = runBlocking {
        withServer { server, url ->
            server.createContext("/error") { it.sendResponseHeaders(500, -1); it.close() }
            server.createContext("/html") { exchange ->
                val bytes = "<html>Access denied</html>".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            for (path in listOf("error", "html")) assertNull(StreamSourceVerifier.verify(
                StreamItem(name = "Fake 4K", addonName = "Test", addonId = "test", url = "$url/$path"),
                StreamVerificationContext("movie", "test2", listOf("Example Movie"), 2024)))
        }
    }

    @Test fun `rejects actual video for another title or incompatible runtime`() = runBlocking {
        withServer { server, url ->
            val bytes = javaClass.getResourceAsStream("/verification/short-video.mp4")!!.use { it.readBytes() }
            server.createContext("/video") { exchange -> exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            val stream = StreamItem(addonName = "Test", addonId = "test", url = "$url/video")
            assertNull(StreamSourceVerifier.verify(stream, StreamVerificationContext("movie", "wrongtitle", listOf("Other Movie"), 2024)))
            assertNull(StreamSourceVerifier.verify(stream, StreamVerificationContext("movie", "wrongruntime", listOf("Example Movie"), 2024, runtimeMinutes = 120)))
        }
    }

    private suspend fun withServer(block: suspend (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") } finally { server.stop(0); executor.shutdownNow() }
    }
}
