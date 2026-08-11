package com.streamvault.tv.data.meta

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Season
import com.streamvault.tv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Free metadata – TVMaze, no API key.
 * Enriches series overview/poster/backdrop and episode stills/titles.
 */
class TvMazeClient(
    private val http: OkHttpClient,
    moshi: Moshi
) {
    private val showAdapter = moshi.adapter(TvMazeShow::class.java)
    private val episodesType = Types.newParameterizedType(List::class.java, TvMazeEpisode::class.java)
    private val episodesAdapter = moshi.adapter<List<TvMazeEpisode>>(episodesType)

    /** Lightweight enrichment for browse tiles (no episode list). */
    suspend fun enrichBasic(series: Series): Series = withContext(Dispatchers.IO) {
        runCatching {
            val show = searchShow(cleanTitle(series.title)) ?: return@withContext series
            applyShowMeta(series, show, seasons = series.seasons)
        }.getOrDefault(series)
    }

    suspend fun enrichBasics(series: List<Series>, concurrency: Int = 6): List<Series> =
        withContext(Dispatchers.IO) {
            if (series.isEmpty()) return@withContext series
            val gate = Semaphore(concurrency)
            coroutineScope {
                series.map { item ->
                    async {
                        gate.withPermit {
                            if (!item.overview.isNullOrBlank() &&
                                !item.backdropUrl.isNullOrBlank() &&
                                !item.posterUrl.isNullOrBlank()
                            ) item else enrichBasic(item)
                        }
                    }
                }.awaitAll()
            }
        }

    suspend fun enrich(series: Series): Series = withContext(Dispatchers.IO) {
        runCatching {
            val show = searchShow(cleanTitle(series.title)) ?: return@withContext series
            val mazeEpisodes = fetchEpisodes(show.id) ?: emptyList()
            val seasonArts = fetchSeasonArts(show.id)
            val byKey = buildEpisodeIndex(mazeEpisodes)

            val seasons = if (series.seasons.isEmpty()) {
                val remap = seasonRemap(mazeEpisodes)
                mazeEpisodes.groupBy { remap[it.season ?: 1] ?: (it.season ?: 1) }.entries
                    .filter { it.key > 0 }
                    .sortedBy { it.key }
                    .map { (seasonNo, eps) ->
                        val art = seasonArts[seasonNo]
                        Season(
                            number = seasonNo,
                            title = "Staffel $seasonNo",
                            posterUrl = art,
                            backdropUrl = art,
                            episodes = eps.sortedBy { it.number ?: 0 }.mapIndexed { idx, me ->
                                Episode(
                                    id = "${series.id}-s${seasonNo}e${me.number ?: (idx + 1)}",
                                    seriesId = series.id,
                                    seasonNumber = seasonNo,
                                    number = me.number ?: (idx + 1),
                                    title = me.name ?: "Episode ${me.number ?: (idx + 1)}",
                                    overview = me.summary?.let { Jsoup.parse(it).text() },
                                    stillUrl = preferredStill(me.image)
                                )
                            }
                        )
                    }
            } else {
                val flatMaze = mazeEpisodes.sortedWith(
                    compareBy<TvMazeEpisode> { it.season ?: 0 }.thenBy { it.number ?: 0 }
                )
                var flatIdx = 0
                val matchedDirect = series.flatEpisodes().count { byKey.containsKey(it.seasonNumber to it.number) }
                val useFlatFallback = matchedDirect < (series.flatEpisodes().size * 0.3)

                series.seasons.map { season ->
                    val art = seasonArts[season.number]
                    season.copy(
                        posterUrl = season.posterUrl ?: art,
                        backdropUrl = season.backdropUrl ?: art,
                        episodes = season.episodes.map { ep ->
                            val me = byKey[ep.seasonNumber to ep.number]
                                ?: if (useFlatFallback && flatIdx < flatMaze.size) flatMaze[flatIdx] else null
                            if (useFlatFallback) flatIdx++
                            val mazeStill = preferredStill(me?.image)
                            ep.copy(
                                title = if (ep.title.startsWith("Episode") || ep.title.equals("Folge ${ep.number}", true)) {
                                    me?.name ?: ep.title
                                } else ep.title,
                                overview = ep.overview ?: me?.summary?.let { Jsoup.parse(it).text() },
                                stillUrl = mazeStill ?: ep.stillUrl
                            )
                        }
                    )
                }
            }

            applyShowMeta(series, show, seasons)
        }.getOrDefault(series)
    }

    private fun applyShowMeta(series: Series, show: TvMazeShow, seasons: List<Season>): Series {
        val poster = show.image?.medium ?: show.image?.original
        val backdrop = show.image?.original ?: poster
        val overview = show.summary?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() }
        val existingOverview = series.overview?.trim()
        val preferExisting = existingOverview != null &&
            existingOverview.length > 60 &&
            !existingOverview.lowercase().startsWith("schaue ")
        return series.copy(
            title = show.name?.takeIf { it.isNotBlank() } ?: series.title,
            overview = if (preferExisting) existingOverview else (overview ?: existingOverview),
            posterUrl = series.posterUrl ?: poster,
            // Prefer landscape/original art for 16:9 tiles when site backdrop missing.
            backdropUrl = series.backdropUrl ?: backdrop,
            year = series.year ?: show.premiered?.take(4)?.toIntOrNull(),
            seasons = seasons
        )
    }

    private fun preferredStill(image: TvMazeImage?): String? =
        image?.original ?: image?.medium

    /**
     * Some anime on TVMaze use air-year as season (2004, 2005…).
     * Remap those to Staffel 1..N and also keep native keys for normal shows.
     */
    private fun seasonRemap(episodes: List<TvMazeEpisode>): Map<Int, Int> {
        val seasons = episodes.mapNotNull { it.season }.distinct().sorted()
        if (seasons.isEmpty()) return emptyMap()
        val looksLikeYears = seasons.any { it >= 1900 }
        return if (looksLikeYears) {
            seasons.mapIndexed { idx, year -> year to (idx + 1) }.toMap()
        } else {
            seasons.associateWith { it }
        }
    }

    private fun buildEpisodeIndex(episodes: List<TvMazeEpisode>): Map<Pair<Int, Int>, TvMazeEpisode> {
        val remap = seasonRemap(episodes)
        val map = LinkedHashMap<Pair<Int, Int>, TvMazeEpisode>()
        episodes.forEach { ep ->
            val nativeSeason = ep.season ?: return@forEach
            val number = ep.number ?: return@forEach
            map[nativeSeason to number] = ep
            val remapped = remap[nativeSeason]
            if (remapped != null) map[remapped to number] = ep
        }
        return map
    }

    private fun cleanTitle(title: String): String =
        title
            .replace(Regex("""(?i)\s*[-|–]\s*stream.*$"""), "")
            .replace(Regex("""(?i)\s+staffel\s*\d+.*$"""), "")
            .replace(Regex("""(?i)\s+season\s*\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun searchShow(title: String): TvMazeShow? {
        if (title.isBlank()) return null
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .build()
        return showAdapter.fromJson(get(url.toString()) ?: return null)
    }

    private fun fetchEpisodes(showId: Int?): List<TvMazeEpisode>? {
        if (showId == null) return null
        return episodesAdapter.fromJson(get("https://api.tvmaze.com/shows/$showId/episodes") ?: return null)
    }

    /** Season poster/backdrop map (number → image URL). */
    private fun fetchSeasonArts(showId: Int?): Map<Int, String> {
        if (showId == null) return emptyMap()
        val body = get("https://api.tvmaze.com/shows/$showId/seasons") ?: return emptyMap()
        return runCatching {
            val arr = org.json.JSONArray(body)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val num = o.optInt("number", -1)
                    if (num <= 0) continue
                    val image = o.optJSONObject("image")
                    val url = image?.optString("original")?.takeIf { it.isNotBlank() }
                        ?: image?.optString("medium")?.takeIf { it.isNotBlank() }
                    if (!url.isNullOrBlank()) put(num, url)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }
}

@JsonClass(generateAdapter = true)
data class TvMazeShow(
    val id: Int? = null,
    val name: String? = null,
    val summary: String? = null,
    val premiered: String? = null,
    val image: TvMazeImage? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeEpisode(
    val id: Int? = null,
    val name: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val summary: String? = null,
    val image: TvMazeImage? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeImage(
    val medium: String? = null,
    val original: String? = null
)
