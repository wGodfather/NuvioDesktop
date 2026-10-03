package com.nuvio.app.features.streams

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlin.test.*

class StreamListingPolicyTest {
    private fun source(torrent: Boolean = true, seeders: Int? = 5, bytes: Long? = StreamListingPolicy.minimumTorrentBytes, width: Int = 1920) =
        StreamItem(name = "Example 1080p", addonName = "Example", addonId = "addon:example", seeders = seeders,
            url = if (torrent) "magnet:?xt=urn:btih:0123456789012345678901234567890123456789" else "https://example.invalid/video.m3u8",
            verifiedMedia = VerifiedStreamMedia(width, 800, "h264", bytes))

    @Test fun `torrent thresholds are inclusive and unknown seeders are hidden`() {
        assertTrue(StreamListingPolicy.isVisible(source()))
        assertFalse(StreamListingPolicy.isVisible(source(seeders = 4)))
        assertFalse(StreamListingPolicy.isVisible(source(seeders = null)))
        assertFalse(StreamListingPolicy.isVisible(source(bytes = StreamListingPolicy.minimumTorrentBytes - 1)))
        assertFalse(StreamListingPolicy.isVisible(source(bytes = null)))
        assertFalse(StreamListingPolicy.isVisible(source(width = 1280)))
    }

    @Test fun `direct HLS does not require size or seeders but still requires full HD`() {
        assertTrue(StreamListingPolicy.isVisible(source(false, null, null)))
        assertTrue(StreamListingPolicy.isVisible(source(false, null, 10)))
        assertFalse(StreamListingPolicy.isVisible(source(false, null, null, 1280)))
        assertFalse(StreamListingPolicy.isVisible(source(false).copy(name = "Unknown", verifiedMedia = null)))
    }

    @Test fun `measured selected episode size overrides whole torrent pack labels`() {
        val episode = source(bytes = 500_000_000).copy(name = "Complete season 1080p [30 GB]", behaviorHints = StreamBehaviorHints(videoSize = 30_000_000_000))
        assertFalse(StreamListingPolicy.isVisible(episode))
        assertTrue(StreamListingPolicy.isVisible(episode.copy(verifiedMedia = episode.verifiedMedia!!.copy(sizeBytes = 2_000_000_000))))
    }

    @Test fun `seed metadata survives parsing and zero never falls back to a label`() {
        val parsed = StreamParser.parse("""{"streams":[{"url":"magnet:?x","seeders":5},{"url":"magnet:?y","seeders":0,"name":"[👤 99]"}]}""", "B.O.A.T", "addon:boat")
        assertEquals(5, StreamListingPolicy.seedCount(parsed[0]))
        assertEquals(0, StreamListingPolicy.seedCount(parsed[1]))
        val labeled = source(seeders = null).copy(name = "1080p [👤 44]", addonName = "B.O.A.T")
        assertEquals(44, StreamListingPolicy.seedCount(labeled))
        assertNull(StreamListingPolicy.seedCount(labeled.copy(addonName = "Unrelated provider")))
        assertNull(StreamListingPolicy.seedCount(labeled.copy(name = "1080p peers: 44")))
    }

    @Test fun `torrents are above direct sources and size then resolution break ties`() {
        val small = source(bytes = 2_000_000_000)
        val big = source(bytes = 3_000_000_000)
        val fourK = big.copy(verifiedMedia = big.verifiedMedia!!.copy(width = 3840))
        val direct = source(false, bytes = 100_000_000_000)
        assertEquals(listOf(fourK, big, small, direct), listOf(direct, small, big, fourK).sortedForSourceListing())
    }

    @Test fun `fast direct source is published while same addon torrent is still blocked`() = runBlocking {
        val torrent = source()
        val direct = source(false)
        val releaseTorrent = CompletableDeferred<Unit>()
        val directPublished = CompletableDeferred<Unit>()
        val published = mutableListOf<StreamItem>()
        val job = launch {
            publishEligibleStreams(listOf(torrent, direct), verify = {
                if (it.isTorrentStream) releaseTorrent.await()
                it
            }, publish = {
                published += it
                if (!it.isTorrentStream) directPublished.complete(Unit)
            })
        }
        try {
            withTimeout(2000) { directPublished.await() }
            assertEquals(listOf(direct), published)
            releaseTorrent.complete(Unit)
            withTimeout(2000) { job.join() }
            assertEquals(listOf(direct, torrent), published)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `ineligible torrents never start expensive verification`() = runBlocking {
        var checks = 0
        publishEligibleStreams(listOf(source(seeders = 4), source(seeders = null), source(width = 1280)),
            verify = { checks++; it }, publish = { fail("No source should be visible") })
        assertEquals(0, checks)
    }

    @Test fun `leaving the source request cancels pending verification and runs cleanup`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            publishEligibleStreams(listOf(source()), verify = {
                try { started.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }, publish = { fail("Cancelled source must not be published") })
        }
        try {
            withTimeout(2000) { started.await() }
        } finally { job.cancelAndJoin() }
        assertTrue(cleaned)
    }

    @Test fun `download capability allows HLS only on a supporting platform`() {
        assertTrue(source(false).supportsDownloadButton(supportsHls = true))
        assertFalse(source(false).supportsDownloadButton(supportsHls = false))
        assertTrue(source().supportsDownloadButton(supportsHls = false))
        assertFalse(source(false).copy(url = "https://example.invalid/video.mpd").supportsDownloadButton(true))
    }
}
