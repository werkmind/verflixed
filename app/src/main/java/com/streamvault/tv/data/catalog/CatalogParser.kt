package com.streamvault.tv.data.catalog

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.streamvault.tv.data.model.Catalog
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Season
import com.streamvault.tv.data.model.Series
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Accepts either:
 * 1) JSON catalog (preferred): see docs/CATALOG_SCHEMA.md
 * 2) Lightweight HTML index with series cards + detail pages that expose m3u8 links.
 *
 * No third-party hoster decryption. Stream URLs must be direct .m3u8 (or pages that
 * contain an absolute .m3u8 URL in HTML/JSON without executing scripts).
 */
class CatalogParser(private val moshi: Moshi) {

    private val catalogAdapter = moshi.adapter(JsonCatalog::class.java)
    private val seriesAdapter = moshi.adapter(JsonSeries::class.java)

    fun parseCatalog(body: String, baseUrl: String, contentType: String?): Catalog {
        val trimmed = body.trim()
        if (looksLikeJson(trimmed, contentType)) {
            val json = catalogAdapter.fromJson(trimmed)
                ?: error("Invalid catalog JSON")
            return Catalog(series = json.series.map { it.toDomain(baseUrl) })
        }
        return parseHtmlCatalog(trimmed, baseUrl)
    }

    fun parseSeriesDetail(body: String, baseUrl: String, seriesId: String, contentType: String?): Series {
        val trimmed = body.trim()
        if (looksLikeJson(trimmed, contentType)) {
            val json = seriesAdapter.fromJson(trimmed)
                ?: error("Invalid series JSON")
            return json.toDomain(baseUrl)
        }
        return parseHtmlSeriesDetail(trimmed, baseUrl, seriesId)
    }

    fun extractM3u8(body: String, pageUrl: String): String? {
        // Direct absolute m3u8
        ABSOLUTE_M3U8.find(body)?.value?.let { return it }
        // Relative m3u8 in href/src
        RELATIVE_M3U8.findAll(body).forEach { match ->
            val rel = match.groupValues.getOrNull(1) ?: return@forEach
            return resolveUrl(pageUrl, rel)
        }
        // data-stream / data-src attributes via jsoup
        val doc = Jsoup.parse(body, pageUrl)
        doc.select("[data-stream],[data-src],[data-file],source[src],a[href]").forEach { el ->
            val candidates = listOf(
                el.attr("data-stream"),
                el.attr("data-src"),
                el.attr("data-file"),
                el.attr("src"),
                el.attr("abs:href"),
                el.attr("href")
            )
            candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }?.let {
                return resolveUrl(pageUrl, it)
            }
        }
        return null
    }

    /**
     * Finds site player entry links already present in HTML:
     * - data-play-url="/r?t=…"
     * - iframe[src*="/r?t="]
     * - anchors containing /r?t=
     * Prefers provider VOE + language Deutsch when attributes exist.
     */
    fun extractPlayBlob(body: String, pageUrl: String): String? {
        val doc = Jsoup.parse(body, pageUrl)

        data class Candidate(
            val url: String,
            val provider: String,
            val language: String,
            val score: Int
        )

        val candidates = mutableListOf<Candidate>()

        fun add(raw: String?, provider: String = "", language: String = "", bonus: Int = 0) {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return
            if (!StreamKind.isPlayBlobUrl(value) && !value.contains("/r?t=", true)) return
            val abs = resolveUrl(pageUrl, value)
            var score = bonus
            val p = provider.lowercase()
            val l = language.lowercase()
            if (p.contains("voe")) score += 50
            if (l.contains("deutsch") || l == "de" || l.contains("german")) score += 30
            if (p.isNotBlank()) score += 5
            candidates += Candidate(abs, provider, language, score)
        }

        doc.select("[data-play-url], [data-link], button.link-box, .link-box, .link-wrapper button, iframe[src]").forEach { el ->
            add(
                raw = el.attr("data-play-url").ifBlank { el.attr("src") }.ifBlank { el.attr("data-link") },
                provider = el.attr("data-provider-name").ifBlank { el.attr("data-provider") },
                language = el.attr("data-language-label").ifBlank { el.attr("data-language") },
                bonus = 10
            )
        }
        doc.select("a[href*='/r?t='], a[href*='/r?t%']").forEach { a ->
            add(a.attr("abs:href").ifBlank { a.attr("href") }, bonus = 5)
        }
        // Raw regex fallback for blobs in HTML/JS strings
        PLAY_BLOB.findAll(body).forEach { m ->
            add(m.value, bonus = 1)
        }

        return candidates.maxByOrNull { it.score }?.url
            ?: candidates.firstOrNull()?.url
    }

    /** Best available player URL from a page: m3u8 > play-blob > voe.sx */
    fun extractPlayerUrl(body: String, pageUrl: String): String? {
        extractM3u8(body, pageUrl)?.let { return it }
        extractPlayBlob(body, pageUrl)?.let { return it }
        Regex("""https?://[^\s"'<>]*voe[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
            .firstOrNull { StreamKind.isVoePlayerUrl(it) }
            ?.let { return it }
        return null
    }

    private fun parseHtmlCatalog(html: String, baseUrl: String): Catalog {
        val doc = Jsoup.parse(html, baseUrl)
        val seen = LinkedHashMap<String, Series>()

        fun upsert(href: String, titleRaw: String?, poster: String?, backdrop: String? = null) {
            val path = runCatching { URI(href).path }.getOrNull().orEmpty()
            // Prefer series root: /serie/{slug} or /serie/stream/{slug} (strip staffel/episode tails)
            val rootPath = SERIES_ROOT.find(path)?.groupValues?.get(1) ?: path
            if (rootPath.equals("/serie/stream", true) || rootPath.equals("/series/stream", true)) return
            val rootHref = runCatching {
                val u = URI(href)
                URI(u.scheme, u.authority, rootPath, null, null).toString()
            }.getOrDefault(href)
            val title = cleanTitle(
                titleRaw?.trim().orEmpty().ifBlank {
                    rootPath.trim('/').substringAfterLast('/')
                }
            )
            if (title.length < 2 || title.equals("stream", true)) return
            val id = slugId(rootHref, title)
            if (!seen.containsKey(id)) {
                seen[id] = Series(
                    id = id,
                    title = title.replace('-', ' ')
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    posterUrl = poster?.takeIf { it.isNotBlank() },
                    backdropUrl = backdrop?.takeIf { it.isNotBlank() },
                    detailPath = rootHref
                )
            } else {
                val prev = seen[id]!!
                seen[id] = prev.copy(
                    posterUrl = prev.posterUrl ?: poster?.takeIf { it.isNotBlank() },
                    backdropUrl = prev.backdropUrl ?: backdrop?.takeIf { it.isNotBlank() }
                )
            }
        }

        doc.select(
            "[data-series], .series-item, .series, article.series, .filmList .coverListItem, a[href*=/serie/], a[href*=/series/], .latest-episode-row a, .cover"
        ).forEach { el ->
            val anchor = when {
                el.tagName() == "a" -> el
                else -> el.selectFirst("a[href]")
            } ?: return@forEach
            val href = anchor.attr("abs:href").ifBlank { resolveUrl(baseUrl, anchor.attr("href")) }
            if (href.isBlank()) return@forEach
            if (!href.contains("/serie", true) && !href.contains("/series", true)) return@forEach
            val title = el.attr("data-title").ifBlank {
                el.selectFirst("h1,h2,h3,.title,.name,.ep-title")?.text()
            }.orEmpty().ifBlank { anchor.attr("title") }.ifBlank {
                anchor.selectFirst("img[alt]")?.attr("alt")
            }.orEmpty().ifBlank { anchor.text() }
            val imgs = buildList {
                (el.selectFirst("img[data-src], img[src], img[srcset]")
                    ?: anchor.selectFirst("img[data-src], img[src], img[srcset]"))
                    ?.let { add(it) }
                el.select("picture source[srcset], picture source[data-srcset]").forEach { add(it) }
                anchor.parent()?.select("img[data-src], img[src]")?.firstOrNull()?.let { add(it) }
            }
            val urls = imgs.mapNotNull { imageAbs(it) }
            val poster = SiteImages.pickPoster(*urls.toTypedArray())
            val backdrop = SiteImages.pickBackdrop(*urls.toTypedArray())
            upsert(href, title, poster, backdrop)
        }

        return Catalog(seen.values.toList())
    }

    private fun parseHtmlSeriesDetail(html: String, baseUrl: String, seriesId: String): Series {
        val doc = Jsoup.parse(html, baseUrl)
        val title = cleanTitle(
            doc.selectFirst("h1, .series-title, .title")?.text()?.trim().orEmpty()
                .ifBlank { seriesId }
        )
        val channelCandidates = doc.select(
            "img[data-src*=/channel/], img[src*=/channel/], source[srcset*=/channel/], " +
                "img.poster, .seriesCoverImg img, .poster img, picture source[srcset]"
        ).mapNotNull { imageAbs(it) }
        val metaImage = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val backdropCandidates = doc.select(
            "img[data-src*=/backdrop/], img[src*=/backdrop/], source[srcset*=/backdrop/]"
        ).mapNotNull { imageAbs(it) }
        val poster = SiteImages.pickPoster(*(channelCandidates + listOfNotNull(metaImage)).toTypedArray())
        val backdrop = SiteImages.pickBackdrop(
            *(backdropCandidates + listOfNotNull(metaImage) + channelCandidates).toTypedArray()
        )
        val bodyDescription = doc.selectFirst(
            ".description-text, .series-description, #description, .description, [itemprop=description], [id*=series-desc]"
        )?.text()?.trim()
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val metaDescription = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        // Prefer real series synopsis over marketing "Schaue X Staffel…" snippets.
        val overview = listOf(bodyDescription, ogDescription, metaDescription)
            .mapNotNull { it?.takeIf { t -> t.length > 40 } }
            .minByOrNull { scoreOverview(it) }
            ?: listOf(bodyDescription, ogDescription, metaDescription).firstOrNull { !it.isNullOrBlank() && it.length > 20 }

        val seasons = LinkedHashMap<Int, MutableList<Episode>>()

        // Explicit episode nodes with stream attrs
        doc.select("[data-episode], .episode, .episodeItem, tr.episode, .seasonEpisodesList tr, .episodes tr").forEach { epEl ->
            val seasonNo = epEl.attr("data-season").toIntOrNull()
                ?: epEl.selectFirst("[data-season]")?.attr("data-season")?.toIntOrNull()
                ?: guessSeason(epEl.text() + " " + (epEl.selectFirst("a[href]")?.attr("href").orEmpty()), doc)
            if (seasonNo <= 0) return@forEach
            val epNo = epEl.attr("data-episode").toIntOrNull()
                ?: Regex("""(?i)(?:episode|folge|ep)[^\d]*(\d+)""").find(epEl.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)(?:episode|folge|ep)[/-]?(\d+)""").find(epEl.selectFirst("a[href]")?.attr("href").orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: (seasons[seasonNo]?.size?.plus(1) ?: 1)
            val epTitle = epEl.attr("data-title").ifBlank {
                epEl.selectFirst(".title, .episode-title, td:not(:first-child)")?.text()
            }.orEmpty().ifBlank { "Episode $epNo" }
            val stream = extractPlayerUrl(epEl.outerHtml(), baseUrl)
            val page = epEl.selectFirst("a[href*=episode], a[href*=folge], a[href]")?.attr("abs:href")
            val still = SiteImages.preferJpeg(
                epEl.selectFirst("img[data-src], img[src], img[srcset]")?.let { imageAbs(it) }
            )
            val id = "$seriesId-s${seasonNo}e$epNo"
            seasons.getOrPut(seasonNo) { mutableListOf() }.add(
                Episode(
                    id = id,
                    seriesId = seriesId,
                    seasonNumber = seasonNo,
                    number = epNo,
                    title = epTitle.trim(),
                    stillUrl = still,
                    streamUrl = stream,
                    streamPageUrl = page
                )
            )
        }

        // Season / episode link crawl
        if (seasons.isEmpty()) {
            doc.select("a[href*=episode], a[href*=folge]").forEach { a ->
                val href = a.attr("abs:href")
                val seasonNo = Regex("""(?i)(?:staffel|season)[/-]?(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (seasonNo <= 0) return@forEach
                val epNo = Regex("""(?i)(?:episode|folge|ep)[/-]?(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                val id = "$seriesId-s${seasonNo}e$epNo"
                val list = seasons.getOrPut(seasonNo) { mutableListOf() }
                if (list.any { it.number == epNo }) return@forEach
                list.add(
                    Episode(
                        id = id,
                        seriesId = seriesId,
                        seasonNumber = seasonNo,
                        number = epNo,
                        title = a.text().ifBlank { "Episode $epNo" }.trim(),
                        streamPageUrl = href
                    )
                )
            }
        }

        // Page itself may already be an episode watch page with play-blobs / m3u8
        if (seasons.isEmpty()) {
            extractPlayerUrl(html, baseUrl)?.let { player ->
                seasons[1] = mutableListOf(
                    Episode(
                        id = "$seriesId-s1e1",
                        seriesId = seriesId,
                        seasonNumber = 1,
                        number = 1,
                        title = title,
                        streamUrl = player
                    )
                )
            }
        } else {
            // If this HTML is already an episode page that also lists hosts, attach blob to matching ep.
            val blob = extractPlayBlob(html, baseUrl)
            if (blob != null && seasons.values.sumOf { it.size } == 1) {
                val onlySeason = seasons.keys.first()
                val only = seasons[onlySeason]!!.first()
                seasons[onlySeason]!![0] = only.copy(streamUrl = only.streamUrl ?: blob)
            }
        }

        val seasonList = seasons.entries
            .filter { it.key > 0 && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { (num, eps) ->
                Season(
                    number = num,
                    title = "Staffel $num",
                    // Staffel-Seiten tragen oft eigenes Cover/Backdrop – an Season binden.
                    posterUrl = poster?.takeIf { it.isNotBlank() && !it.startsWith("data:") },
                    backdropUrl = backdrop?.takeIf { it.isNotBlank() && !it.startsWith("data:") },
                    episodes = eps.distinctBy { it.number }.sortedBy { it.number }
                )
            }

        val genres = doc.select("a[href*=/genre/], .genre a, .genres a, [itemprop=genre]")
            .mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { el.attr("href") }
                val slug = Regex("""/genre/([^/?#]+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                (slug ?: el.text()).trim().lowercase().replace(Regex("\\s+"), "-").takeIf { it.length > 1 }
            }
            .distinct()

        return Series(
            id = seriesId,
            title = title,
            posterUrl = poster?.takeIf { it.isNotBlank() && !it.startsWith("data:") },
            backdropUrl = backdrop?.takeIf { it.isNotBlank() && !it.startsWith("data:") },
            overview = overview?.takeIf { it.isNotBlank() },
            detailPath = baseUrl,
            genres = genres,
            seasons = seasonList
        )
    }

    private fun imageAbs(el: org.jsoup.nodes.Element): String? {
        val srcset = el.attr("abs:data-srcset").ifBlank { el.attr("data-srcset") }
            .ifBlank { el.attr("abs:srcset") }.ifBlank { el.attr("srcset") }
        SiteImages.fromSrcset(srcset)?.let { return it }

        val raw = el.attr("abs:data-src").ifBlank {
            el.attr("data-src")
        }.ifBlank {
            el.attr("abs:src")
        }.ifBlank {
            el.attr("src")
        }
        return SiteImages.preferJpeg(
            raw.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith(".svg", true) }
        )
    }

    /**
     * Deep-claim helpers: mine iframe/OG/video sources for HLS before embedding WebView.
     */
    fun extractClaimableMedia(body: String, pageUrl: String): String? {
        extractM3u8(body, pageUrl)?.let { return it }
        val doc = Jsoup.parse(body, pageUrl)
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")
            .forEach { meta ->
                val c = meta.attr("abs:content").ifBlank { meta.attr("content") }
                if (StreamKind.isDirectMediaUrl(c)) return resolveUrl(pageUrl, c)
            }
        doc.select("video[src], video source[src], source[src*=.m3u8], source[type*=mpegurl]")
            .forEach { el ->
                val src = el.attr("abs:src").ifBlank { el.attr("src") }
                if (StreamKind.isDirectMediaUrl(src)) return resolveUrl(pageUrl, src)
            }
        // iframe src that itself looks like direct media (rare) or VOE
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (StreamKind.isDirectMediaUrl(src)) return resolveUrl(pageUrl, src)
        }
        return null
    }

    fun extractIframeSources(body: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(body, pageUrl)
        return doc.select("iframe[src]")
            .mapNotNull { el ->
                val src = el.attr("abs:src").ifBlank { el.attr("src") }.trim()
                src.takeIf { it.isNotBlank() && !it.startsWith("about:") }?.let { resolveUrl(pageUrl, it) }
            }
            .distinct()
    }

    /** Lower score = better synopsis candidate. */
    private fun scoreOverview(text: String): Int {
        var score = 0
        val lower = text.lowercase()
        if (lower.startsWith("schaue ")) score += 80
        if (lower.contains("staffel") && lower.contains("stream")) score += 40
        if (lower.contains("alle episoden")) score += 30
        if (text.length < 80) score += 20
        return score
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\s*[-|–]\s*stream.*$"""), "")
            .replace(Regex("""(?i)\s+staffel\s*\d+.*$"""), "")
            .replace(Regex("""(?i)\s+season\s*\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun guessSeason(text: String, doc: Document): Int {
        Regex("""(?i)(?:staffel|season)[/-]?(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(?:staffel|season)\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(?:staffel|season)\s*(\d+)""").find(doc.title())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    private fun looksLikeJson(body: String, contentType: String?): Boolean {
        if (contentType?.contains("json", ignoreCase = true) == true) return true
        return body.startsWith("{") || body.startsWith("[")
    }

    private fun slugId(url: String, title: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val root = SERIES_ROOT.find(path)?.groupValues?.get(1).orEmpty()
        val slug = root.trim('/').substringAfterLast('/').ifBlank {
            path.trim('/').substringAfterLast('/')
        }.ifBlank { title }
        return slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private fun resolveUrl(base: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) return maybeRelative
        return runCatching { URI(base).resolve(maybeRelative).toString() }.getOrDefault(maybeRelative)
    }

    /** Collect absolute season page URLs from a series HTML page. */
    fun discoverSeasonUrls(html: String, pageUrl: String): List<Pair<Int, String>> {
        val doc = Jsoup.parse(html, pageUrl)
        val map = linkedMapOf<Int, String>()
        doc.select("a[href*=staffel], a[href*=season]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { resolveUrl(pageUrl, a.attr("href")) }
            val n = Regex("""(?i)(?:staffel|season)[/-]?(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)(?:staffel|season)\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > 0 && href.isNotBlank()) {
                // Prefer season index pages (without episode in path)
                val isEpisode = href.contains("episode", true) || href.contains("folge", true)
                if (!isEpisode || !map.containsKey(n)) {
                    if (!isEpisode) map[n] = href
                    else map.putIfAbsent(n, href.substringBeforeLast("/episode").substringBeforeLast("/folge").ifBlank { href })
                }
            }
        }
        // data-season tabs
        doc.select("[data-season-url], [data-season]").forEach { el ->
            val n = el.attr("data-season").toIntOrNull() ?: return@forEach
            if (n <= 0) return@forEach
            val href = el.attr("abs:data-season-url").ifBlank { el.attr("abs:href") }
            if (href.isNotBlank()) map.putIfAbsent(n, href)
        }
        if (map.isEmpty()) {
            // At least season 1 = current page
            map[1] = pageUrl
        }
        return map.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    companion object {
        private val ABSOLUTE_M3U8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        private val RELATIVE_M3U8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val PLAY_BLOB = Regex("""(?:https?:)?(?://[^"'\\\s]+)?/r\?t=[A-Za-z0-9%._~\-+=/]+""")
        /** Matches /serie/{slug}, /serie/stream/{slug}, /anime/stream/{slug}. */
        private val SERIES_ROOT =
            Regex("""(/(?:serie|series|anime/stream)(?:/stream)?/[^/]+)""", RegexOption.IGNORE_CASE)
    }
}

@JsonClass(generateAdapter = true)
data class JsonCatalog(
    val series: List<JsonSeries> = emptyList()
)

@JsonClass(generateAdapter = true)
data class JsonSeries(
    val id: String,
    val title: String,
    @Json(name = "poster") val posterUrl: String? = null,
    @Json(name = "backdrop") val backdropUrl: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    @Json(name = "tmdb_id") val tmdbId: Int? = null,
    @Json(name = "detail") val detailPath: String? = null,
    val seasons: List<JsonSeason> = emptyList()
) {
    fun toDomain(baseUrl: String): Series = Series(
        id = id,
        title = title,
        posterUrl = abs(baseUrl, posterUrl),
        backdropUrl = abs(baseUrl, backdropUrl),
        overview = overview,
        year = year,
        tmdbId = tmdbId,
        detailPath = abs(baseUrl, detailPath),
        seasons = seasons.map { it.toDomain(baseUrl, id) }
    )
}

@JsonClass(generateAdapter = true)
data class JsonSeason(
    val number: Int,
    val title: String? = null,
    val episodes: List<JsonEpisode> = emptyList()
) {
    fun toDomain(baseUrl: String, seriesId: String): Season = Season(
        number = number,
        title = title ?: "Staffel $number",
        episodes = episodes.map { it.toDomain(baseUrl, seriesId, number) }
    )
}

@JsonClass(generateAdapter = true)
data class JsonEpisode(
    val number: Int,
    val title: String? = null,
    val overview: String? = null,
    @Json(name = "still") val stillUrl: String? = null,
    @Json(name = "stream") val streamUrl: String? = null,
    @Json(name = "stream_page") val streamPageUrl: String? = null,
    val id: String? = null
) {
    fun toDomain(baseUrl: String, seriesId: String, seasonNumber: Int): Episode = Episode(
        id = id ?: "$seriesId-s${seasonNumber}e$number",
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        number = number,
        title = title ?: "Episode $number",
        overview = overview,
        stillUrl = abs(baseUrl, stillUrl),
        streamUrl = abs(baseUrl, streamUrl),
        streamPageUrl = abs(baseUrl, streamPageUrl)
    )
}

private fun abs(baseUrl: String, value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    return runCatching { URI(baseUrl.trimEnd('/') + "/").resolve(value).toString() }.getOrDefault(value)
}
