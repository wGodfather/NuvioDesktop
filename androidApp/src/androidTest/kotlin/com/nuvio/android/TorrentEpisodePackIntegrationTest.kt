package com.nuvio.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Generated season pack on a disposable emulator; no external media. */
@RunWith(AndroidJUnit4::class)
class TorrentEpisodePackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    @Test fun samePackAndStaleHintsProduceDistinctEpisodesForPlaybackAndDownloads() {
        val magnet = args.getString("packMagnet")
        val tracker = args.getString("packTracker")
        val hash8 = args.getString("packSha8")
        val hash9 = args.getString("packSha9")
        assumeNotNull(magnet, tracker, hash8, hash9)
        val infoHash = requireNotNull(magnet).substringAfter("urn:btih:").substringBefore('&')
        val expected = mapOf(8 to requireNotNull(hash8), 9 to requireNotNull(hash9))
        ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { P2pSettingsRepository.setP2pEnabled(true) }
            try {
                runBlocking {
                    for (episode in listOf(8, 9)) {
                        val url = P2pStreamingEngine.startStream(P2pStreamRequest(
                            infoHash = infoHash, fileIdx = 1, filename = "Suits.S02E08.mp4",
                            trackers = listOf(requireNotNull(tracker)), seasonNumber = 2, episodeNumber = episode,
                        ))
                        val connection = URL(url).openConnection().apply { connectTimeout = 60_000; readTimeout = 60_000 }
                        val bytes = connection.getInputStream().use { it.readBytes() }
                        assertEquals("Wrong playback episode $episode", expected[episode], sha(bytes))
                    }
                }
                P2pStreamingEngine.stopStream()
                scenario.onActivity {
                    for (episode in listOf(8, 9)) DownloadsRepository.enqueueFromStream(
                        contentType = "series", videoId = "$ID:2:$episode", parentMetaId = ID,
                        parentMetaType = "series", title = "Generated season pack QA",
                        logo = null, poster = null, background = null, seasonNumber = 2,
                        episodeNumber = episode, episodeTitle = "Episode $episode", episodeThumbnail = null,
                        stream = StreamItem(url = magnet, fileIdx = 1, addonName = "Local QA", addonId = "local.qa",
                            behaviorHints = StreamBehaviorHints(filename = "Suits.S02E08.mp4")),
                    )
                }
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    val items = DownloadsRepository.uiState.value.items.filter { it.parentMetaId == ID }
                    if (items.size == 2 && items.all { it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) }) break
                    Thread.sleep(100)
                }
                val items = DownloadsRepository.uiState.value.items.filter { it.parentMetaId == ID }
                assertEquals(2, items.size)
                for (item in items) {
                    assertEquals(item.errorMessage, DownloadStatus.Completed, item.status)
                    assertEquals("Wrong downloaded episode ${item.episodeNumber}", expected[item.episodeNumber],
                        sha(File(URI(requireNotNull(item.localFileUri))).readBytes()))
                }
                assertNotEquals(expected[8], expected[9])
            } finally {
                P2pStreamingEngine.stopStream()
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object { private const val ID = "nuvio-episode-pack-qa" }
}
