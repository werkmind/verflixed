package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.Series
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Live search against SerienStream suggest API / AniWorld ajax search /
 * Filmpalast /search/title/{q}.
 *
 * Fast mode (default) is tuned for TV typing: short call timeout, suggest-only
 * for series, single path for movies. No HTML scrape waterfall while the user
 * is still typing - that was the main source of lag.
 */
object SiteSearch {
    private val SERIES_HREF =
        Regex("""/(?:serie|series|anime/stream)(?:/stream)?/[^/?#]+""", RegexOption.IGNORE_CASE)
    private val MOVIE_HREF = Regex("""/stream/[^/?#]+""", RegexOption.IGNORE_CASE)
    private val EP_RE = Regex("""\bS\d{1,2}E\d{1,3}\b""", RegexOption.IGNORE_CASE)

    /** Shared short-timeout client for live typing. Derived once per base client. */
    @Volatile private var fastHttp: OkHttpClient? = null
    @Volatile private var fastHttpParent: OkHttpClient? = null

    private fun fastClient(http: OkHttpClient): OkHttpClient {
        val cached = fastHttp
        if (cached != null && fastHttpParent === http) return cached
        val built = http.newBuilder()
            .connectTimeout(1_200, TimeUnit.MILLISECONDS)
            .readTimeout(2_200, TimeUnit.MILLISECONDS)
            .writeTimeout(2_200, TimeUnit.MILLISECONDS)
            .callTimeout(2_800, TimeUnit.MILLISECONDS)
            .build()
        fastHttp = built
        fastHttpParent = http
        return built
    }

    /**
     * Fold German umlauts to their ASCII digraph spelling. The sites index
     * ASCII slugs (ue/oe/ae), so a typed "Ü/ö/ä" misses. Used both as the
     * fallback query when the exact term returns nothing and in tests.
     */
    fun foldUmlauts(q: String): String = q
        .replace("ä", "ae", ignoreCase = true)
        .replace("ö", "oe", ignoreCase = true)
        .replace("ü", "ue", ignoreCase = true)
        .replace("ß", "ss", ignoreCase = true)

    /**
     * @param fast when true (default): suggest/ajax only, short timeouts, one
     * movie path. When false: full waterfall including HTML (rare offline use).
     */
    fun search(
        http: OkHttpClient,
        baseUrl: String,
        query: String,
        userAgent: String,
        mediaKind: String = "series",
        fast: Boolean = true,
    ): List<Series> {
        val base = baseUrl.trim().trimEnd('/')
        val q = query.trim()
        if (base.isBlank() || q.length < 2) return emptyList()
        val client = if (fast) fastClient(http) else http

        val direct = searchOnce(client, base, q, userAgent, mediaKind, fast)
        if (direct.isNotEmpty()) return direct

        // Umlaut fallback: sites index ASCII slugs. Retry once with folded vowels.
        // Fast path only re-hits suggest/ajax - never the HTML scrape.
        val folded = foldUmlauts(q)
        if (folded.equals(q, ignoreCase = true)) return emptyList()
        return searchOnce(client, base, folded, userAgent, mediaKind, fast)
    }

    private fun searchOnce(
        http: OkHttpClient,
        base: String,
        q: String,
        userAgent: String,
        mediaKind: String,
        fast: Boolean,
    ): List<Series> {
        if (mediaKind == "movie") {
            return searchMovies(http, base, q, userAgent, fast)
        }
        // Prefer suggest (JSON, ~100ms). Ajax is the AniWorld fallback.
        // HTML scrape only in slow mode - it is multi-second and blocks typing.
        searchSuggest(http, base, q, userAgent)?.takeIf { it.isNotEmpty() }?.let { return it }
        searchAjax(http, base, q, userAgent)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!fast) {
            searchHtml(http, base, q, userAgent)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun searchMovies(
        http: OkHttpClient,
        base: String,
        q: String,
        userAgent: String,
        fast: Boolean,
    ): List<Series> {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val plus = java.net.URLEncoder.encode(q.replace(Regex("""\s+"""), "+"), "UTF-8")
        val paths = if (fast) {
            // One path while typing - Filmpalast's canonical title search.
            listOf("/search/title/$enc")
        } else {
            listOf(
                "/search/title/$enc",
                "/search/title/$plus",
                "/suche?q=$enc",
                "/search?q=$enc",
            )
        }
        for (path in paths) {
            if (Thread.interrupted()) return emptyList()
            val html = get(http, "$base$path", userAgent, base, acceptJson = false) ?: continue
            val hits = FilmParser.parseMovieList(html, base, moviesOnly = true)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun searchSuggest(
        http: OkHttpClient,
        base: String,
        q: String,
        userAgent: String,
    ): List<Series>? {
        val url = "$base/api/search/suggest?term=${java.net.URLEncoder.encode(q, "UTF-8")}"
        val body = get(http, url, userAgent, base, acceptJson = true) ?: return null
        return parseSuggestBody(body, base)
    }

    private fun searchAjax(
        http: OkHttpClient,
        base: String,
        q: String,
        userAgent: String,
    ): List<Series>? {
        val form = FormBody.Builder().add("keyword", q).build()
        val req = Request.Builder()
            .url("$base/ajax/search")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", base)
            .header("Referer", "$base/")
            .post(form)
            .build()
        val body = execute(http, req) ?: return null
        return parseSuggestBody(body, base)
    }

    private fun searchHtml(
        http: OkHttpClient,
        base: String,
        q: String,
        userAgent: String,
    ): List<Series>? {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        for (path in listOf("/suche?q=$enc", "/search?q=$enc")) {
            if (Thread.interrupted()) return null
            val html = get(http, "$base$path", userAgent, base, acceptJson = false) ?: continue
            val pageUrl = "$base$path"
            val doc = Jsoup.parse(html, pageUrl)
            val seen = linkedSetOf<String>()
            val out = mutableListOf<Series>()
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").ifBlank { resolve(base, a.attr("href")) }
                if (!SERIES_HREF.containsMatchIn(href)) return@forEach
                val root = seriesRoot(href) ?: return@forEach
                if (!seen.add(root)) return@forEach
                val title = cleanTitle(
                    a.selectFirst("strong, .title, h3, h2")?.text()
                        ?: a.attr("title").ifBlank { a.text() }
                )
                if (title.isBlank()) return@forEach
                out += seriesOf(root, title, null)
            }
            if (out.isNotEmpty()) return out
        }
        return null
    }

    private fun parseSuggestBody(body: String, base: String): List<Series> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val shows = obj.optJSONArray("shows") ?: JSONArray()
                    parseShowArray(shows, base)
                }
                trimmed.startsWith("[") -> parseShowArray(JSONArray(trimmed), base)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Test seam: same logic as parseSuggestBody, visible for unit tests. */
    internal fun parseSuggestBodyForTest(body: String, base: String): List<Series> =
        parseSuggestBody(body, base)

    private fun parseShowArray(arr: JSONArray, base: String): List<Series> {
        val out = mutableListOf<Series>()
        val seen = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = cleanTitle(
                stripTags(
                    item.optString("name")
                        .ifBlank { item.optString("title") }
                )
            )
            val href = item.optString("url")
                .ifBlank { item.optString("link") }
                .ifBlank { item.optString("href") }
            if (title.isBlank() || href.isBlank()) continue
            if (!SERIES_HREF.containsMatchIn(href)) continue
            val detail = resolve(base, href)
            val root = seriesRoot(detail) ?: continue
            if (!seen.add(root)) continue
            val overview = stripTags(
                item.optString("description").ifBlank { item.optString("overview") }
            ).ifBlank { null }
            out += seriesOf(root, title, overview)
        }
        return out
    }

    private fun seriesOf(detailPath: String, title: String, overview: String?): Series {
        val id = slugId(detailPath, title)
        return Series(
            id = id,
            title = title,
            overview = overview,
            detailPath = detailPath,
            mediaKind = "series",
        )
    }

    private fun seriesRoot(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val m = SERIES_HREF.find(path) ?: return null
        val rootPath = m.value.trimEnd('/')
        return runCatching {
            val u = URI(url)
            URI(u.scheme, u.authority, rootPath, null, null).toString()
        }.getOrElse { resolve(url.substringBefore(path), rootPath) }
    }

    private fun slugId(url: String, title: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val root = SERIES_HREF.find(path)?.value.orEmpty()
        val slug = root.trim('/').substringAfterLast('/').ifBlank {
            path.trim('/').substringAfterLast('/')
        }.ifBlank { title }
        return slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*[-|–]\s*stream.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+staffel\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+season\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&#8230;", "…")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun resolve(base: String, href: String): String {
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)
    }

    private fun get(
        http: OkHttpClient,
        url: String,
        userAgent: String,
        base: String,
        acceptJson: Boolean,
    ): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header(
                "Accept",
                if (acceptJson) "application/json, text/javascript, */*; q=0.01"
                else "text/html,application/xhtml+xml,*/*;q=0.8"
            )
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$base/")
            .get()
            .build()
        return execute(http, req)
    }

    private fun execute(http: OkHttpClient, req: Request): String? {
        if (Thread.interrupted()) return null
        val call: Call = http.newCall(req)
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        } catch (_: Exception) {
            // Timeouts / cancel / DNS - live search is best-effort.
            null
        }
    }
}
