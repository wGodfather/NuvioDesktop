package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine

/** Playback and background downloads use the same selection policy before creating a route. */
internal suspend fun NuvioEngine.prepareEpisodeAwareStream(
    torrentId: String,
    fileIndex: Int?,
    filenameHint: String?,
    season: Int?,
    episode: Int?,
) = if (season == null || episode == null) {
    prepareStream(torrentId = torrentId, fileIndex = fileIndex, filenameHint = filenameHint)
} else {
    val selected = selectTorrentFile(
        files = files(torrentId).filterNot { it.pathTruncated }
            .map { TorrentSelectionFile(it.index, it.path, it.size) },
        requestedIndex = fileIndex,
        filename = filenameHint,
        season = season,
        episode = episode,
    )
    prepareStream(torrentId = torrentId, fileIndex = selected.index, filenameHint = selected.path)
}
