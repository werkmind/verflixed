package com.streamvault.tv.data.skip

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.streamvault.tv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * AniSkip (https://api.aniskip.com) crowd-sourced OP/ED/recap timestamps.
 * MAL id resolved via Jikan title search when unknown.
 */
class AniSkipClient(
    private val http: OkHttpClient,
    moshi: Moshi,
    private val skipMarks: SkipMarksStore? = null,
) {
    private val skipAdapter = moshi.adapter(AniSkipResponse::class.java)
    private val jikanAdapter = moshi.adapter(JikanSearchResponse::class.java)
    private val malCache = ConcurrentHashMap<String, Int?>()
    private val skipCache = ConcurrentHashMap<String, List<SkipSegment>>()
    private val mutex = Mutex()

    suspend fun resolveMalId(series: Series): Int? = withContext(Dispatchers.IO) {
        series.malId?.takeIf { it > 0 }?.let {
            skipMarks?.rememberMalId(series.id, it)
            return@withContext it
        }
        skipMarks?.rememberedMalId(series.id)?.let {
            malCache[series.id] = it
            return@withContext it
        }
        malCache[series.id]?.let { return@withContext it }
        mutex.withLock {
            malCache[series.id]?.let { return@withLock it }
            skipMarks?.rememberedMalId(series.id)?.let {
                malCache[series.id] = it
                return@withLock it
            }
            if (!looksAnime(series)) {
                malCache[series.id] = null
                return@withLock null
            }
            val q = series.title.trim().takeIf { it.isNotBlank() } ?: run {
                malCache[series.id] = null
                return@withLock null
            }
            val url = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("limit", "8")
                .build()
            val body = get(url.toString()) ?: run {
                malCache[series.id] = null
                return@withLock null
            }
            val parsed = runCatching { jikanAdapter.fromJson(body) }.getOrNull()
            val best = parsed?.data
                ?.maxByOrNull { scoreTitle(q, it.title, it.titleEnglish) }
            val mal = best?.malId?.takeIf { it > 0 }
            malCache[series.id] = mal
            if (mal != null) skipMarks?.rememberMalId(series.id, mal)
            mal
        }
    }

    /**
     * @param episodeNumber season-local number (SerienStream)
     * @param absoluteEpisodeNumber 1-based flat index across seasons (MAL-style)
     */
    suspend fun skipSegments(
        series: Series,
        episodeNumber: Int,
        durationMs: Long,
        absoluteEpisodeNumber: Int = episodeNumber,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (episodeNumber <= 0 && absoluteEpisodeNumber <= 0) return@withContext emptyList()
        val mal = resolveMalId(series) ?: return@withContext emptyList()
        // MAL / AniSkip usually use absolute numbering for multi-season anime.
        val candidates = linkedSetOf<Int>().apply {
            if (absoluteEpisodeNumber > 0) add(absoluteEpisodeNumber)
            if (episodeNumber > 0) add(episodeNumber)
        }
        for (num in candidates) {
            val segs = fetchSkip(mal, num, durationMs)
            if (segs.isNotEmpty()) return@withContext segs
        }
        emptyList()
    }

    private fun fetchSkip(mal: Int, episodeNumber: Int, durationMs: Long): List<SkipSegment> {
        val cacheKey = "$mal:$episodeNumber:${durationMs / 1000}"
        skipCache[cacheKey]?.let { return it }

        val lengthSec = if (durationMs > 5_000L) (durationMs / 1000L).toInt() else 0
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$episodeNumber".toHttpUrl()
            .newBuilder()
            .addQueryParameter("types", "op")
            .addQueryParameter("types", "ed")
            .addQueryParameter("types", "recap")
            .addQueryParameter("types", "mixed-op")
            .addQueryParameter("types", "mixed-ed")
            .addQueryParameter("episodeLength", lengthSec.toString())
            .build()
        val body = get(url.toString()) ?: return emptyList()
        val parsed = runCatching { skipAdapter.fromJson(body) }.getOrNull()
        if (parsed?.found != true) {
            skipCache[cacheKey] = emptyList()
            return emptyList()
        }
        val segs = parsed.results.orEmpty().mapNotNull { row ->
            val start = ((row.interval?.startTime ?: return@mapNotNull null) * 1000.0).toLong()
            val end = ((row.interval?.endTime ?: return@mapNotNull null) * 1000.0).toLong()
            if (end <= start) return@mapNotNull null
            val type = when (row.skipType?.lowercase()) {
                "op", "mixed-op" -> SkipSegment.Type.INTRO
                "ed", "mixed-ed" -> SkipSegment.Type.CREDITS
                "recap" -> SkipSegment.Type.RECAP
                else -> return@mapNotNull null
            }
            SkipSegment(type, start, end, source = "aniskip")
        }.sortedBy { it.startMs }
        skipCache[cacheKey] = segs
        return segs
    }

    private fun looksAnime(series: Series): Boolean {
        if (series.genres.any {
                it.contains("anime", true) ||
                    it.contains("animation", true) ||
                    it.equals("Zeichentrick", true) ||
                    it.contains("Manga", true)
            }
        ) return true
        val path = (series.detailPath ?: "").lowercase()
        if (path.contains("anime") || path.contains("aniworld") || path.contains("animes")) return true
        // Common DE/EN anime hubs / title hints on stream sites.
        val title = series.title.lowercase()
        if (title.contains("anime")) return true
        return false
    }

    private fun scoreTitle(query: String, vararg titles: String?): Int {
        val q = query.lowercase()
        return titles.filterNotNull().maxOfOrNull { t ->
            val n = t.lowercase()
            when {
                n == q -> 100
                n.contains(q) || q.contains(n) -> 70
                else -> n.split(' ').count { it.length > 2 && q.contains(it) } * 10
            }
        } ?: 0
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Verflixed/1.8.0 (Android TV)")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    @JsonClass(generateAdapter = true)
    data class AniSkipResponse(
        val found: Boolean? = null,
        val results: List<AniSkipResult>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AniSkipResult(
        val interval: AniSkipInterval? = null,
        @Json(name = "skipType") val skipType: String? = null,
        @Json(name = "episodeLength") val episodeLength: Double? = null,
    )

    @JsonClass(generateAdapter = true)
    data class AniSkipInterval(
        @Json(name = "startTime") val startTime: Double? = null,
        @Json(name = "endTime") val endTime: Double? = null,
    )

    @JsonClass(generateAdapter = true)
    data class JikanSearchResponse(
        val data: List<JikanAnime>? = null,
    )

    @JsonClass(generateAdapter = true)
    data class JikanAnime(
        @Json(name = "mal_id") val malId: Int? = null,
        val title: String? = null,
        @Json(name = "title_english") val titleEnglish: String? = null,
    )
}
