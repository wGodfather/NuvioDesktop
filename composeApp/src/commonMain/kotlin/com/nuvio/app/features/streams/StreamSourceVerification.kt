package com.nuvio.app.features.streams

internal data class StreamVerificationContext(
    val type: String,
    val videoId: String,
    val titles: List<String>,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null,
)

/** Measured media facts. Provider labels alone do not establish verification. */
data class VerifiedStreamMedia(
    val width: Int,
    val height: Int,
    val codec: String,
    val sizeBytes: Long? = null,
    val durationSeconds: Double? = null,
    val contentTitle: String? = null,
)

internal expect object StreamSourceVerifier {
    val enabled: Boolean
    suspend fun prepareContext(context: StreamVerificationContext): StreamVerificationContext
    suspend fun verify(stream: StreamItem, context: StreamVerificationContext): StreamItem?
}

internal fun parseStreamSizeBytes(text: String?): Long? {
    val match = Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\\b")
        .find(text.orEmpty()) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val factor = when (match.groupValues[2].uppercase()) {
        "TB", "TIB" -> 1_099_511_627_776.0
        "GB", "GIB" -> 1_073_741_824.0
        "MB", "MIB" -> 1_048_576.0
        "KB", "KIB" -> 1_024.0
        else -> 1.0
    }
    return (value * factor).takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
}

internal fun StreamItem.displaySizeBytes(): Long =
    verifiedMedia?.sizeBytes?.takeIf { it > 0 }
        ?: behaviorHints.videoSize?.takeIf { it > 0 }
        ?: clientResolve?.stream?.raw?.size?.takeIf { it > 0 }
        ?: debridCacheStatus?.cachedSize?.takeIf { it > 0 }
        ?: parseStreamSizeBytes(streamSubtitle)
        ?: parseStreamSizeBytes(streamLabel)
        ?: 0L

internal fun StreamItem.displayResolution(): Int {
    verifiedMedia?.let { return it.width } // Width handles cinematic aspect ratios without mislabelling 4K.
    val text = listOfNotNull(clientResolve?.stream?.raw?.parsed?.resolution, behaviorHints.filename, name, title, description).joinToString(" ")
    return when {
        Regex("(?i)\\b(4320p?|8k)\\b").containsMatchIn(text) -> 7680
        Regex("(?i)\\b(2160p?|4k|uhd)\\b").containsMatchIn(text) -> 3840
        Regex("(?i)\\b(1440p?|qhd)\\b").containsMatchIn(text) -> 2560
        Regex("(?i)\\b(1080p?|fhd)\\b").containsMatchIn(text) -> 1920
        Regex("(?i)\\b(720p?|hd)\\b").containsMatchIn(text) -> 1280
        Regex("(?i)\\b(576p?)\\b").containsMatchIn(text) -> 1024
        Regex("(?i)\\b(480p?|sd)\\b").containsMatchIn(text) -> 854
        else -> 0
    }
}

internal fun StreamItem.displayQualityRank(): Int {
    val text = listOfNotNull(behaviorHints.filename, name, title, description, clientResolve?.stream?.raw?.parsed?.quality).joinToString(" ").lowercase()
    return when {
        "remux" in text -> 6
        Regex("blu[ ._-]?ray|bdrip|brrip").containsMatchIn(text) -> 5
        Regex("web[ ._-]?dl").containsMatchIn(text) -> 4
        Regex("web[ ._-]?rip").containsMatchIn(text) -> 3
        "hdtv" in text || "hdrip" in text -> 2
        "dvdrip" in text -> 1
        else -> 0
    }
}

internal fun List<StreamItem>.sortedBySizeAndQuality(): List<StreamItem> =
    sortedWith(compareByDescending<StreamItem> { it.displaySizeBytes() }
        .thenByDescending { it.displayResolution() }
        .thenByDescending { it.displayQualityRank() })

/** Requires an actual filename/title match, not a shared year or a provider's query ID. */
internal fun matchesVerifiedContent(filename: String, context: StreamVerificationContext): Boolean {
    fun normalized(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val name = normalized(filename.substringAfterLast('/').substringAfterLast('\\'))
    val matchesTitle = context.titles.map(::normalized).filter { it.isNotBlank() }.any { title ->
        Regex("(^| )${Regex.escape(title)}( |$)").containsMatchIn(name)
    }
    if (!matchesTitle) return false
    if (context.type == "movie") {
        val statedYear = Regex("\\b(?:19|20)[0-9]{2}\\b").find(name)?.value?.toIntOrNull()
        if (context.year != null && statedYear != context.year) return false
        if (Regex("\\b(?:s[0-9]{1,2}e[0-9]{1,3}|complete series|season|collection|sample|trailer)\\b").containsMatchIn(name)) return false
    } else if (context.season != null && context.episode != null) {
        val ep = Regex("(?i)\\bs([0-9]{1,2})[ ._-]*e([0-9]{1,3})\\b").find(filename)
            ?: Regex("(?i)\\b([0-9]{1,2})x([0-9]{1,3})\\b").find(filename) ?: return false
        if (ep.groupValues[1].toInt() != context.season || ep.groupValues[2].toInt() != context.episode) return false
    }
    return true
}
