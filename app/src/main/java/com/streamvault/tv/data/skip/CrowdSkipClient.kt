package com.streamvault.tv.data.skip

import com.streamvault.tv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Crowd-sourced intro / recap / credits / preview timestamps.
 *
 * Sources (read-only, no API key):
 * - TheIntroDB — https://api.theintrodb.org (TMDB / IMDb)
 * - SkipDB — https://skipdb.tv (IMDb)
 *
 * Never auto-skips; the player only shows a button.
 */
class CrowdSkipClient(
    private val http: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, List<SkipSegment>>()

    suspend fun skipSegments(
        series: Series,
        seasonNumber: Int,
        episodeNumber: Int,
        durationMs: Long,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val tmdb = series.tmdbId?.takeIf { it > 0 }
        val imdb = series.imdbId?.trim()?.takeIf { it.startsWith("tt") }
        if (tmdb == null && imdb == null) return@withContext emptyList()

        val key = "${tmdb ?: imdb}:$seasonNumber:$episodeNumber:${durationMs / 1000}"
        cache[key]?.let { return@withContext it }

        val movie = series.isMovie
        val segs = coroutineScope {
            val introDb = async {
                runCatching {
                    fetchIntroDb(tmdb, imdb, seasonNumber, episodeNumber, durationMs, movie)
                }.getOrDefault(emptyList())
            }
            val skipDb = async {
                runCatching {
                    fetchSkipDb(imdb, seasonNumber, episodeNumber, durationMs, movie)
                }.getOrDefault(emptyList())
            }
            merge(introDb.await(), skipDb.await())
        }
        cache[key] = segs
        segs
    }

    private fun fetchIntroDb(
        tmdbId: Int?,
        imdbId: String?,
        season: Int,
        episode: Int,
        durationMs: Long,
        movie: Boolean,
    ): List<SkipSegment> {
        val url = "https://api.theintrodb.org/v3/media".toHttpUrl().newBuilder().apply {
            if (tmdbId != null) addQueryParameter("tmdb_id", tmdbId.toString())
            else addQueryParameter("imdb_id", imdbId)
            if (!movie && season > 0 && episode > 0) {
                addQueryParameter("season", season.toString())
                addQueryParameter("episode", episode.toString())
            }
            if (durationMs > 5_000L) addQueryParameter("duration_ms", durationMs.toString())
        }.build()
        val body = get(url.toString()) ?: return emptyList()
        val json = JSONObject(body)
        return buildList {
            addAll(parseArray(json.optJSONArray("intro"), SkipSegment.Type.INTRO, "theintrodb", durationMs))
            addAll(parseArray(json.optJSONArray("recap"), SkipSegment.Type.RECAP, "theintrodb", durationMs))
            addAll(parseArray(json.optJSONArray("credits"), SkipSegment.Type.CREDITS, "theintrodb", durationMs))
            addAll(parseArray(json.optJSONArray("preview"), SkipSegment.Type.PREVIEW, "theintrodb", durationMs))
        }
    }

    private fun fetchSkipDb(
        imdbId: String?,
        season: Int,
        episode: Int,
        durationMs: Long,
        movie: Boolean,
    ): List<SkipSegment> {
        if (imdbId.isNullOrBlank()) return emptyList()
        val url = "https://skipdb.tv/api/segments".toHttpUrl().newBuilder().apply {
            addQueryParameter("imdb_id", imdbId)
            if (!movie && season > 0 && episode > 0) {
                addQueryParameter("season", season.toString())
                addQueryParameter("episode", episode.toString())
            }
            if (durationMs > 5_000L) addQueryParameter("duration", (durationMs / 1000L).toString())
        }.build()
        val body = get(url.toString()) ?: return emptyList()
        val json = JSONObject(body)
        val segs = json.optJSONObject("segments") ?: json
        return buildList {
            parseObject(segs.optJSONObject("intro"), SkipSegment.Type.INTRO, "skipdb", durationMs)?.let { add(it) }
            parseObject(segs.optJSONObject("recap"), SkipSegment.Type.RECAP, "skipdb", durationMs)?.let { add(it) }
            parseObject(
                segs.optJSONObject("outro") ?: segs.optJSONObject("credits"),
                SkipSegment.Type.CREDITS,
                "skipdb",
                durationMs,
            )?.let { add(it) }
            parseObject(segs.optJSONObject("preview"), SkipSegment.Type.PREVIEW, "skipdb", durationMs)?.let { add(it) }
        }
    }

    private fun parseArray(
        arr: JSONArray?,
        type: SkipSegment.Type,
        source: String,
        durationMs: Long,
    ): List<SkipSegment> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                parseObject(arr.optJSONObject(i), type, source, durationMs)?.let { add(it) }
            }
        }
    }

    private fun parseObject(
        obj: JSONObject?,
        type: SkipSegment.Type,
        source: String,
        durationMs: Long,
    ): SkipSegment? {
        if (obj == null || obj === JSONObject.NULL) return null
        val start = ms(obj, "start_ms", "start_sec", "start") ?: 0L
        val end = ms(obj, "end_ms", "end_sec", "end") ?: durationMs.takeIf { it > start } ?: return null
        if (end <= start) return null
        return SkipSegment(type, start.coerceAtLeast(0L), end, source)
    }

    private fun ms(obj: JSONObject, msKey: String, secKey: String, alt: String): Long? {
        if (obj.has(msKey) && !obj.isNull(msKey)) {
            val v = obj.optLong(msKey, Long.MIN_VALUE)
            if (v != Long.MIN_VALUE) return v
        }
        if (obj.has(secKey) && !obj.isNull(secKey)) {
            return (obj.optDouble(secKey) * 1000.0).toLong()
        }
        if (obj.has(alt) && !obj.isNull(alt)) {
            val raw = obj.opt(alt)
            if (raw is Number) {
                val n = raw.toDouble()
                return if (n > 10_000) n.toLong() else (n * 1000.0).toLong()
            }
        }
        return null
    }

    private fun merge(a: List<SkipSegment>, b: List<SkipSegment>): List<SkipSegment> =
        (a + b)
            .groupBy { it.type }
            .mapNotNull { (_, list) -> list.minByOrNull { it.startMs } }
            .sortedBy { it.startMs }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Verflixed/1.9.0 (Android TV)")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
