package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.VerifiedTorrentFile
import com.nuvio.app.features.p2p.TorrentSelectionFile
import com.nuvio.app.features.p2p.selectTorrentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

internal actual object StreamSourceVerifier {
    actual val enabled = System.getProperty("os.name").contains("windows", ignoreCase = true)
    private val directPermits = Semaphore(4)
    private val torrentPermits = Semaphore(2)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val contexts = java.util.concurrent.ConcurrentHashMap<String, StreamVerificationContext>()
    private data class CacheEntry(val stream: StreamItem, val checkedAt: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private val probeExecutable: String by lazy {
        System.getProperty("nuvio.ffprobe.path")?.takeIf { File(it).isFile }
            ?: installBundledProbe() ?: "ffprobe"
    }

    private fun installBundledProbe(): String? {
        val resource = "/verification/windows/ffprobe.exe"
        val expectedHash = StreamSourceVerifier::class.java.getResourceAsStream("$resource.sha256")?.bufferedReader()?.use { it.readText().trim() } ?: return null
        require(Regex("[a-f0-9]{64}").matches(expectedHash))
        val directory = DesktopStorage.cacheDir.resolve("stream-verification").resolve(expectedHash.take(24))
        Files.createDirectories(directory)
        val target = directory.resolve("ffprobe.exe")
        fun hashOf(path: java.nio.file.Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { DigestInputStream(it, digest).use { input -> input.transferTo(java.io.OutputStream.nullOutputStream()) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (Files.isRegularFile(target) && hashOf(target) == expectedHash) return target.toString()
        val input = StreamSourceVerifier::class.java.getResourceAsStream(resource) ?: return null
        val pending = Files.createTempFile(directory, "ffprobe-", ".part")
        try {
            input.use { Files.copy(it, pending, StandardCopyOption.REPLACE_EXISTING) }
            require(hashOf(pending) == expectedHash) { "Video denetleyicisi doğrulanamadı" }
            runCatching { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING) }
            return target.toString()
        } finally { Files.deleteIfExists(pending) }
    }

    actual suspend fun prepareContext(context: StreamVerificationContext): StreamVerificationContext {
        val imdb = Regex("tt[0-9]+").find(context.videoId)?.value ?: return context
        val key = "${context.type}:$imdb"
        val existing = contexts[key]
        if (existing != null) return context.copy(titles = (context.titles + existing.titles).distinct(), year = context.year ?: existing.year,
            runtimeMinutes = context.runtimeMinutes ?: existing.runtimeMinutes)
        val enriched = try {
            val type = if (context.type == "movie") "movie" else "series"
            val request = HttpRequest.newBuilder(URI.create("https://v3-cinemeta.strem.io/meta/$type/$imdb.json"))
                .timeout(Duration.ofSeconds(10)).GET().build()
            val response = runInterruptible(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            val meta = if (response.statusCode() == 200) (Json.parseToJsonElement(response.body()).jsonObject["meta"] as? JsonObject) else null
            context.copy(
                titles = (context.titles + listOfNotNull(meta?.text("name"), meta?.text("originalName"), meta?.text("originalTitle"))).distinct(),
                year = context.year ?: meta?.text("year")?.take(4)?.toIntOrNull(),
                runtimeMinutes = context.runtimeMinutes ?: Regex("[0-9]+").find(meta?.text("runtime").orEmpty())?.value?.toIntOrNull(),
            )
        } catch (error: CancellationException) { throw error } catch (_: Exception) { context }
        contexts[key] = enriched
        return enriched
    }

    actual suspend fun verify(stream: StreamItem, context: StreamVerificationContext): StreamItem? {
        val key = "${context.videoId}:${context.season}:${context.episode}:${stream.p2pInfoHash ?: stream.url}:${stream.p2pFileIdx}:${stream.behaviorHints.proxyHeaders?.request}"
        fun cachedStream(): StreamItem? = cache[key]?.takeIf { System.currentTimeMillis() - it.checkedAt < 120_000L }?.stream?.let { checked ->
            // Reuse measured facts, while keeping this provider's identity and current seed count.
            stream.copy(
                verifiedMedia = checked.verifiedMedia, fileIdx = checked.fileIdx, description = checked.description,
                seeders = StreamListingPolicy.seedCount(stream),
                behaviorHints = stream.behaviorHints.copy(filename = checked.behaviorHints.filename, videoSize = checked.behaviorHints.videoSize),
            )
        }
        cachedStream()?.let { return it }
        return (if (stream.isTorrentStream) torrentPermits else directPermits).withPermit {
        cachedStream()?.let { return@withPermit it }
        try {
            val verified = withTimeoutOrNull(65_000L) {
                if (stream.isTorrentStream) verifyTorrent(stream, context) else verifyDirect(stream, context)
            }
            if (verified != null) {
                if (cache.size > 500) cache.clear()
                cache[key] = CacheEntry(verified, System.currentTimeMillis())
            }
            verified
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        }
    }

    private suspend fun verifyTorrent(stream: StreamItem, context: StreamVerificationContext): StreamItem? {
        var hash: String? = null
        P2pStreamingEngine.incrementActiveDownloads()
        try {
            P2pStreamingEngine.ensureTorrServerRunning()
            val magnet = stream.torrentMagnetUri ?: stream.p2pInfoHash?.let { P2pStreamingEngine.buildP2pMagnet(it, stream.p2pTrackers) } ?: return null
            hash = P2pStreamingEngine.addTorrent(magnet) ?: return null
            var files = P2pStreamingEngine.torrentFiles(hash)
            val deadline = System.currentTimeMillis() + 25_000L
            while (files.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(500)
                files = P2pStreamingEngine.torrentFiles(hash)
            }
            val file = selectVerifiedTorrentFile(files, stream, context) ?: return null
            if (file.length < StreamListingPolicy.minimumTorrentBytes) return null
            val media = probe(P2pStreamingEngine.getTorrentStreamUrl(hash, file.id), emptyMap()) ?: return null
            if (!durationMatches(media, context)) return null
            return verifiedPresentation(stream.copy(fileIdx = file.id - 1, behaviorHints = stream.behaviorHints.copy(filename = file.path.substringAfterLast('/'), videoSize = file.length)), media.copy(sizeBytes = file.length))
        } finally {
            withContext(NonCancellable) { hash?.let { P2pStreamingEngine.dropTorrent(it) }; P2pStreamingEngine.decrementActiveDownloads() }
        }
    }

    private suspend fun verifyDirect(stream: StreamItem, context: StreamVerificationContext): StreamItem? {
        val url = stream.playableDirectUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val media = probe(url, stream.behaviorHints.proxyHeaders?.request.orEmpty()) ?: return null
        if (!durationMatches(media, context)) return null
        if (!media.contentTitle.isNullOrBlank() && !matchesVerifiedContent(media.contentTitle, context.copy(year = null))) return null
        // A direct source was requested with the content ID; when the container carries
        // a title, also require it to agree. Never treat a generic provider name as a title.
        return verifiedPresentation(stream, media)
    }

    private fun durationMatches(media: VerifiedStreamMedia, context: StreamVerificationContext): Boolean {
        val minutes = context.runtimeMinutes ?: return true
        val seconds = media.durationSeconds ?: return false
        return seconds >= minutes * 60.0 * 0.8 && seconds <= minutes * 60.0 * 1.2
    }

    private fun verifiedPresentation(stream: StreamItem, media: VerifiedStreamMedia): StreamItem {
        val size = media.sizeBytes?.let { "%.2f GiB".format(java.util.Locale.ROOT, it / 1_073_741_824.0) }
        val details = listOfNotNull("${media.width}×${media.height}", size ?: "Boyut bilinmiyor", media.codec.uppercase(), "✓ Doğrulandı").joinToString(" • ")
        return stream.copy(verifiedMedia = media, description = details, behaviorHints = stream.behaviorHints.copy(videoSize = media.sizeBytes))
    }

    internal suspend fun probe(url: String, headers: Map<String, String>): VerifiedStreamMedia? = withContext(Dispatchers.IO) {
        val args = mutableListOf(probeExecutable, "-v", "quiet", "-rw_timeout", "15000000", "-analyzeduration", "3000000", "-probesize", "4000000")
        val lines = headers.filter { (key, value) -> key.isNotBlank() && '\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value }
            .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        if (lines.isNotBlank()) args.addAll(listOf("-headers", lines))
        args.addAll(listOf("-select_streams", "V", "-show_entries", "stream=width,height,codec_name:format=size,duration:format_tags=title", "-of", "json", "-i", url))
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 256_000) output.append(it) } } } }.apply { isDaemon = true; start() }
        try {
            val deadline = System.currentTimeMillis() + 30_000L
            while (process.isAlive && System.currentTimeMillis() < deadline) { currentCoroutineContext().ensureActive(); delay(100) }
            if (process.isAlive || process.exitValue() != 0) return@withContext null
            reader.join(1000)
            val root = Json.parseToJsonElement(output.toString()).jsonObject
            val video = (root["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.maxByOrNull { (it.text("width")?.toIntOrNull() ?: 0) } ?: return@withContext null
            val width = video.text("width")?.toIntOrNull() ?: return@withContext null
            val height = video.text("height")?.toIntOrNull() ?: return@withContext null
            val codec = video.text("codec_name")?.takeIf { it.isNotBlank() } ?: return@withContext null
            if (width <= 0 || height <= 0) return@withContext null
            val format = root["format"] as? JsonObject
            VerifiedStreamMedia(width, height, codec, format?.text("size")?.toLongOrNull()?.takeIf { it > 0 }, format?.text("duration")?.toDoubleOrNull(), (format?.get("tags") as? JsonObject)?.text("title"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
}

/** Keep source verification, playback and download on the same episode selection policy. */
internal fun selectVerifiedTorrentFile(
    files: List<VerifiedTorrentFile>, stream: StreamItem, context: StreamVerificationContext,
): VerifiedTorrentFile? {
    val candidates = files.filter { it.length > 0 && matchesVerifiedContent(it.path, context) }
    val selected = try {
        selectTorrentFile(candidates.map { TorrentSelectionFile(it.id - 1, it.path, it.length) },
            requestedIndex = stream.p2pFileIdx, filename = stream.behaviorHints.filename,
            season = context.season, episode = context.episode)
    } catch (_: IllegalStateException) {
        return null
    }
    return candidates.firstOrNull { it.id == selected.index + 1 }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
