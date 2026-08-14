package com.streamvault.tv.data.meta

import com.streamvault.tv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Keyless ID resolver (no account, no API key).
 * Maps a title → IMDb (P345) + TMDB (P4983 TV / P4947 movie) via Wikidata.
 */
class WikidataClient(
    private val http: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, Ids>()

    data class Ids(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
    )

    suspend fun enrich(series: Series): Series = withContext(Dispatchers.IO) {
        if (!series.imdbId.isNullOrBlank() && series.tmdbId != null) return@withContext series
        val ids = resolve(series.title, series.year, series.isMovie) ?: return@withContext series
        series.copy(
            imdbId = series.imdbId ?: ids.imdbId,
            tmdbId = series.tmdbId ?: ids.tmdbId,
        )
    }

    suspend fun resolve(title: String, year: Int?, movie: Boolean): Ids? = withContext(Dispatchers.IO) {
        val q = clean(title)
        if (q.length < 2) return@withContext null
        val key = "${q.lowercase()}|${year ?: 0}|$movie"
        cache[key]?.let { return@withContext it }
        val hit = runCatching { searchAndRead(q, year, movie) }.getOrNull()
        if (hit != null) cache[key] = hit
        hit
    }

    private fun searchAndRead(title: String, year: Int?, movie: Boolean): Ids? {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbsearchentities")
            .addQueryParameter("search", title)
            .addQueryParameter("language", "de")
            .addQueryParameter("uselang", "de")
            .addQueryParameter("type", "item")
            .addQueryParameter("limit", "6")
            .addQueryParameter("format", "json")
            .build()
        val body = get(url.toString()) ?: return null
        val arr = JSONObject(body).optJSONArray("search") ?: return null
        val ids = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.startsWith("Q") }?.let { add(it) }
            }
        }
        if (ids.isEmpty()) return null
        val entities = fetchEntities(ids) ?: return null
        var best: Pair<Int, Ids>? = null
        for (qid in ids) {
            val ent = entities.optJSONObject(qid) ?: continue
            val claims = ent.optJSONObject("claims") ?: continue
            val imdb = firstString(claims, "P345")?.takeIf { it.startsWith("tt") }
            val tmdb = firstString(claims, if (movie) "P4947" else "P4983")?.toIntOrNull()
                ?: firstString(claims, if (movie) "P4983" else "P4947")?.toIntOrNull()
            if (imdb == null && tmdb == null) continue
            val label = ent.optJSONObject("labels")
                ?.optJSONObject("de")?.optString("value")
                ?: ent.optJSONObject("labels")?.optJSONObject("en")?.optString("value")
                ?: ""
            val score = score(title, label, year, claims)
            val cand = Ids(imdbId = imdb, tmdbId = tmdb)
            if (best == null || score > best!!.first) best = score to cand
        }
        return best?.second
    }

    private fun fetchEntities(ids: List<String>): JSONObject? {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", ids.joinToString("|"))
            .addQueryParameter("props", "claims|labels")
            .addQueryParameter("languages", "de|en")
            .addQueryParameter("format", "json")
            .build()
        val body = get(url.toString()) ?: return null
        return JSONObject(body).optJSONObject("entities")
    }

    private fun firstString(claims: JSONObject, prop: String): String? {
        val arr = claims.optJSONArray(prop) ?: return null
        val mainsnak = arr.optJSONObject(0)?.optJSONObject("mainsnak") ?: return null
        val dv = mainsnak.optJSONObject("datavalue") ?: return null
        return when (val v = dv.opt("value")) {
            is String -> v
            is JSONObject -> v.optString("id").ifBlank { v.optString("text") }.ifBlank { null }
            else -> dv.optString("value").takeIf { it.isNotBlank() }
        }
    }

    private fun score(query: String, label: String, year: Int?, claims: JSONObject): Int {
        val q = query.lowercase()
        val n = label.lowercase()
        var s = when {
            n == q -> 100
            n.contains(q) || q.contains(n) -> 70
            else -> n.split(' ').count { it.length > 2 && q.contains(it) } * 12
        }
        if (year != null) {
            val date = firstTimeYear(claims, "P577") ?: firstTimeYear(claims, "P580")
            if (date != null && kotlin.math.abs(date - year) <= 1) s += 25
        }
        return s
    }

    private fun firstTimeYear(claims: JSONObject, prop: String): Int? {
        val arr = claims.optJSONArray(prop) ?: return null
        val time = arr.optJSONObject(0)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("time")
            ?: return null
        return Regex("""([12]\d{3})""").find(time)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun clean(title: String): String =
        title
            .replace(Regex("""(?i)\s*[-|–]\s*stream.*$"""), "")
            .replace(Regex("""(?i)\s+staffel\s*\d+.*$"""), "")
            .replace(Regex("""(?i)\s+season\s*\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Verflixed/1.9.1 (Android TV; metadata; no-account)")
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
