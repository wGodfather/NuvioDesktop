package com.nuvio.app.features.streams

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.nuvio.app.features.downloads.DownloadStatus
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StreamDownloadButtonTest {
    @get:Rule val compose = createComposeRule()
    private var plays = 0
    private var downloads = 0
    private var buttonLabel = ""

    private fun show(url: String = "https://example.invalid/video.mp4", status: DownloadStatus? = null) {
        compose.setContent {
            buttonLabel = stringResource(when {
                url.endsWith(".m3u8") -> Res.string.downloads_enqueue_unsupported_format
                status != null -> Res.string.streams_download_in_library
                else -> Res.string.streams_download_file
            })
            MaterialTheme {
                CompositionLocalProvider(LocalStreamDownloadAction provides StreamDownloadAction({ downloads++ }, false, status)) {
                    StreamCard(
                        stream = StreamItem(name = "Test movie 1080p", url = url, addonName = "Example", addonId = "example"),
                        enabled = true, appendInstantServiceToDefaultName = false, showFileSizeBadges = false,
                        showAddonLogo = false, badgePlacement = StreamBadgePlacement.BOTTOM,
                        onClick = { plays++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun downloadButtonDoesNotStartPlayback() {
        show()
        compose.onNodeWithContentDescription(buttonLabel).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(0, plays) }
        compose.onNodeWithText("Test movie 1080p").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, plays) }
    }

    @Test fun unsupportedAndroidHlsButtonDoesNotTriggerTheParentRow() {
        show("https://example.invalid/video.m3u8")
        compose.onNodeWithContentDescription(buttonLabel).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, downloads); assertEquals(0, plays) }
    }

    @Test fun completedDownloadCannotBeReplacedByAnotherButtonTap() {
        show(status = DownloadStatus.Completed)
        compose.onNodeWithContentDescription(buttonLabel).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, downloads); assertEquals(0, plays) }
    }
}
