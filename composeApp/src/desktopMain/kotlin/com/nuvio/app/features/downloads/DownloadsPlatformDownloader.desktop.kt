package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.p2p.P2pStreamingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            try {
                when {
                    request.isP2pDownload -> {
                        downloadViaTorrServer(
                            request = request,
                            downloadsDir = downloadsDir,
                            onProgress = onProgress,
                            onSuccess = onSuccess,
                        )
                    }
                    request.isHlsDownload -> {
                        downloadHlsWithSourceRefresh(
                            request = request,
                            downloadsDir = downloadsDir,
                            onProgress = onProgress,
                            onSuccess = onSuccess,
                        )
                    }
                    else -> {
                        downloadViaHttp(
                            request = request,
                            downloadsDir = downloadsDir,
                            onProgress = onProgress,
                            onSuccess = onSuccess,
                        )
                    }
                }
            } catch (error: CancellationException) {
                onPaused()
                throw error
            } catch (error: Throwable) {
                onFailure(error.message ?: "Download failed")
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        runCatching { DownloadSubtitleStorage(localFileUri).remove() }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        val stateFile = File(downloadsDir, "$destinationFileName.hls_state")
        if (stateFile.exists()) stateFile.delete()
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val directory = downloadsDir
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    private suspend fun CoroutineScope.downloadViaTorrServer(
        request: DownloadPlatformRequest,
        downloadsDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    ) {
        val destination = File(downloadsDir, request.destinationFileName)
        val tempFile = File(downloadsDir, "${request.destinationFileName}.part")
        var torrentHash: String? = null

        try {
            P2pStreamingEngine.incrementActiveDownloads()
            P2pStreamingEngine.ensureTorrServerRunning()

            val magnet = if (request.sourceUrl.startsWith("magnet:", ignoreCase = true)) {
                request.sourceUrl
            } else {
                request.p2pInfoHash?.let { P2pStreamingEngine.buildP2pMagnet(it, request.p2pTrackers) }
                    ?: error("Missing magnet or infoHash for torrent download")
            }

            torrentHash = P2pStreamingEngine.addTorrent(magnet, request.destinationFileName)
                ?: error("Failed to add torrent to TorrServer")

            val fileIdx = P2pStreamingEngine.resolveTorrentFileIndex(
                hash = torrentHash,
                requestedIdx = request.p2pFileIdx,
                filename = request.p2pFilename,
            )

            val streamUrl = P2pStreamingEngine.getTorrentStreamUrl(torrentHash, fileIdx)

            streamHttpToFile(
                url = streamUrl,
                headers = emptyMap(),
                tempFile = tempFile,
                destination = destination,
                onProgress = onProgress,
                onSuccess = onSuccess,
            )
        } finally {
            withContext(NonCancellable) {
                torrentHash?.let { hash -> runCatching { P2pStreamingEngine.dropTorrent(hash) } }
                P2pStreamingEngine.decrementActiveDownloads()
            }
        }
    }

    private suspend fun CoroutineScope.downloadViaHttp(
        request: DownloadPlatformRequest,
        downloadsDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    ) {
        val destination = File(downloadsDir, request.destinationFileName)
        val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

        streamHttpToFile(
            url = request.sourceUrl,
            headers = request.sourceHeaders,
            tempFile = tempFile,
            destination = destination,
            onProgress = onProgress,
            onSuccess = onSuccess,
        )
    }

    private suspend fun CoroutineScope.streamHttpToFile(
        url: String,
        headers: Map<String, String>,
        tempFile: File,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    ) {
        var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
        var attemptedRangeRequest = resumeFromBytes > 0L
        var response = sendDownloadRequestDirect(url, headers, if (attemptedRangeRequest) resumeFromBytes else null)

        if (attemptedRangeRequest && response.statusCode() == 416) {
            response.body().close()
            tempFile.delete()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            response = sendDownloadRequestDirect(url, headers, null)
        }

        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Download failed with HTTP ${response.statusCode()}")
        }

        val isPartialResume = attemptedRangeRequest && response.statusCode() == 206 && resumeFromBytes > 0L
        val appendToTemp = isPartialResume
        val startingBytes = if (appendToTemp) resumeFromBytes else 0L
        if (!appendToTemp && tempFile.exists()) {
            tempFile.delete()
        }

        val totalBytes = resolveTotalBytes(
            startingBytes = startingBytes,
            isPartialResume = isPartialResume,
            contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
            contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
        )
        var downloadedBytes = startingBytes
        onProgress(downloadedBytes, totalBytes)
        var lastProgressAt = System.nanoTime()

        response.body().use { input ->
            FileOutputStream(tempFile, appendToTemp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    val now = System.nanoTime()
                    if (now - lastProgressAt >= 250_000_000L) {
                        onProgress(downloadedBytes, totalBytes)
                        lastProgressAt = now
                    }
                }
                output.flush()
            }
        }

        if (totalBytes != null && downloadedBytes != totalBytes) {
            error("İndirme tamamlanmadı: beklenen dosya boyutuna ulaşılamadı. Yeniden deneyin.")
        }

        if (destination.exists()) {
            destination.delete()
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }

        val finalSize = destination.length()
        onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
    }

    internal suspend fun CoroutineScope.downloadHlsWithSourceRefresh(
        request: DownloadPlatformRequest,
        downloadsDir: File,
        onProgress: (Long, Long?) -> Unit,
        onSuccess: (String, Long?) -> Unit,
    ) {
        try {
            downloadViaHls(request, downloadsDir, onProgress, onSuccess)
        } catch (error: HlsDownloadFailure) {
            if (error.httpStatus !in listOf(403, 404)) throw error
            val refreshed = request.refreshHlsSource?.invoke() ?: throw error
            ensureActive()
            downloadViaHls(refreshed, downloadsDir, onProgress, onSuccess, verifyResumeSegment = true)
        }
    }

    internal suspend fun CoroutineScope.downloadViaHls(
        request: DownloadPlatformRequest,
        downloadsDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        verifyResumeSegment: Boolean = true,
    ) {
        val destination = File(downloadsDir, request.destinationFileName)
        val tempFile = File(downloadsDir, "${request.destinationFileName}.part")
        val stateFile = File(downloadsDir, "${request.destinationFileName}.hls_state")

        var currentUrl = request.sourceUrl
        var playlistText = fetchText(currentUrl, request.sourceHeaders, "HLS playlist")

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val variants = parseHlsMasterPlaylist(playlistText, currentUrl)
            val bestVariant = variants.maxByOrNull { it.bandwidth }
                ?: error("No valid HLS stream variants found")
            currentUrl = bestVariant.uri
            playlistText = fetchText(currentUrl, request.sourceHeaders, "HLS media playlist")
        }

        val segments = parseHlsMediaPlaylist(playlistText, currentUrl)
        if (segments.isEmpty()) {
            error("No segments found in HLS playlist")
        }

        var startSegmentIndex = 0
        if (tempFile.exists() && stateFile.exists()) {
            val savedIdx = stateFile.readText().trim().toIntOrNull()
            if (verifyResumeSegment) {
                check(savedIdx != null && savedIdx in segments.indices) {
                    "HLS playlist changed; partial download preserved. Start a new download for this source."
                }
            }
            if (savedIdx != null && savedIdx in segments.indices) {
                startSegmentIndex = savedIdx + 1
            }
        }

        check(!verifyResumeSegment || !tempFile.exists() || tempFile.length() == 0L || startSegmentIndex > 0) {
            "HLS resume checkpoint missing; partial download preserved."
        }

        if (verifyResumeSegment && startSegmentIndex > 0) {
            val previous = segments[startSegmentIndex - 1]
            val raw = fetchBytes(previous.uri, request.sourceHeaders, "HLS resume verification")
            val expected = previous.keyInfo?.let { key ->
                HlsSegmentCipher(key, fetchBytes(key.uri, request.sourceHeaders, "HLS encryption key"))
                    .decrypt(raw, previous.sequenceNumber)
            } ?: raw
            val matches = tempFile.length() >= expected.size && RandomAccessFile(tempFile, "r").use { file ->
                file.seek(file.length() - expected.size)
                val actual = ByteArray(expected.size)
                file.readFully(actual)
                actual.contentEquals(expected)
            }
            check(matches) { "HLS source changed; partial download preserved. Start a new download for this source." }
        }

        if (startSegmentIndex == 0 && tempFile.exists()) {
            tempFile.delete()
        }

        var downloadedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
        val totalSegments = segments.size
        var activeCipher: HlsSegmentCipher? = null

        FileOutputStream(tempFile, startSegmentIndex > 0).use { output ->
            for (idx in startSegmentIndex until totalSegments) {
                ensureActive()
                val segment = segments[idx]

                if (segment.keyInfo == null) {
                    activeCipher = null
                } else if (segment.keyInfo != activeCipher?.keyInfo) {
                    val keyBytes = fetchBytes(segment.keyInfo.uri, request.sourceHeaders, "HLS encryption key")
                    activeCipher = HlsSegmentCipher(segment.keyInfo, keyBytes)
                }

                val rawBytes = fetchBytes(segment.uri, request.sourceHeaders, "HLS segment $idx")
                ensureActive()

                val finalBytes = if (activeCipher != null) {
                    activeCipher.decrypt(rawBytes, segment.sequenceNumber)
                } else {
                    rawBytes
                }

                output.write(finalBytes)
                output.flush()
                downloadedBytes += finalBytes.size
                stateFile.writeText(idx.toString())

                val avgSegmentBytes = downloadedBytes / (idx + 1)
                val estimatedTotalBytes = avgSegmentBytes * totalSegments
                onProgress(downloadedBytes, estimatedTotalBytes)
            }
            output.flush()
        }

        if (destination.exists()) {
            destination.delete()
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }
        stateFile.delete()

        val finalSize = destination.length()
        onSuccess(destination.toURI().toString(), finalSize)
    }

    private fun sendDownloadRequestDirect(
        url: String,
        headers: Map<String, String>,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(120))
            .GET()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() && !key.equals("range", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        return desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
}

private data class HlsVariant(val uri: String, val bandwidth: Long)

private fun parseHlsMasterPlaylist(content: String, baseUrl: String): List<HlsVariant> {
    val variants = mutableListOf<HlsVariant>()
    val lines = content.lines()
    var currentBandwidth = 0L

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
            val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(trimmed)
            currentBandwidth = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val resolvedUri = resolveHlsUri(baseUrl, trimmed)
            variants.add(HlsVariant(resolvedUri, currentBandwidth))
            currentBandwidth = 0L
        }
    }
    return variants
}

private data class HlsKeyInfo(val method: String, val uri: String, val iv: ByteArray?)

private data class HlsSegment(val uri: String, val sequenceNumber: Long, val keyInfo: HlsKeyInfo?)

private fun parseHlsMediaPlaylist(content: String, baseUrl: String): List<HlsSegment> {
    val segments = mutableListOf<HlsSegment>()
    val lines = content.lines()
    var currentKeyInfo: HlsKeyInfo? = null
    var seq = 0L

    val seqMatch = lines.firstOrNull { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE:") }
    if (seqMatch != null) {
        seq = seqMatch.substringAfter(':').trim().toLongOrNull() ?: 0L
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-KEY:")) {
            currentKeyInfo = parseHlsKey(trimmed, baseUrl)
        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val resolvedUri = resolveHlsUri(baseUrl, trimmed)
            segments.add(HlsSegment(resolvedUri, seq++, currentKeyInfo))
        }
    }
    return segments
}

private fun parseHlsKey(line: String, baseUrl: String): HlsKeyInfo? {
    val methodMatch = Regex("""METHOD=([A-Z0-9-]+)""").find(line) ?: return null
    val method = methodMatch.groupValues[1]
    if (method == "NONE") return null
    val uriMatch = Regex("""URI="([^"]+)"""").find(line) ?: return null
    val uri = resolveHlsUri(baseUrl, uriMatch.groupValues[1])
    val ivMatch = Regex("""IV=0x([0-9a-fA-F]+)""").find(line)
    val iv = ivMatch?.groupValues?.get(1)?.let { hexToBytes(it) }
    return HlsKeyInfo(method, uri, iv)
}

private fun resolveHlsUri(base: String, relative: String): String =
    try {
        URI(base).resolve(relative).toString()
    } catch (_: Exception) {
        relative
    }

private fun hexToBytes(hex: String): ByteArray {
    val clean = if (hex.length % 2 != 0) "0$hex" else hex
    val bytes = ByteArray(clean.length / 2)
    for (i in bytes.indices) {
        bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

private class HlsSegmentCipher(val keyInfo: HlsKeyInfo, private val keyBytes: ByteArray) {
    fun decrypt(encrypted: ByteArray, sequenceNumber: Long): ByteArray {
        if (keyInfo.method != "AES-128") return encrypted
        val iv = keyInfo.iv ?: ByteBuffer.allocate(16).apply {
            putLong(8, sequenceNumber)
        }.array()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(encrypted)
    }
}

private suspend fun fetchText(url: String, headers: Map<String, String>, label: String): String =
    fetchBytes(url, headers, label).toString(Charsets.UTF_8)

private class HlsHttpFailure(val status: Int, val retryAfterMs: Long?) : IOException()

private class HlsDownloadFailure(message: String, val httpStatus: Int?) : IOException(message)

private suspend fun fetchBytes(url: String, headers: Map<String, String>, label: String): ByteArray {
    val maxAttempts = 5
    for (attempt in 1..maxAttempts) {
        currentCoroutineContext().ensureActive()
        try {
            val builder = HttpRequest.newBuilder()
                .uri(URI(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            val response = runInterruptible(Dispatchers.IO) {
                desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            if (response.statusCode() !in 200..299) {
                throw HlsHttpFailure(response.statusCode(), hlsRetryAfterMillis(
                    response.headers().firstValue("Retry-After").orElse(null),
                ))
            }
            if (response.body().isEmpty()) throw IOException("Empty response")
            return response.body()
        } catch (error: IOException) {
            val retryable = error !is HlsHttpFailure || error.status in listOf(408, 429, 500, 502, 503, 504)
            if (!retryable || attempt == maxAttempts) {
                // Avoid exposing signed source URLs or request headers in the UI.
                val reason = if (error is HlsHttpFailure) "HTTP ${error.status}" else error.javaClass.simpleName
                throw HlsDownloadFailure("Failed to download $label ($reason; $attempt attempts)",
                    (error as? HlsHttpFailure)?.status)
            }
            val backoffMs = 1_000L shl (attempt - 1)
            delay(maxOf(backoffMs, (error as? HlsHttpFailure)?.retryAfterMs ?: 0L))
        }
    }
    error("Unreachable HLS retry state")
}

internal fun hlsRetryAfterMillis(value: String?, nowEpochMs: Long = System.currentTimeMillis()): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = trimmed.toLongOrNull()
    val millis = if (seconds != null) {
        seconds.coerceIn(0, 120) * 1_000L
    } else {
        runCatching {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowEpochMs
        }.getOrNull() ?: return null
    }
    return millis.coerceIn(0, 120_000L)
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
