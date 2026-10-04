package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTorrentDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Session(val route: AndroidTorrentDownloadRoute?, val failure: Exception? = null) : AndroidTorrentDownloadSession {
        var closed = false
        var original: DownloadItem? = null
        override suspend fun prepare(item: DownloadItem): AndroidTorrentDownloadRoute {
            original = item
            failure?.let { throw it }
            return checkNotNull(route)
        }
        override suspend fun close() { closed = true }
    }

    private fun torrent() = downloadItem("magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie%20Name%0A&tr=udp%3A%2F%2Ftracker.example%3A80")
        .copy(p2pInfoHash = "a".repeat(40), p2pFileIdx = 2, p2pFilename = "folder/movie.mkv")

    @Test fun downloadsSelectedEngineRouteAndKeepsOriginalMagnet(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("torrent video"))
            val item = torrent()
            val session = Session(AndroidTorrentDownloadRoute(server.url("/engine/selected-file").toString(), 13))
            val output = transferAndroidTorrentDownload(item, temporary.newFolder(), null, session,
                onHeaders = { total, _ -> assertEquals(13L, total) }, onProgress = { _, _ -> })
            assertEquals("torrent video", output.readText())
            assertEquals(item, session.original)
            val request = server.takeRequest()
            assertEquals("/engine/selected-file", request.path)
            assertNull(request.getHeader("Authorization"))
            assertTrue(session.closed)
            assertTrue(item.sourceUrl.startsWith("magnet:"))
        }
    }

    @Test fun resumesTorrentPartialFileThroughFreshRoute(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11").setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")
            File(directory, "video.mkv.part.torrent_identity").writeText("v1:${"a".repeat(40)}:0:11:null:null")
            val session = Session(AndroidTorrentDownloadRoute(server.url("/new-route").toString(), 11))
            val output = transferAndroidTorrentDownload(torrent(), directory, null, session,
                onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("hello world", output.readText())
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            assertTrue(session.closed)
        }
    }

    @Test fun rejectsWrongSelectedFileLength(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("wrong"))
            val session = Session(AndroidTorrentDownloadRoute(server.url("/wrong-file").toString(), 123))
            assertFailsWith<IOException> {
                transferAndroidTorrentDownload(torrent(), temporary.newFolder(), null, session,
                    onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertTrue(session.closed)
        }
    }

    @Test fun restartsLegacyPartialInsteadOfMixingEpisodes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("episode nine"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("episode eight")
            val item = torrent().copy(seasonNumber = 2, episodeNumber = 9)
            val session = Session(AndroidTorrentDownloadRoute(server.url("/episode9").toString(), 12, 8))
            val output = transferAndroidTorrentDownload(item, directory, null, session,
                onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("episode nine", output.readText())
            assertNull(server.takeRequest().getHeader("Range"))
            assertTrue(session.closed)
        }
    }

    @Test fun closesSessionWhenPreparationFails(): Unit = runBlocking {
        val session = Session(null, IOException("metadata timeout"))
        assertFailsWith<IOException> {
            transferAndroidTorrentDownload(torrent(), temporary.newFolder(), null, session,
                onHeaders = { _, _ -> }, onProgress = { _, _ -> })
        }
        assertTrue(session.closed)
    }

    @Test fun rejectsRemoteEngineRouteAndClosesSession(): Unit = runBlocking {
        val session = Session(AndroidTorrentDownloadRoute("https://example.com/remote", 10))
        assertFailsWith<IllegalArgumentException> {
            transferAndroidTorrentDownload(torrent(), temporary.newFolder(), null, session,
                onHeaders = { _, _ -> }, onProgress = { _, _ -> })
        }
        assertTrue(session.closed)
    }

    @Test fun cancellationClosesEngineAndRetainsPartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("streaming video").throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS))
            val session = Session(AndroidTorrentDownloadRoute(server.url("/slow").toString(), 15))
            val directory = temporary.newFolder()
            val firstByte = CompletableDeferred<Unit>()
            val task = async {
                transferAndroidTorrentDownload(torrent(), directory, null, session,
                    onHeaders = { _, _ -> }, onProgress = { bytes, _ -> if (bytes > 0) firstByte.complete(Unit) })
            }
            withTimeout(5_000) { firstByte.await() }
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(session.closed)
            assertTrue(File(directory, "video.mkv.part").length() > 0)
        }
    }

    @Test fun magnetWithoutExplicitHashStillUsesTorrentTransfer() {
        assertTrue(downloadItem("MAGNET:?xt=urn:btih:${"a".repeat(40)}").isP2pDownload)
    }
}
