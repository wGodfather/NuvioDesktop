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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the disposable QA emulator without changing its existing download records. */
@RunWith(AndroidJUnit4::class)
class LibraryDownloadsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun downloadsLivesBesideSavedAndCloudAndCanBeOpenedWithoutSettings() {
        // The disposable CI emulator uses English. Assert the user-visible labels
        // without exporting the app module's internal generated resource API.
        val saved = "Saved"
        val cloud = "Cloud"
        val downloads = "Downloads"
        val empty = "No downloads yet"
        val active = "Active"
        val movies = "Movies"
        val shows = "Shows"
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
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
