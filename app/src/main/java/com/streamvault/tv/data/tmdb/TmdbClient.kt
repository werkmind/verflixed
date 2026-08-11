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
            val id = series.tmdbId ?: searchTvId(series.title, key) ?: return@withContext series
            val details = tvDetails(id, key) ?: return@withContext series
            series.copy(
                tmdbId = id,
                overview = series.overview ?: details.overview,
                posterUrl = series.posterUrl ?: details.posterPath?.let { "$IMAGE_BASE$it" },
                backdropUrl = series.backdropUrl ?: details.backdropPath?.let { "$IMAGE_BASE$it" },
                year = series.year ?: details.firstAirDate?.take(4)?.toIntOrNull()
            )
        }.getOrDefault(series)
    }

    private fun searchTvId(title: String, apiKey: String): Int? {
        val url = "https://api.themoviedb.org/3/search/tv".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("query", title)
            .addQueryParameter("language", "de-DE")
            .build()
        val body = get(url.toString()) ?: return null
        return searchAdapter.fromJson(body)?.results?.firstOrNull()?.id
    }

    private fun tvDetails(id: Int, apiKey: String): TmdbTvDetails? {
        val url = "https://api.themoviedb.org/3/tv/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", "de-DE")
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
    @Json(name = "first_air_date") val firstAirDate: String? = null
)
