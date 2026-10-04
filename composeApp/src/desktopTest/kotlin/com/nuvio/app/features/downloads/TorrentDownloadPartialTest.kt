package com.nuvio.app.features.downloads

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentDownloadPartialTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyPartialStartsAgainAfterEpisodeSelectionFix() {
        val partial = File(temporary.newFolder(), "episode.mkv.part").apply { writeText("wrong episode") }
        bindTorrentDownloadPartial(partial, "hash:8:S02E09")
        assertEquals(0L, partial.length())
        partial.writeText("correct episode")
        bindTorrentDownloadPartial(partial, "hash:8:S02E09")
        assertEquals("correct episode", partial.readText())
    }

    @Test fun aDifferentResolvedFileCannotReusePartialBytes() {
        val partial = File(temporary.newFolder(), "episode.mkv.part")
        bindTorrentDownloadPartial(partial, "hash:7:S02E08")
        partial.writeText("eight")
        bindTorrentDownloadPartial(partial, "hash:8:S02E09")
        assertEquals(0L, partial.length())
    }

    @Test fun changedTorrentOrMissingMarkerRestartsThePartial() {
        val partial = File(temporary.newFolder(), "episode.mkv.part")
        bindTorrentDownloadPartial(partial, "hash-a:8:S02E09")
        partial.writeText("old torrent")
        bindTorrentDownloadPartial(partial, "hash-b:8:S02E09")
        assertEquals(0L, partial.length())
        partial.writeText("some bytes")
        File(partial.parentFile, "${partial.name}.torrent_identity").delete()
        bindTorrentDownloadPartial(partial, "hash-b:8:S02E09")
        assertEquals(0L, partial.length())
    }
}
