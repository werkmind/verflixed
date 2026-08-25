package com.streamvault.tv.data.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDb metadata — same model as Plex/Kodi: the **app** ships a public scraper key,
 * the user never creates a TMDb account.
 *
 * Built-in key is the public Team-Kodi TMDb scraper key (GPL, published in
 * metadata.tvshows.themoviedb.org.python as TMDB_CLOWNCAR).
 */
class TmdbClient(
    private val http: OkHttpClient,
    moshi: Moshi,
    private val prefs: UserPrefs
) {
    private val searchAdapter = moshi.adapter(TmdbSearchResponse::class.java)
    private val detailsAdapter = moshi.adapter(TmdbTvDetails::class.java)
    private val movieYearCache = ConcurrentHashMap<String, Int>()

    private fun apiKey(): String = prefs.tmdbApiKey.ifBlank { APP_KEY }

    suspend fun enrich(series: Series): Series = withContext(Dispatchers.IO) {
        val key = apiKey()
        runCatching {
            if (series.isMovie) enrichMovie(series, key) else enrichTv(series, key)
        }.getOrDefault(series)
    }

    private fun enrichTv(series: Series, key: String): Series {
        val id = series.tmdbId
            ?: findId(series.imdbId, movie = false, key)
            ?: searchId("tv", series.title, key)
            ?: return series
        val details = details("tv", id, key) ?: return series
        return apply(series, id, details)
    }

    private fun enrichMovie(series: Series, key: String): Series {
        val id = series.tmdbId
            ?: findId(series.imdbId, movie = true, key)
            ?: searchId("movie", series.title, key)
            ?: return series
        val details = details("movie", id, key) ?: return series
        return apply(series, id, details)
    }

    private fun apply(series: Series, id: Int, details: TmdbTvDetails): Series {
        val deTitle = (details.name ?: details.title)?.trim()?.takeIf { it.isNotBlank() }
        val deOverview = details.overview?.trim()?.takeIf { it.isNotBlank() }
        val poster = details.posterPath?.let { "$POSTER_BASE$it" }
        val backdrop = details.backdropPath?.let { "$BACKDROP_BASE$it" }
        val siteBackdrop = series.backdropUrl?.takeIf { it != series.posterUrl }
        return series.copy(
            tmdbId = id,
            imdbId = series.imdbId ?: details.externalIds?.imdbId,
            title = deTitle ?: series.title,
            overview = deOverview ?: series.overview,
            posterUrl = series.posterUrl ?: poster,
            backdropUrl = siteBackdrop ?: backdrop ?: series.backdropUrl ?: poster,
            year = series.year
                ?: details.firstAirDate?.take(4)?.toIntOrNull()
                ?: details.releaseDate?.take(4)?.toIntOrNull(),
            rating = series.rating ?: details.voteAverage?.takeIf { it > 0.0 },
            runtimeMinutes = series.runtimeMinutes
                ?: details.episodeRunTime?.firstOrNull()?.takeIf { it > 0 }
                ?: details.runtime?.takeIf { it > 0 },
            status = series.status ?: details.status?.takeIf { it.isNotBlank() },
            genres = series.genres.ifEmpty {
                details.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            },
        )
    }

    /**
     * Fill episode gaps (German titles, overviews, stills, air dates) from TMDb
     * season data. Site markup often ships "Episode N" placeholders and unmarked
     * future episodes — TMDb air dates make DEMNÄCHST reliable.
     */
    suspend fun enrichEpisodes(series: Series): Series = withContext(Dispatchers.IO) {
        val id = series.tmdbId ?: return@withContext series
        if (series.isMovie || series.seasons.isEmpty()) return@withContext series
        val key = apiKey()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val seasons = series.seasons.map { season ->
            val body = get(
                "https://api.themoviedb.org/3/tv/$id/season/${season.number}".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("api_key", key)
                    .addQueryParameter("language", "de-DE")
                    .build()
                    .toString()
            ) ?: return@map season
            val byNumber = runCatching {
                val arr = JSONObject(body).optJSONArray("episodes") ?: return@runCatching emptyMap()
                buildMap {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        put(o.optInt("episode_number"), o)
                    }
                }
            }.getOrDefault(emptyMap<Int, JSONObject>())
            if (byNumber.isEmpty()) return@map season
            season.copy(
                episodes = season.episodes.map { ep ->
                    val meta = byNumber[ep.number] ?: return@map ep
                    val name = meta.optString("name").takeIf {
                        it.isNotBlank() && !it.equals("Episode ${ep.number}", ignoreCase = true)
                    }
                    val overview = meta.optString("overview").takeIf { it.isNotBlank() }
                    val still = meta.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { "$STILL_BASE$it" }
                    val airDate = meta.optString("air_date").takeIf { it.isNotBlank() && it != "null" }
                    val futureAir = airDate != null && airDate > today
                    ep.copy(
                        title = name ?: ep.title,
                        overview = overview ?: ep.overview,
                        stillUrl = ep.stillUrl ?: still,
                        airDate = airDate,
                        upcoming = ep.upcoming || futureAir,
                    )
                }
            )
        }
        series.copy(seasons = seasons)
    }

    /**
     * Real-world release year for a movie title (search endpoint only, cached).
     * Used to rank "Neu" shelves by actual release, not platform-added date.
     */
    suspend fun movieReleaseYear(title: String): Int? = withContext(Dispatchers.IO) {
        val q = title.trim()
        if (q.length < 2) return@withContext null
        movieYearCache[q.lowercase()]?.let { return@withContext it.takeIf { y -> y > 0 } }
        val url = "https://api.themoviedb.org/3/search/movie".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey())
            .addQueryParameter("query", q)
            .addQueryParameter("language", "de-DE")
            .build()
        val body = get(url.toString()) ?: return@withContext null
        val year = runCatching {
            val results = JSONObject(body).optJSONArray("results")
            var best: Int? = null
            for (i in 0 until minOf(3, results?.length() ?: 0)) {
                val o = results?.optJSONObject(i) ?: continue
                val name = (o.optString("title").ifBlank { o.optString("original_title") }).lowercase()
                val date = o.optString("release_date")
                val y = date.take(4).toIntOrNull() ?: continue
                if (name == q.lowercase()) return@runCatching y
                if (best == null) best = y
            }
            best
        }.getOrNull()
        movieYearCache[q.lowercase()] = year ?: -1
        year
    }

    /**
     * Portrait avatars from TMDb's popular-people DB — real faces for profile
     * pictures without shipping any assets.
     */
    suspend fun popularPersonAvatars(pages: Int = 3): List<PersonAvatar> = withContext(Dispatchers.IO) {
        val key = apiKey()
        buildList {
            for (page in 1..pages.coerceIn(1, 5)) {
                val url = "https://api.themoviedb.org/3/person/popular".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", key)
                    .addQueryParameter("language", "de-DE")
                    .addQueryParameter("page", page.toString())
                    .build()
                val body = get(url.toString()) ?: continue
                val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("profile_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(PersonAvatar(name = name, url = "$AVATAR_BASE$path"))
                }
            }
        }.distinctBy { it.url }
    }

    private fun findId(imdbId: String?, movie: Boolean, apiKey: String): Int? {
        val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return null
        val url = "https://api.themoviedb.org/3/find/$imdb".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("external_source", "imdb_id")
            .addQueryParameter("language", "de-DE")
            .build()
        val body = get(url.toString()) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val arr = if (movie) json.optJSONArray("movie_results") else json.optJSONArray("tv_results")
        val first = arr?.optJSONObject(0) ?: json.optJSONArray("tv_results")?.optJSONObject(0)
            ?: json.optJSONArray("movie_results")?.optJSONObject(0)
        return first?.optInt("id")?.takeIf { it > 0 }
    }

    private fun searchId(kind: String, title: String, apiKey: String): Int? {
        val url = "https://api.themoviedb.org/3/search/$kind".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("query", title)
            .addQueryParameter("language", "de-DE")
            .build()
        val body = get(url.toString()) ?: return null
        return searchAdapter.fromJson(body)?.results?.firstOrNull()?.id
    }

    private fun details(kind: String, id: Int, apiKey: String): TmdbTvDetails? {
        val url = "https://api.themoviedb.org/3/$kind/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", "de-DE")
            .addQueryParameter("append_to_response", "external_ids")
            .build()
        val body = get(url.toString()) ?: return null
        return detailsAdapter.fromJson(body)
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Verflixed/1.9.2 (Android TV)")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        /** Public Team-Kodi TMDb scraper key — users never need a TMDb account. */
        const val APP_KEY = "af3a53eb387d57fc935e9128468b1899"
        private const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
        private const val AVATAR_BASE = "https://image.tmdb.org/t/p/w185"
        private const val STILL_BASE = "https://image.tmdb.org/t/p/w300"
    }
}

data class PersonAvatar(val name: String, val url: String)

@JsonClass(generateAdapter = true)
data class TmdbSearchResponse(
    val results: List<TmdbSearchItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSearchItem(
    val id: Int,
    val name: String? = null,
    val title: String? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbTvDetails(
    val id: Int,
    val overview: String? = null,
    val name: String? = null,
    val title: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIds? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    val runtime: Int? = null,
    val status: String? = null,
    val genres: List<TmdbGenre>? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    val id: Int? = null,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbExternalIds(
    @Json(name = "imdb_id") val imdbId: String? = null,
)
