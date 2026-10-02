package com.nuvio.app.features.addons

import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** A declared TMDB catalog is executed locally: GitHub Raw cannot execute Stremio routes. */
internal object TmdbCatalogTransport {
    private val clients = MutableStateFlow<Map<String, TmdbCatalogClient>>(emptyMap())

    fun configure(manifest: AddonManifest, declaration: JsonObject?): AddonManifest {
        val legacy = manifest.id == "community.nuvio.boat" &&
            manifest.catalogs.any { it.id in TMDB_CATALOG_TYPES }
        if (declaration == null && !legacy) return manifest
        if (declaration != null && declaration.text("provider") != "tmdb") return manifest
        val base = addonTransportBaseUrl(manifest.transportUrl)
        val language = declaration?.text("language") ?: "tr-TR"
        val declaredKey = declaration?.text("apiKey")
        clients.update { current ->
            current + (base to TmdbCatalogClient(
                language = language,
                apiKey = {
                    declaredKey ?: Json.parseToJsonElement(httpGetText("$base/config.json"))
                        .jsonObject["api_keys"]?.jsonObject?.text("tmdb")
                        ?: error("TMDB katalog anahtarı bulunamadı")
                },
                getText = { url, refresh ->
                    if (refresh) httpGetTextWithHeaders(url, mapOf("Cache-Control" to "no-cache"))
                    else httpGetText(url)
                },
            ))
        }
        return manifest.copy(catalogs = manifest.catalogs.map { catalog ->
            if (TMDB_CATALOG_TYPES[catalog.id] != catalog.type) return@map catalog
            catalog.copy(extra = buildList {
                // One general search per media type avoids repeating the same results in every row.
                if (catalog.id in TMDB_SEARCH_CATALOGS) add(AddonExtraProperty("search"))
                add(AddonExtraProperty("genre", options = tmdbGenres(catalog.type).keys.toList(), optionsLimit = 1))
                add(AddonExtraProperty("skip"))
            })
        })
    }

    suspend fun fetchResource(url: String, forceRefresh: Boolean): String? {
        val entry = clients.value.entries.firstOrNull { url.startsWith("${it.key}/catalog/") || url.startsWith("${it.key}/meta/") }
            ?: return null
        val parts = url.removePrefix("${entry.key}/").substringBefore('?').removeSuffix(".json").split('/')
        if (parts.size !in 3..4) return null
        val resource = parts[0]
        val type = parts[1]
        val id = parts[2].decodeURLPart()
        if (resource == "catalog" && TMDB_CATALOG_TYPES[id] == type) {
            val extra = parts.getOrNull(3).orEmpty().split('&').filter { '=' in it }.associate {
                it.substringBefore('=').decodeURLPart() to it.substringAfter('=').decodeURLPart()
            }
            return entry.value.catalog(type, id, extra, forceRefresh).toString()
        }
        if (resource == "meta" && type in listOf("movie", "series") && (id.startsWith("tt") || id.startsWith("tmdb:"))) {
            return entry.value.meta(type, id, forceRefresh).toString()
        }
        return null
    }
}

internal val TMDB_CATALOG_TYPES = mapOf(
    "tmdb_trending_movies" to "movie", "tmdb_now_playing" to "movie", "tmdb_top_movies" to "movie",
    "tmdb_popular_series" to "series", "tmdb_anime" to "series",
)
internal val TMDB_SEARCH_CATALOGS = setOf("tmdb_trending_movies", "tmdb_popular_series")

internal fun tmdbGenres(type: String): Map<String, Int> = if (type == "movie") linkedMapOf(
    "Aksiyon" to 28, "Macera" to 12, "Animasyon" to 16, "Komedi" to 35, "Suç" to 80,
    "Belgesel" to 99, "Dram" to 18, "Aile" to 10751, "Fantastik" to 14, "Tarih" to 36,
    "Korku" to 27, "Müzik" to 10402, "Gizem" to 9648, "Romantik" to 10749,
    "Bilim-Kurgu" to 878, "TV film" to 10770, "Gerilim" to 53, "Savaş" to 10752, "Vahşi Batı" to 37,
) else linkedMapOf(
    "Aksiyon & Macera" to 10759, "Animasyon" to 16, "Komedi" to 35, "Suç" to 80, "Belgesel" to 99,
    "Dram" to 18, "Aile" to 10751, "Çocuk" to 10762, "Gizem" to 9648, "Haber" to 10763,
    "Reality" to 10764, "Bilim Kurgu & Fantazi" to 10765, "Pembe Dizi" to 10766,
    "Talk" to 10767, "Savaş & Politik" to 10768, "Vahşi Batı" to 37,
)

internal class TmdbCatalogClient(
    private val language: String,
    private val apiKey: suspend () -> String,
    private val getText: suspend (String, Boolean) -> String,
) {
    private val keyMutex = Mutex()
    private var cachedKey: String? = null
    private val mappingMutex = Mutex()
    private val imdbIds = linkedMapOf<String, String>()
    private val requests = Semaphore(4)

    private suspend fun request(endpoint: String, params: Map<String, String> = emptyMap(), refresh: Boolean = false): JsonObject {
        val key = keyMutex.withLock { cachedKey ?: apiKey().also { cachedKey = it } }
        val query = (mapOf("api_key" to key, "language" to language) + params).entries.joinToString("&") {
            "${it.key.encodeAddonPathSegment()}=${it.value.encodeAddonPathSegment()}"
        }
        return requests.withPermit {
            try { withTimeout(30_000) {
                val result = Json.parseToJsonElement(getText("https://api.themoviedb.org/3/$endpoint?$query", refresh)).jsonObject
                check(result["success"]?.jsonPrimitive?.booleanOrNull != false) { "TMDB katalog isteği başarısız" }
                result
            } } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("TMDB katalog isteği zaman aşımına uğradı", timeout)
            }
        }
    }

    private suspend fun mediaId(media: String, tmdbId: String, refresh: Boolean): String {
        val key = "$media:$tmdbId"
        if (!refresh) mappingMutex.withLock { imdbIds[key] }?.let { return it }
        val id = try {
            request("$media/$tmdbId/external_ids", refresh = refresh).text("imdb_id")
                ?.takeIf { it.matches(Regex("tt[0-9]+")) } ?: "tmdb:$tmdbId"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A real TMDB ID is usable by plugins. Never fabricate an IMDb ID from a TMDB number.
            "tmdb:$tmdbId"
        }
        mappingMutex.withLock {
            imdbIds[key] = id
            if (imdbIds.size > 1500) imdbIds.remove(imdbIds.keys.first())
        }
        return id
    }

    suspend fun catalog(type: String, id: String, extra: Map<String, String>, refresh: Boolean = false): JsonObject = coroutineScope {
        require(TMDB_CATALOG_TYPES[id] == type) { "Bilinmeyen TMDB kataloğu" }
        val skip = extra["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = skip / 20 + 1
        if (page > 500) return@coroutineScope catalogEnvelope(emptyList(), null)
        val media = if (type == "movie") "movie" else "tv"
        val search = extra["search"]?.trim()?.takeIf { it.isNotEmpty() }
        val genre = extra["genre"]?.takeIf { it.isNotBlank() }?.let {
            tmdbGenres(type)[it] ?: error("Bilinmeyen tür: $it")
        }
        val params = linkedMapOf("page" to page.toString(), "include_adult" to "false")
        val endpoint = if (search != null) {
            params["query"] = search
            "search/$media"
        } else if (id == "tmdb_now_playing" && genre == null) {
            "movie/now_playing"
        } else {
            params["sort_by"] = if (id == "tmdb_top_movies") "vote_average.desc" else "popularity.desc"
            // Browsing is not limited to titles exceeding arbitrary vote thresholds.
            if (id == "tmdb_top_movies") params["vote_count.gte"] = "1500"
            if (id == "tmdb_anime") {
                params["with_genres"] = "16"
                params["with_original_language"] = "ja"
            }
            if (genre != null) params["with_genres"] = listOfNotNull(params["with_genres"], genre.toString()).distinct().joinToString(",")
            if (id == "tmdb_now_playing") {
                val dates = request("movie/now_playing", refresh = refresh)["dates"]?.jsonObject
                dates?.text("minimum")?.let { params["release_date.gte"] = it }
                dates?.text("maximum")?.let { params["release_date.lte"] = it }
                params["with_release_type"] = "2|3"
            }
            "discover/$media"
        }
        val data = request(endpoint, params, refresh)
        val raw = data.array("results").mapNotNull { it as? JsonObject }.drop(skip % 20)
        val items = raw.filter { item ->
            val genres = item.array("genre_ids").mapNotNull { it.jsonPrimitive.intOrNull }
            (genre == null || genre in genres) &&
                (search == null || id != "tmdb_anime" || (16 in genres && item.text("original_language") == "ja"))
        }.map { item -> async {
            val tmdbId = item.text("id") ?: return@async null
            preview(item, type, mediaId(media, tmdbId, refresh))
        } }.awaitAll().filterNotNull()
        val totalPages = data["total_pages"]?.jsonPrimitive?.intOrNull ?: page
        catalogEnvelope(items, (page * 20).takeIf { page < totalPages.coerceAtMost(500) })
    }

    suspend fun meta(type: String, id: String, refresh: Boolean = false): JsonObject = coroutineScope {
        val media = if (type == "movie") "movie" else "tv"
        val tmdbId = if (id.startsWith("tmdb:")) id.removePrefix("tmdb:") else {
            val found = request("find/$id", mapOf("external_source" to "imdb_id"), refresh)
            (found.array(if (type == "movie") "movie_results" else "tv_results").firstOrNull() as? JsonObject)?.text("id")
                ?: error("İçerik TMDB'de bulunamadı")
        }
        require(tmdbId.all { it.isDigit() } && tmdbId.isNotEmpty())
        val details = request("$media/$tmdbId", mapOf("append_to_response" to "external_ids,credits,videos"), refresh)
        val actualId = details["external_ids"]?.jsonObject?.text("imdb_id")?.takeIf { it.matches(Regex("tt[0-9]+")) }
            ?: if (id.startsWith("tt")) id else "tmdb:$tmdbId"
        val genres = details.array("genres").mapNotNull { (it as? JsonObject)?.text("name") }
        val credits = details["credits"] as? JsonObject
        val videos = if (type == "series") {
            details.array("seasons").mapNotNull { it as? JsonObject }.map { season -> async {
                val number = season["season_number"]?.jsonPrimitive?.intOrNull ?: return@async emptyList<JsonObject>()
                request("tv/$tmdbId/season/$number", refresh = refresh).array("episodes").mapNotNull { episode ->
                    val ep = episode as? JsonObject ?: return@mapNotNull null
                    val n = ep["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    buildJsonObject {
                        put("id", "$actualId:$number:$n"); put("title", ep.text("name") ?: "Bölüm $n")
                        put("season", number); put("episode", n)
                        ep.text("air_date")?.let { put("released", "${it}T00:00:00.000Z") }
                        ep.text("overview")?.let { put("overview", it) }
                        image(ep.text("still_path"), "w500")?.let { put("thumbnail", it) }
                    }
                }
            } }.awaitAll().flatten()
        } else emptyList()
        val meta = buildJsonObject {
            preview(details, type, actualId).forEach { (key, value) -> put(key, value) }
            put("tmdbId", tmdbId); put("originalName", details.text(if (type == "movie") "original_title" else "original_name") ?: "")
            if (actualId.startsWith("tt")) put("imdb_id", actualId)
            put("genres", JsonArray(genres.map(::JsonPrimitive)))
            val minutes = details["runtime"]?.jsonPrimitive?.intOrNull ?: details.array("episode_run_time").firstOrNull()?.jsonPrimitive?.intOrNull
            minutes?.let { put("runtime", "$it min") }
            put("cast", JsonArray(credits?.array("cast").orEmpty().take(12).mapNotNull { (it as? JsonObject)?.text("name") }.map(::JsonPrimitive)))
            put("director", JsonArray(credits?.array("crew").orEmpty().mapNotNull { it as? JsonObject }.filter { it.text("job") == "Director" }.mapNotNull { it.text("name") }.map(::JsonPrimitive)))
            put("videos", JsonArray(videos))
            details.text("status")?.let { put("status", it) }
            details.text("last_air_date")?.let { put("lastAirDate", it) }
            put("trailers", JsonArray((details["videos"] as? JsonObject)?.array("results").orEmpty().mapNotNull { it as? JsonObject }.filter { it.text("site") == "YouTube" && it.text("type") == "Trailer" }.mapNotNull { it.text("key") }.take(3).map { buildJsonObject { put("source", it); put("type", "Trailer") } }))
        }
        buildJsonObject { put("meta", meta) }
    }

    private fun preview(item: JsonObject, type: String, id: String): JsonObject = buildJsonObject {
        put("id", id); put("type", type); put("name", item.text(if (type == "movie") "title" else "name") ?: "")
        image(item.text("poster_path"), "w500")?.let { put("poster", it) }
        image(item.text("backdrop_path"), "w1280")?.let { put("background", it) }
        item.text("overview")?.let { put("description", it) }
        item.text(if (type == "movie") "release_date" else "first_air_date")?.takeIf { it.length >= 4 }?.let {
            put("releaseInfo", it.take(4)); put("released", "${it}T00:00:00.000Z")
        }
        item.text("vote_average")?.let { put("imdbRating", it) }
        put("genres", JsonArray(item.array("genre_ids").mapNotNull { value -> tmdbGenres(type).entries.firstOrNull { it.value == value.jsonPrimitive.intOrNull }?.key }.map(::JsonPrimitive)))
    }

    private fun catalogEnvelope(items: List<JsonObject>, next: Int?): JsonObject = buildJsonObject {
        put("metas", JsonArray(items)); put("nuvioNextSkip", next?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun image(path: String?, size: String): String? = path?.takeIf { it.startsWith('/') }?.let {
        "https://wsrv.nl/?url=https://image.tmdb.org/t/p/$size$it"
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
