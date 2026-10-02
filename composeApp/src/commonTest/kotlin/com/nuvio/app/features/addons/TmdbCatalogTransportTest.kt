package com.nuvio.app.features.addons

import com.nuvio.app.features.catalog.catalogNextSkip
import com.nuvio.app.features.catalog.supportsPagination
import com.nuvio.app.features.home.HomeCatalogParser
import com.nuvio.app.features.details.MetaDetailsParser
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class TmdbCatalogTransportTest {
    @Test fun `legacy static manifest acquires genres pagination and two search catalogs`() {
        val manifest = AddonManifestParser.parse("https://catalog-test.example/manifest.json", """{
          "id":"community.nuvio.boat","name":"Test","version":"1.7.6","types":["movie","series"],"resources":["catalog","meta"],
          "catalogs":[{"id":"tmdb_trending_movies","type":"movie"},{"id":"tmdb_top_movies","type":"movie"},{"id":"tmdb_popular_series","type":"series"},{"id":"tmdb_anime","type":"series"}]}
        """)
        assertTrue(manifest.catalogs.all { it.supportsPagination() })
        assertEquals(2, manifest.catalogs.count { c -> c.extra.any { it.name == "search" } })
        assertTrue("Bilim-Kurgu" in manifest.catalogs.first().extra.first { it.name == "genre" }.options)
        assertEquals("https://catalog-test.example/manifest.json", manifest.transportUrl)
    }

    @Test fun `ordinary Stremio addon is unchanged`() {
        val manifest = AddonManifestParser.parse("https://other.example/manifest.json", """{"id":"other","name":"Other","version":"1","types":["movie"],"resources":["catalog"],"catalogs":[{"id":"tmdb_trending_movies","type":"movie","extra":[{"name":"search"}]}]}""")
        assertFalse(manifest.catalogs.first().supportsPagination())
        assertEquals(1, manifest.catalogs.first().extra.size)
    }

    @Test fun `Turkish search is encoded and resolves true IMDb IDs`() = runBlocking {
        val urls = mutableListOf<Url>()
        val client = client { url ->
            urls += url
            if (url.encodedPath.endsWith("external_ids")) """{"imdb_id":"tt0068646"}"""
            else """{"page":1,"total_pages":44,"results":[{"id":238,"title":"Baba","release_date":"1972-03-14","genre_ids":[18,80]}]}"""
        }
        val data = client.catalog("movie", "tmdb_trending_movies", mapOf("search" to "Örümcek & Baba/İ"))
        val query = urls.first()
        assertEquals("/3/search/movie", query.encodedPath)
        assertEquals("Örümcek & Baba/İ", query.parameters["query"])
        assertEquals("tr-TR", query.parameters["language"])
        val item = HomeCatalogParser.parseCatalog(data.toString()).single()
        assertEquals("tt0068646", item.id)
        assertEquals("Baba", item.name)
        assertEquals(20, catalogNextSkip(data.toString(), 0, 1))
    }

    @Test fun `page two keeps search query and stops on final partial page`() = runBlocking {
        val client = client { url ->
            assertEquals("/3/search/movie", url.encodedPath)
            assertEquals("Baba", url.parameters["query"])
            assertEquals("2", url.parameters["page"])
            """{"page":2,"total_pages":2,"results":[]}"""
        }
        val data = client.catalog("movie", "tmdb_trending_movies", mapOf("search" to "Baba", "skip" to "20"))
        assertNull(catalogNextSkip(data.toString(), 20, 0))
    }

    @Test fun `genre applies on discover endpoint and unrelated results are excluded`() = runBlocking {
        val client = client { url ->
            if (url.encodedPath.endsWith("external_ids")) """{"imdb_id":"tt1234567"}"""
            else {
                assertEquals("/3/discover/movie", url.encodedPath)
                assertEquals("878", url.parameters["with_genres"])
                """{"total_pages":3,"results":[{"id":1,"title":"Science fiction","genre_ids":[878]},{"id":2,"title":"Wrong genre","genre_ids":[35]}]}"""
            }
        }
        assertEquals(listOf("Science fiction"), HomeCatalogParser.parseCatalog(client.catalog("movie", "tmdb_trending_movies", mapOf("genre" to "Bilim-Kurgu")).toString()).map { it.name })
    }

    @Test fun `missing external ID uses TMDB namespace and never invents IMDb ID`() = runBlocking {
        val client = client { url -> if (url.encodedPath.endsWith("external_ids")) """{"imdb_id":null}""" else """{"total_pages":1,"results":[{"id":999,"title":"Test","genre_ids":[]}]}""" }
        val item = HomeCatalogParser.parseCatalog(client.catalog("movie", "tmdb_trending_movies", emptyMap()).toString()).single()
        assertEquals("tmdb:999", item.id)
    }

    @Test fun `empty search succeeds while server failure remains an error`() = runBlocking {
        val empty = client { """{"total_pages":0,"results":[]}""" }.catalog("movie", "tmdb_trending_movies", mapOf("search" to "nosuchtitle"))
        assertTrue(HomeCatalogParser.parseCatalog(empty.toString()).isEmpty())
        assertNull(catalogNextSkip(empty.toString(), 0, 0))
        assertFailsWith<IllegalStateException> {
            client { error("HTTP 500") }.catalog("movie", "tmdb_trending_movies", emptyMap())
        }
        Unit
    }

    @Test fun `new search result has real metadata without a static JSON file`() = runBlocking {
        val client = client { url ->
            assertEquals("/3/movie/238", url.encodedPath)
            """{"id":238,"title":"Baba","original_title":"The Godfather","release_date":"1972-03-14","overview":"Test","runtime":175,"genres":[{"name":"Dram"}],"external_ids":{"imdb_id":"tt0068646"},"credits":{"cast":[{"name":"Marlon Brando"}],"crew":[{"job":"Director","name":"Francis Ford Coppola"}]},"videos":{"results":[]}}"""
        }
        val meta = MetaDetailsParser.parse(client.meta("movie", "tmdb:238").toString())
        assertEquals("tt0068646", meta.id)
        assertEquals("tt0068646", meta.imdbId)
        assertEquals("175 min", meta.runtime)
        assertEquals(listOf("Francis Ford Coppola"), meta.director)
    }

    @Test fun `static addon pagination retains existing raw count behavior`() {
        assertEquals(40, catalogNextSkip("""{"metas":[]}""", 20, 20))
        assertNull(catalogNextSkip("""{"metas":[],"nuvioNextSkip":null}""", 20, 7))
    }

    private fun client(fetch: (Url) -> String) = TmdbCatalogClient("tr-TR", { "test-key" }) { url, _ -> fetch(Url(url)) }
}
