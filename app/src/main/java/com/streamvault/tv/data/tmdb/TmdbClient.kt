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

class TmdbClient(
    private val http: OkHttpClient,
    moshi: Moshi,
    private val prefs: UserPrefs
) {
    private val searchAdapter = moshi.adapter(TmdbSearchResponse::class.java)
    private val detailsAdapter = moshi.adapter(TmdbTvDetails::class.java)

    suspend fun enrich(series: Series): Series = withContext(Dispatchers.IO) {
        val key = prefs.tmdbApiKey
        if (key.isBlank()) return@withContext series
        runCatching {
            if (series.isMovie) enrichMovie(series, key) else enrichTv(series, key)
        }.getOrDefault(series)
    }

    private fun enrichTv(series: Series, key: String): Series {
        val id = series.tmdbId ?: searchId("tv", series.title, key) ?: return series
        val details = details("tv", id, key) ?: return series
        return series.copy(
            tmdbId = id,
            imdbId = series.imdbId ?: details.externalIds?.imdbId,
            overview = series.overview ?: details.overview,
            posterUrl = series.posterUrl ?: details.posterPath?.let { "$IMAGE_BASE$it" },
            backdropUrl = series.backdropUrl ?: details.backdropPath?.let { "$IMAGE_BASE$it" },
            year = series.year ?: details.firstAirDate?.take(4)?.toIntOrNull()
        )
    }

    private fun enrichMovie(series: Series, key: String): Series {
        val id = series.tmdbId ?: searchId("movie", series.title, key) ?: return series
        val details = details("movie", id, key) ?: return series
        return series.copy(
            tmdbId = id,
            imdbId = series.imdbId ?: details.externalIds?.imdbId,
            overview = series.overview ?: details.overview,
            posterUrl = series.posterUrl ?: details.posterPath?.let { "$IMAGE_BASE$it" },
            backdropUrl = series.backdropUrl ?: details.backdropPath?.let { "$IMAGE_BASE$it" },
            year = series.year
                ?: details.releaseDate?.take(4)?.toIntOrNull()
                ?: details.firstAirDate?.take(4)?.toIntOrNull()
        )
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
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    companion object {
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    }
}

@JsonClass(generateAdapter = true)
data class TmdbSearchResponse(
    val results: List<TmdbSearchItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSearchItem(
    val id: Int,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvDetails(
    val id: Int,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIds? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbExternalIds(
    @Json(name = "imdb_id") val imdbId: String? = null,
)
