package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun refreshPluginDownloadStream(item: DownloadItem): StreamItem? {
    if (!AppFeaturePolicy.pluginsEnabled || !item.providerAddonId.orEmpty().startsWith("plugin")) return null
    return withTimeoutOrNull(45_000) {
        PluginRepository.initialize()
        val state = PluginRepository.uiState.value
        val scrapers = PluginRepository.getEnabledScrapersForType(item.contentType)
        val groups = listOf(false, true).flatMap { grouped ->
            scrapers.toPluginProviderGroups(state.repositories, grouped)
        }
        val group = groups.firstOrNull { it.addonId == item.providerAddonId } ?: return@withTimeoutOrNull null
        val streams = group.scrapers.flatMap { scraper ->
            PluginRepository.executeScraper(
                scraper = scraper,
                tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber),
                mediaType = item.contentType,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                respectSearchPause = false,
            ).getOrNull().orEmpty().map { result ->
                result.toStreamItem(scraper, group.addonName, group.addonId)
            }
        }
        currentCoroutineContext().ensureActive()
        matchingDownloadStream(item, streams)
    }
}

internal fun matchingDownloadStream(item: DownloadItem, streams: List<StreamItem>): StreamItem? =
    streams.filter {
        it.addonId == item.providerAddonId && it.streamLabel == item.streamTitle &&
            it.playableDirectUrl != null && !it.isTorrentStream &&
            (it.streamType in listOf("hls", "m3u8") || it.playableDirectUrl.orEmpty().contains(".m3u8", ignoreCase = true))
    }.singleOrNull()
