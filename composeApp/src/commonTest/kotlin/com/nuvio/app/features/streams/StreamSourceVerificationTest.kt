package com.nuvio.app.features.streams

import com.nuvio.app.features.p2p.buildTorrServerStreamUrl
import kotlin.test.*

class StreamSourceVerificationTest {
    private fun stream(name: String, size: Long? = null, width: Int = 1920, height: Int = 1080) =
        StreamItem(name = name, addonName = "BOAT", addonId = "plugin:boat", url = "https://example.invalid/video",
            verifiedMedia = size?.let { VerifiedStreamMedia(width, height, "h264", it) })

    @Test fun `size is primary even when a smaller source has higher resolution`() {
        val sources = listOf(stream("Small 4K [8.16 GB]", width = 3840), stream("Large 1080p [19.97 GB]"), stream("Largest 4K [20.48 GB]"), stream("Unknown HLS"))
        assertEquals(listOf("Largest 4K [20.48 GB]", "Large 1080p [19.97 GB]", "Small 4K [8.16 GB]", "Unknown HLS"), sources.sortedBySizeAndQuality().map { it.name })
    }

    @Test fun `measured file size overrides a stale provider title`() {
        val measured = stream("Source [100 GB]", size = 1024)
        val other = stream("Source [1 MB]")
        assertEquals(listOf(other, measured), listOf(measured, other).sortedBySizeAndQuality())
    }

    @Test fun `equal sizes use real dimensions then release quality`() {
        val sources = listOf(stream("WEB-DL", 100, 1920, 800), stream("BluRay", 100, 1920, 800), stream("4K", 100, 3840, 1600))
        assertEquals(listOf("4K", "BluRay", "WEB-DL"), sources.sortedBySizeAndQuality().map { it.name })
    }

    @Test fun `size parsing accepts decimal comma and all supported units`() {
        assertEquals(1610612736L, parseStreamSizeBytes("1,5 GiB"))
        assertEquals(262144L, parseStreamSizeBytes("256 KiB"))
        assertEquals(1099511627776L, parseStreamSizeBytes("1 TB"))
        assertNull(parseStreamSizeBytes("1080p"))
        assertNull(parseStreamSizeBytes("0 GB"))
    }

    @Test fun `same year does not verify unrelated movies or collections`() {
        val context = StreamVerificationContext("movie", "tt0068646", listOf("Baba", "The Godfather"), 1972)
        assertTrue(matchesVerifiedContent("The.Godfather.1972.2160p.BluRay.mkv", context))
        assertTrue(matchesVerifiedContent("Baba (1972) 1080p.mkv", context))
        assertFalse(matchesVerifiedContent("New Moomin 1972 complete series VHSRip.mkv", context))
        assertFalse(matchesVerifiedContent("Official IMDb Top 250 Movies Collection 6-17-2011.mkv", context))
        assertFalse(matchesVerifiedContent("The Godfather Part II (1974).mkv", context))
        assertFalse(matchesVerifiedContent("The Godfather.mkv", context))
        assertFalse(matchesVerifiedContent("The Godfather (1972) sample.mkv", context))
    }

    @Test fun `episode matching rejects another episode or a season pack filename`() {
        val context = StreamVerificationContext("series", "tt1234567", listOf("Example Show"), season = 2, episode = 3)
        assertTrue(matchesVerifiedContent("Example.Show.S02E03.1080p.mkv", context))
        assertFalse(matchesVerifiedContent("Example.Show.S02E04.1080p.mkv", context))
        assertFalse(matchesVerifiedContent("Example.Show.Season.2.Complete.mkv", context))
    }

    @Test fun `torrent stream URL carries only the added hash and selected file`() {
        val hash = "88c1c16feb6f3bd9f1c7861ed25a9e13411b5bbd"
        assertEquals("http://127.0.0.1:8091/stream?link=$hash&index=2&play", buildTorrServerStreamUrl("http://127.0.0.1:8091/", hash.uppercase(), 2))
        assertFailsWith<IllegalArgumentException> { buildTorrServerStreamUrl("http://localhost", "magnet:?xt=urn:btih:$hash&dn=Name%0A", 1) }
    }
}
