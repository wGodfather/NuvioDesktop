package com.nuvio.app.features.streams

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A provider's seed count is availability metadata, not a guarantee of playback speed. */
internal object StreamListingPolicy {
    const val minimumSeeders = 5
    const val minimumTorrentBytes = 1_073_741_824L
    const val minimumWidth = 1920

    fun seedCount(stream: StreamItem): Int? {
        stream.seeders?.let { return it.takeIf { count -> count >= 0 } }
        // These providers use the Torrentio seed label. Never interpret arbitrary peer/viewer counts.
        val provider = "${stream.addonName} ${stream.sourceName.orEmpty()}".lowercase().replace(".", "")
        if (listOf("boat", "torrentio", "torrentsdb").none { it in provider }) return null
        return Regex("👤\\s*([0-9]+)").find(listOfNotNull(stream.name, stream.title, stream.description).joinToString(" "))
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    fun beforeVerification(stream: StreamItem): Boolean {
        if (stream.isTorrentStream && (seedCount(stream) ?: -1) < minimumSeeders) return false
        if (stream.isTorrentStream && stream.displaySizeBytes() in 1 until minimumTorrentBytes) return false
        // Unknown dimensions can still be measured by the verifier; known low quality can be skipped.
        val width = stream.displayResolution()
        return width == 0 || width >= minimumWidth
    }

    fun isVisible(stream: StreamItem): Boolean =
        stream.displayResolution() >= minimumWidth && (!stream.isTorrentStream ||
            ((seedCount(stream) ?: -1) >= minimumSeeders && stream.displaySizeBytes() >= minimumTorrentBytes))
}

internal fun List<StreamItem>.sortedForSourceListing(): List<StreamItem> =
    sortedWith(compareByDescending<StreamItem> { it.isTorrentStream }
        .thenByDescending { it.displaySizeBytes() }
        .thenByDescending { it.displayResolution() }
        .thenByDescending { it.displayQualityRank() })

/** Publish each ready source immediately, even if another source in the same addon is stalled. */
internal suspend fun publishEligibleStreams(
    streams: List<StreamItem>,
    verify: suspend (StreamItem) -> StreamItem?,
    publish: (StreamItem) -> Unit,
) = coroutineScope {
    streams.filter(StreamListingPolicy::beforeVerification).forEach { stream ->
        launch {
            verify(stream)?.takeIf(StreamListingPolicy::isVisible)?.let(publish)
        }
    }
}
