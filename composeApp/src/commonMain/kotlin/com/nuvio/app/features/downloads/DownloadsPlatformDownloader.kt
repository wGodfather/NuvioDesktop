package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
    val refreshHlsSource: (suspend () -> DownloadPlatformRequest?)? = null,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
    val isP2pDownload: Boolean get() = item.isP2pDownload
    val isHlsDownload: Boolean get() = item.isHlsDownload
    val p2pInfoHash: String? get() = item.p2pInfoHash
    val p2pFileIdx: Int? get() = item.p2pFileIdx
    val p2pTrackers: List<String> get() = item.p2pTrackers
    val p2pFilename: String? get() = item.p2pFilename
}

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean
}
