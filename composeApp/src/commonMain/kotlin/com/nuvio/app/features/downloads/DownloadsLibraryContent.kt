package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioStatusModal
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared download rows inside the library's own header and scrolling container. */
@Composable
internal fun rememberDownloadsLibraryContent(onOpenDownload: (DownloadItem) -> Unit): LazyListScope.() -> Unit {
    val state by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    var showId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    pendingDeletion?.let { id ->
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = { DownloadsRepository.cancelDownload(id); pendingDeletion = null },
            onDismiss = { pendingDeletion = null },
        )
    }
    return {
        val selectedShow = showId
        if (selectedShow == null) {
            downloadsRootContent(state, onOpenDownload, { id, _ -> showId = id }, { pendingDeletion = it })
        } else {
            item(key = "download-library-show-back") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.compose_settings_root_downloads_title))
                    }
                    Text(state.completedItems.firstOrNull { it.parentMetaId == selectedShow }?.title.orEmpty())
                }
            }
            downloadsShowContent(selectedShow, state.completedItems.filter { it.isEpisode }, onOpenDownload, { pendingDeletion = it })
        }
    }
}
