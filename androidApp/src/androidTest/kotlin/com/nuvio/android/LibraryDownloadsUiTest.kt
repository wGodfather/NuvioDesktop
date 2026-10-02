package com.nuvio.android

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.MainActivity
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.downloads.DownloadsRepository
import java.io.File
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the disposable QA emulator without changing its existing download records. */
@RunWith(AndroidJUnit4::class)
class LibraryDownloadsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun downloadsLivesBesideSavedAndCloudAndCanBeOpenedWithoutSettings() {
        var saved = ""
        var cloud = ""
        var downloads = ""
        var empty = ""
        var active = ""
        var movies = ""
        var shows = ""
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            saved = stringResource(Res.string.library_source_saved)
            cloud = stringResource(Res.string.library_source_cloud)
            downloads = stringResource(Res.string.compose_settings_root_downloads_title)
            empty = stringResource(Res.string.downloads_empty_title)
            active = stringResource(Res.string.downloads_section_active)
            movies = stringResource(Res.string.downloads_section_movies)
            shows = stringResource(Res.string.downloads_section_shows)
            NuvioTheme { LibraryScreen() }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(saved).assertIsDisplayed()
        compose.onNodeWithText(cloud).assertIsDisplayed()
        compose.onNodeWithText(downloads).assertIsDisplayed().performClick()
        val state = DownloadsRepository.uiState.value
        val contentLabel = when {
            state.activeItems.isNotEmpty() -> active
            state.completedItems.any { !it.isEpisode } -> movies
            state.completedItems.isNotEmpty() -> shows
            else -> empty
        }
        compose.onNodeWithText(contentLabel).assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val destination = File(instrumentation.targetContext.getExternalFilesDir(null), "fork-ui-qa/library-downloads.png")
        destination.parentFile!!.mkdirs()
        destination.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithText(saved).performClick()
        compose.onNodeWithText(downloads).performClick()
        compose.onNodeWithText(contentLabel).assertIsDisplayed()
    }
}
