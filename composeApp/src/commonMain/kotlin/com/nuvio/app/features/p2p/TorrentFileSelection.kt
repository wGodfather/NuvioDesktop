package com.nuvio.app.features.p2p

/** Index is always the original, zero-based torrent index, never the video list position. */
internal data class TorrentSelectionFile(val index: Int, val path: String, val size: Long)

private val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "ts", "m2ts", "m4v", "mov", "wmv", "flv")
private val sampleMarker = Regex("(?i)(?:^|[/ ._\\-])(?:sample|trailer)(?:$|[/ ._\\-])")
private val episodeMarker = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,2})[ ._\\-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))(?!\\d)")
private val seasonFolder = Regex("(?i)(?:^|[ ._\\-])(?:s|season[ ._\\-]*|sezon[ ._\\-]*)(\\d{1,2})(?=$|[ ._\\-])")
private val shortEpisode = Regex("(?i)^(?:e|episode[ ._\\-]*|bölüm[ ._\\-]*)?(\\d{1,3})(?=$|[ ._\\-])")

private fun String.normalizedPath(): String = trim().replace('\\', '/').lowercase()
private fun TorrentSelectionFile.isVideo(): Boolean {
    if (path.substringAfterLast('.', "").lowercase() !in videoExtensions) return false
    val normalized = path.normalizedPath()
    if (normalized.substringBeforeLast('/', "").split('/').any { it in setOf("sample", "samples", "trailer", "trailers") }) return false
    val name = normalized.substringAfterLast('/')
    // A series title can contain these words (e.g. Trailer Park Boys).
    // Sample/trailer markers for an episode must follow its episode identifier.
    val episode = episodeMarker.find(name)
    val labels = if (episode == null) name else name.substring(episode.range.last + 1)
    return !sampleMarker.containsMatchIn(labels)
}

private fun TorrentSelectionFile.folderSeason(): Int? =
    path.normalizedPath().substringBeforeLast('/', "").split('/').asReversed()
        .firstNotNullOfOrNull { folder ->
            seasonFolder.findAll(folder).map { it.groupValues[1].toInt() }.distinct().toList().singleOrNull()
        }

private fun TorrentSelectionFile.episodes(): Set<Pair<Int, Int>> {
    val path = path.normalizedPath()
    val name = path.substringAfterLast('/').substringBeforeLast('.')
    val result = mutableSetOf<Pair<Int, Int>>()
    val labelledName = if (episodeMarker.containsMatchIn(name)) name else
        path.substringBeforeLast('/', "").split('/').asReversed().firstOrNull { episodeMarker.containsMatchIn(it) }.orEmpty()
    episodeMarker.findAll(labelledName).forEach { match ->
        val season = (match.groups[1]?.value ?: match.groups[3]!!.value).toInt()
        val first = (match.groups[2]?.value ?: match.groups[4]!!.value).toInt()
        result += season to first
        // Combined files: S02E08E09, S02E08-E09 and 2x08-09.
        var tail = labelledName.substring(match.range.last + 1)
        val nextEpisode = Regex("^(e|[ ._]*-[ ._]*(?:e)?)(\\d{1,3})(?!\\d)")
        while (true) {
            val next = nextEpisode.find(tail) ?: break
            val last = next.groupValues[2].toInt()
            if ('-' in next.groupValues[1] && last >= first && last - first <= 100) {
                (first..last).forEach { result += season to it }
            } else {
                result += season to last
            }
            tail = tail.substring(next.range.last + 1)
        }
    }
    if (result.isEmpty()) {
        val season = folderSeason()
        val episode = shortEpisode.find(name)?.groupValues?.get(1)?.toInt()
        if (season != null && episode != null) result += season to episode
    }
    return result
}

/** Episode identity takes precedence over stale addon hints and largest-file fallbacks. */
internal fun selectTorrentFile(
    files: List<TorrentSelectionFile>,
    requestedIndex: Int? = null,
    filename: String? = null,
    season: Int? = null,
    episode: Int? = null,
): TorrentSelectionFile {
    val videos = files.filter { it.isVideo() }
    check(videos.isNotEmpty()) { "Torrent içinde oynatılabilir video bulunamadı." }
    val target = if (season != null && episode != null) season to episode else null
    // Combined episode files need chapter/time metadata to start the requested episode.
    // Selecting the same file from its beginning for every episode repeats the original bug.
    val candidates = if (target == null) videos else videos.filter { it.episodes() == setOf(target) }
    val hint = filename?.normalizedPath()?.takeIf { it.isNotBlank() }

    fun hinted(items: List<TorrentSelectionFile>): TorrentSelectionFile? {
        if (hint == null) return null
        return items.filter { it.path.normalizedPath() == hint }.singleOrNull()
            ?: items.filter { it.path.normalizedPath().substringAfterLast('/') == hint.substringAfterLast('/') }.singleOrNull()
    }

    if (target != null) {
        if (candidates.isNotEmpty()) {
            hinted(candidates)?.let { return it }
            candidates.singleOrNull()?.let { return it }
            candidates.singleOrNull { it.index == requestedIndex }?.let { return it }
            error("S${season}E${episode} için birden fazla dosya var. Dosyası belirtilmiş başka bir kaynak seçin.")
        }
        // Unlabelled single episodes and explicit addon file identities still work.
        // A contradictory episode label must never be overridden by a hint/index.
        val unlabelled = videos.filter { it.episodes().isEmpty() && (it.folderSeason() == null || it.folderSeason() == season) }
        hinted(unlabelled)?.let { return it }
        unlabelled.singleOrNull { it.index == requestedIndex }?.let { return it }
        if (videos.size == 1 && unlabelled.size == 1) return unlabelled.single()
        error("Seçilen S${season}E${episode} torrent paketinde güvenilir biçimde bulunamadı. Başka bir kaynak seçin.")
    }
    hinted(videos)?.let { return it }
    videos.singleOrNull { it.index == requestedIndex }?.let { return it }
    return videos.maxBy { it.size }
}
