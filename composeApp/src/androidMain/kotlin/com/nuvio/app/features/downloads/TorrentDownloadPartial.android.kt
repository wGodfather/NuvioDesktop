package com.nuvio.app.features.downloads

import java.io.File
import java.io.RandomAccessFile

/** Never append bytes from a legacy or different episode to a newly selected file. */
internal fun bindTorrentDownloadPartial(partial: File, identity: String) {
    val marker = File(partial.parentFile, "${partial.name}.torrent_identity")
    val previous = marker.takeIf { it.isFile }?.readText()
    if (previous == identity) return
    if (partial.isFile) RandomAccessFile(partial, "rw").use { it.setLength(0) }
    marker.writeText(identity)
}
