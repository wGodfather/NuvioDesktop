package com.nuvio.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live tests on a disposable emulator, never a personal profile. */
@RunWith(AndroidJUnit4::class)
class ForkAndroidIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun turkishSearchGenreAndPagination() {
        val manifest = arguments.getString("manifest")
        assumeNotNull(manifest)
        launch().use { scenario ->
            scenario.onActivity { AddonRepository.initialize() }
            runBlocking {
                AddonRepository.addAddon(requireNotNull(manifest))
                AddonRepository.awaitManifestsLoaded()
            }
            val addons = AddonRepository.uiState.value.addons.filter { it.manifestUrl == manifest }
            assertEquals(1, addons.size)
            assertNotNull(addons.single().manifest)
            assertTrue(addons.single().manifest!!.catalogs.size >= 5)
            scenario.onActivity { SearchRepository.search("Suits", addons, forceRefresh = true) }
            await { !SearchRepository.uiState.value.isLoading }
            val search = SearchRepository.uiState.value
            assertNull(search.errorMessage)
            assertTrue("Suits (2011) missing: $search", search.sections.flatMap { it.items }.any { it.id == "tt1632701" })
            scenario.onActivity { SearchRepository.refreshDiscover(addons, forceRefresh = true) }
            await { !SearchRepository.discoverUiState.value.isLoading }
            // Discover shows catalog choices for the selected media type only.
            assertTrue(SearchRepository.discoverUiState.value.catalogOptions.isNotEmpty())
            val movie = SearchRepository.discoverUiState.value.catalogOptions.first { it.type == "movie" }
            scenario.onActivity { SearchRepository.selectDiscoverCatalog(movie.key) }
            await { !SearchRepository.discoverUiState.value.isLoading }
            assertTrue(SearchRepository.discoverUiState.value.genreOptions.contains("Bilim-Kurgu"))
            scenario.onActivity { SearchRepository.selectDiscoverGenre("Bilim-Kurgu") }
            await { !SearchRepository.discoverUiState.value.isLoading }
            val first = SearchRepository.discoverUiState.value
            assertNull(first.errorMessage)
            assertEquals("Bilim-Kurgu", first.selectedGenre)
            assertTrue(first.items.isNotEmpty())
            assertTrue(first.canLoadMore)
            scenario.onActivity { SearchRepository.loadMoreDiscover() }
            await { !SearchRepository.discoverUiState.value.isLoading }
            val second = SearchRepository.discoverUiState.value
            assertNull(second.errorMessage)
            assertTrue(second.items.size > first.items.size)
            assertEquals(second.items.size, second.items.map { it.id }.distinct().size)
        }
    }

    @Test
    fun nativeTorrentReachesCompletedWithMatchingBytes() {
        val magnet = arguments.getString("magnet")
        val hash = arguments.getString("sha256")
        val bytes = arguments.getString("bytes")?.toLong()
        assumeNotNull(magnet, hash, bytes)
        launch().use { scenario ->
            scenario.onActivity {
                DownloadsRepository.enqueueFromStream(
                    contentType = "movie", videoId = FIXTURE_ID,
                    parentMetaId = FIXTURE_ID, parentMetaType = "movie", title = "Native torrent QA",
                    logo = null, poster = null, background = null, seasonNumber = null,
                    episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                    stream = StreamItem(
                        url = magnet, fileIdx = 0, addonName = "Local QA", addonId = "local.qa",
                        behaviorHints = StreamBehaviorHints(filename = "fixture.mp4"),
                    ),
                )
            }
            await(timeoutMs = 120_000) {
                DownloadsRepository.uiState.value.items.any {
                    it.videoId == FIXTURE_ID && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed)
                }
            }
            val item = DownloadsRepository.uiState.value.items.single { it.videoId == FIXTURE_ID }
            assertEquals("${item.errorMessage}", DownloadStatus.Completed, item.status)
            val file = File(URI(requireNotNull(item.localFileUri)))
            assertEquals(bytes, file.length())
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual)
            assertTrue(item.sourceUrl.startsWith("magnet:"))
        }
    }

    private fun await(timeoutMs: Long = 60_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Timed out waiting for Android operation", check())
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    companion object { private const val FIXTURE_ID = "nuvio-native-torrent-fixture" }
}
