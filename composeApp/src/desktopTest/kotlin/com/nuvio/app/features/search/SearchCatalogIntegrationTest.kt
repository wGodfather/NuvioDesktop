package com.nuvio.app.features.search

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.catalog.*
import com.nuvio.app.features.details.MetaDetailsParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class SearchCatalogIntegrationTest {
    @Test fun `empty catalog response is no results rather than a failed request`() = runBlocking {
        withServer { server, manifestUrl ->
            server.createContext("/catalog/") { exchange ->
                val bytes = """{"metas":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            SearchRepository.reset()
            try {
                SearchRepository.search("no-such-title", listOf(addon(manifestUrl)))
                val state = withTimeout(10_000) { SearchRepository.uiState.first { !it.isLoading } }
                assertEquals(SearchEmptyStateReason.NoResults, state.emptyStateReason)
                assertNull(state.errorMessage)
            } finally { SearchRepository.reset() }
        }
    }

    @Test fun `search catalog view retains query through load more`() = runBlocking {
        withServer { server, manifestUrl ->
            val paths = CopyOnWriteArrayList<String>()
            server.createContext("/catalog/") { exchange ->
                val path = exchange.requestURI.rawPath
                paths += path
                val page = if ("skip=20" in path) 2 else 1
                val metas = (1..20).joinToString(",") { """{"id":"page${page}item$it","type":"movie","name":"Result $page-$it"}""" }
                val bytes = """{"metas":[$metas]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val repo = CatalogRepository
            repo.clear()
            try {
                repo.load(CatalogTarget.Addon(manifestUrl, "movie", "test", supportsPagination = true, search = "Baba"))
                withTimeout(10_000) { repo.uiState.first { !it.isLoading && it.items.size == 20 } }
                repo.loadMore()
                val state = withTimeout(10_000) { repo.uiState.first { !it.isLoading && it.items.size == 40 } }
                assertEquals(40, state.items.size)
                assertEquals(2, paths.size)
                assertTrue(paths.all { "/search=Baba" in it })
                assertTrue("skip=20" in paths.last())
            } finally { repo.clear() }
        }
    }

    /** Opt-in live check: no network dependency in the ordinary test suite. */
    @Test fun `live legacy addon search genres next page and metadata`() = runBlocking {
        if (System.getenv("NUVIO_LIVE_CATALOG_TEST") != "1") return@runBlocking
        val manifestUrl = "https://raw.githubusercontent.com/dr-octagon/nuvio/main/manifest.json"
        val manifest = AddonManifestParser.parse(manifestUrl, httpGetText(manifestUrl))
        assertTrue(manifest.catalogs.all { it.supportsPagination() })
        val first = fetchCatalogPage(manifestUrl, "movie", "tmdb_trending_movies", search = "Baba")
        assertTrue(first.items.any { it.id == "tt0068646" && it.name == "Baba" })
        val second = fetchCatalogPage(manifestUrl, "movie", "tmdb_trending_movies", search = "Baba", skip = assertNotNull(first.nextSkip))
        assertTrue(second.items.isNotEmpty())
        assertTrue(second.items.map { it.id }.toSet() != first.items.map { it.id }.toSet())
        val genre = fetchCatalogPage(manifestUrl, "movie", "tmdb_trending_movies", genre = "Bilim-Kurgu")
        assertTrue(genre.items.isNotEmpty())
        assertTrue(genre.items.all { "Bilim-Kurgu" in it.genres })
        val meta = MetaDetailsParser.parse(fetchAddonResponseText(buildAddonResourceUrl(manifestUrl, "meta", "movie", "tt0068646")))
        assertEquals("Baba", meta.name)
        assertEquals("tt0068646", meta.imdbId)
        val empty = fetchCatalogPage(manifestUrl, "movie", "tmdb_trending_movies", search = "zzqnonexistent987654321")
        assertTrue(empty.items.isEmpty())
        assertNull(empty.nextSkip)
        val series = fetchCatalogPage(manifestUrl, "series", "tmdb_popular_series", search = "Breaking Bad")
        assertTrue(series.items.any { it.id == "tt0903747" })
        for ((catalogId, type) in TMDB_CATALOG_TYPES) {
            val browse = fetchCatalogPage(manifestUrl, type, catalogId)
            assertTrue(browse.items.isNotEmpty(), "Empty catalog: $catalogId")
            assertNotNull(browse.nextSkip, "No next page: $catalogId")
        }
        val tvMeta = MetaDetailsParser.parse(fetchAddonResponseText(buildAddonResourceUrl(manifestUrl, "meta", "series", "tt0903747")))
        assertEquals("tt0903747", tvMeta.imdbId)
        assertTrue(tvMeta.videos.isNotEmpty())
        println("LIVE_CATALOG_OK search=${first.items.size} nextPage=${second.items.size} genre=${genre.items.size} metadata=${meta.name} empty=0")
        println("LIVE_TV_OK search=${series.items.size} episodes=${tvMeta.videos.size} catalogs=5")
    }

    private fun addon(url: String) = ManagedAddon(url, AddonManifestParser.parse(url, """{"id":"fixture","name":"Fixture","version":"1","resources":["catalog"],"types":["movie"],"catalogs":[{"id":"test","type":"movie","extra":[{"name":"search"}]}]}"""))
    private suspend fun withServer(block: suspend (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}/manifest.json") }
        finally { server.stop(0) }
    }
}
