package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentFileSelectionTest {
    @Test fun wordsInSeriesTitlesAreNotMistakenForSampleFiles() {
        val files = listOf(
            TorrentSelectionFile(1, "Trailer Park Boys/Trailer.Park.Boys.S02E08.mkv", 500),
            TorrentSelectionFile(2, "Trailer Park Boys/Trailer.Park.Boys.S02E08.sample.mkv", 900),
        )
        assertEquals(1, selectTorrentFile(files, season = 2, episode = 8).index)
    }

    private val suits = listOf(
        TorrentSelectionFile(0, "Suits.S02/README.txt", 9_000),
        TorrentSelectionFile(12, "Suits.S02/Suits.S02E09.1080p.mkv", 900),
        TorrentSelectionFile(4, "Suits.S02/Suits.S02E08.1080p.mkv", 500),
        TorrentSelectionFile(9, "Suits.S02/Suits.S02E08.sample.mkv", 2_000),
        TorrentSelectionFile(17, "Suits.S02/Suits.S02E080.mkv", 3_000),
    )

    @Test fun sameSeasonPackSelectsDifferentOriginalIndexesForEightAndNine() {
        assertEquals(4, selectTorrentFile(suits, season = 2, episode = 8).index)
        assertEquals(12, selectTorrentFile(suits, season = 2, episode = 9).index)
    }

    @Test fun staleFilenameAndIndexCannotOverrideRequestedEpisode() {
        assertEquals(4, selectTorrentFile(suits, 12, "Suits.S02E09.1080p.mkv", 2, 8).index)
    }

    @Test fun missingEpisodeNeverFallsBackToLargestOrWrongExplicitFile() {
        assertFailsWith<IllegalStateException> { selectTorrentFile(suits, 12, "Suits.S02E09.1080p.mkv", 2, 10) }
        assertFailsWith<IllegalStateException> { selectTorrentFile(suits, season = 1, episode = 8) }
    }

    @Test fun folderEpisodeNumbersAndWindowsPathsAreSupported() {
        val files = listOf(
            TorrentSelectionFile(7, "Suits\\Suits - Season 02\\08 - Rewind.mkv", 100),
            TorrentSelectionFile(8, "Suits\\Season 02\\09 - Asterisk.mkv", 200),
            TorrentSelectionFile(1, "Suits\\Season 01\\08.mkv", 300),
        )
        assertEquals(7, selectTorrentFile(files, season = 2, episode = 8).index)
        assertFailsWith<IllegalStateException> {
            selectTorrentFile(listOf(TorrentSelectionFile(0, "Suits.S01/video.mkv", 100)), season = 2, episode = 8)
        }
    }

    @Test fun alternateEpisodeNamesUseNumericBoundariesAndCombinedFilesAreNotGuessed() {
        for (name in listOf("Show.2x08.mkv", "Show.S2E8.mkv", "Show.S02E08/video.mkv")) {
            assertEquals(4, selectTorrentFile(listOf(TorrentSelectionFile(4, name, 100)), season = 2, episode = 8).index)
        }
        assertFailsWith<IllegalStateException> {
            selectTorrentFile(listOf(TorrentSelectionFile(4, "Show.2x080.mkv", 100)), season = 2, episode = 8)
        }
        for (name in listOf("Show.S02E07E08.mkv", "Show.S02E07-E09.mkv", "Show.S02E07-S02E09.mkv")) {
            assertFailsWith<IllegalStateException> {
                selectTorrentFile(listOf(TorrentSelectionFile(4, name, 100)), season = 2, episode = 8)
            }
        }
    }

    @Test fun ambiguousUnlabelledPackRequiresAnExplicitFileIdentity() {
        val files = listOf(TorrentSelectionFile(3, "a.mkv", 100), TorrentSelectionFile(8, "b.mkv", 200))
        assertFailsWith<IllegalStateException> { selectTorrentFile(files, season = 2, episode = 8) }
        assertEquals(3, selectTorrentFile(files, requestedIndex = 3, season = 2, episode = 8).index)
        assertEquals(8, selectTorrentFile(files, filename = "b.mkv", season = 2, episode = 8).index)
    }

    @Test fun duplicateEpisodeInDifferentFoldersRequiresExactPathOrIndex() {
        val files = listOf(TorrentSelectionFile(3, "cut-a/Show.S02E08.mkv", 100), TorrentSelectionFile(8, "cut-b/Show.S02E08.mkv", 200))
        assertFailsWith<IllegalStateException> { selectTorrentFile(files, filename = "Show.S02E08.mkv", season = 2, episode = 8) }
        assertEquals(8, selectTorrentFile(files, filename = "cut-b/Show.S02E08.mkv", season = 2, episode = 8).index)
    }

    @Test fun singleUnlabelledEpisodeAndMovieFallbackStillWork() {
        assertEquals(3, selectTorrentFile(listOf(TorrentSelectionFile(3, "episode.mkv", 100)), season = 2, episode = 8).index)
        assertEquals(12, selectTorrentFile(suits.filterNot { it.index == 17 }).index)
        assertEquals(4, selectTorrentFile(suits, requestedIndex = 4).index)
        assertFailsWith<IllegalStateException> { selectTorrentFile(listOf(TorrentSelectionFile(0, "README.txt", 5_000))) }
    }
}
