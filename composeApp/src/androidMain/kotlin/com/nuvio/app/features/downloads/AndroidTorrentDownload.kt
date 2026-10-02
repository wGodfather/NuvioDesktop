package com.nuvio.app.features.downloads

import android.content.Context
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.buildNuvioEngineConfig
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.engine.NuvioEngine
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal data class AndroidTorrentDownloadRoute(val url: String, val fileSize: Long)

internal interface AndroidTorrentDownloadSession {
    suspend fun prepare(item: DownloadItem): AndroidTorrentDownloadRoute
    suspend fun close()
}

/** A download owns its engine and route; player stop/reconfiguration cannot close them. */
internal class NuvioEngineDownloadSession(private val context: Context) : AndroidTorrentDownloadSession {
    private var engine: NuvioEngine? = null
    private var streamId: String? = null

    override suspend fun prepare(item: DownloadItem): AndroidTorrentDownloadRoute = withContext(Dispatchers.IO) {
        check(engine == null)
        val key = MessageDigest.getInstance("SHA-256").digest(item.fileName.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val state = File(context.noBackupFilesDir, "download-torrents/$key")
        val cache = File(context.cacheDir, "download-torrents/$key")
        check(state.isDirectory || state.mkdirs()) { "Cannot create torrent state directory" }
        check(cache.isDirectory || cache.mkdirs()) { "Cannot create torrent cache directory" }
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val activeEngine = NuvioEngine.create(buildNuvioEngineConfig(
            stateDirectory = state,
            cacheDirectory = cache,
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )).also { engine = it }
        val magnet = item.sourceUrl.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: buildP2pMagnetUri(checkNotNull(item.p2pInfoHash) { "Torrent hash is missing" }, item.p2pTrackers)
        val torrentId = activeEngine.addMagnet(magnet)
        val stream = activeEngine.prepareStream(
            torrentId = torrentId,
            fileIndex = item.p2pFileIdx,
            filenameHint = item.p2pFilename,
        )
        streamId = stream.id
        AndroidTorrentDownloadRoute(stream.url, stream.fileSize)
    }

    override suspend fun close() {
        val activeEngine = engine ?: return
        engine = null
        try {
            streamId?.let { activeEngine.stopStream(it) }
        } finally {
            streamId = null
            activeEngine.shutdown()
        }
    }
}

internal suspend fun transferAndroidTorrentDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    session: AndroidTorrentDownloadSession,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (Long?, String?) -> Unit,
    onProgress: (Long, Long?) -> Unit,
): File {
    try {
        val route = session.prepare(item)
        val url = route.url.toHttpUrl()
        require(url.host in setOf("127.0.0.1", "localhost", "::1")) { "Torrent engine returned a non-local route" }
        return transferAndroidDownload(
            // Persist the original magnet in the transfer store, never the ephemeral engine URL.
            item = item.copy(sourceUrl = route.url, sourceHeaders = emptyMap()),
            directory = directory,
            validator = validator,
            client = client,
            onHeaders = { total, responseValidator ->
                if (total != null && route.fileSize > 0L && total != route.fileSize) {
                    throw IOException("Torrent response size does not match the selected file")
                }
                onHeaders(total ?: route.fileSize.takeIf { it > 0L }, responseValidator)
            },
            onProgress = onProgress,
        )
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { session.close() }
    }
}
