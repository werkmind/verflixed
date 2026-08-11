package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Season
import com.streamvault.tv.data.model.Series
import org.jsoup.Jsoup
import java.net.URI

/**
 * Filmpalast-style movie catalog / detail parser.
 * Movies are modeled as [Series] with mediaKind="movie" and one synthetic season/episode.
 */
object FilmParser {
    private val EP_RE = Regex("""\bS\d{1,2}E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    private val STREAM_RE = Regex("""/stream/[^/?#]+""", RegexOption.IGNORE_CASE)

    data class Hoster(
        val provider: String,
        val name: String,
        val url: String,
        val score: Int,
        val language: String = "",
    )

    fun browsePaths(): List<String> = listOf("/movies/new", "/movies/top", "/")

    fun isMovieSite(baseUrl: String): Boolean {
        val h = baseUrl.lowercase()
        return h.contains("filmpalast") || h.contains("movie") || h.contains("film")
    }

    fun isEpisodeLike(title: String?, url: String?): Boolean =
        EP_RE.containsMatchIn(title.orEmpty()) || EP_RE.containsMatchIn(url.orEmpty())

    /** Detect audio language from Filmpalast detail HTML / release title / URL. */
    fun detectPageLanguage(html: String, title: String = "", pageUrl: String = ""): String {
        val release = Regex(
            """id=["']release_text["'][^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1).orEmpty()
        val fromMeta = Regex(
            """itemprop=["']inLanguage["'][^>]*content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        val genreEn = html.contains("/search/genre/Englisch", ignoreCase = true) &&
            (title.contains("ENGLISH", true) || pageUrl.contains("-english", true))
        return StreamLanguage.detectFromText(
            pageUrl,
            title,
            release,
            fromMeta,
            if (genreEn) "english" else null,
            html.take(6000),
        ) ?: StreamLanguage.DE
    }

    /**
     * Suggest sibling Filmpalast URLs for the other language (DE↔EN).
     * Filmpalast keeps German and English as separate /stream/{slug} pages.
     */
    fun siblingLanguageUrls(pageUrl: String, currentLang: String): List<String> {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val path = uri.path.orEmpty()
        val m = Regex("""^(.*?/stream/)([^/?#]+)/?$""", RegexOption.IGNORE_CASE).find(path)
            ?: return emptyList()
        val prefix = m.groupValues[1]
        val slug = m.groupValues[2]
        val candidates = linkedSetOf<String>()
        val lang = StreamLanguage.normalize(currentLang)
        if (lang == StreamLanguage.EN) {
            val stripped = slug
                .replace(Regex("""-english$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""-eng$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""-ovo$""", RegexOption.IGNORE_CASE), "")
            if (stripped.isNotBlank() && stripped != slug) {
                candidates += uri.resolve("$prefix$stripped").toString().trimEnd('/')
            }
            // minions-monsters-english → minions-monster (common Filmpalast plural quirk)
            val singular = stripped.replace(Regex("""s$"""), "")
            if (singular.isNotBlank() && singular != stripped) {
                candidates += uri.resolve("$prefix$singular").toString().trimEnd('/')
            }
        } else {
            candidates += uri.resolve("$prefix$slug-english").toString().trimEnd('/')
            candidates += uri.resolve("$prefix${slug}s-english").toString().trimEnd('/')
            candidates += uri.resolve("$prefix$slug-eng").toString().trimEnd('/')
        }
        return candidates.filter { it != pageUrl.trimEnd('/') }
    }

    fun languageFromMovieHit(title: String, url: String): String =
        detectPageLanguage("", title, url)

    fun parseMovieList(
        html: String,
        baseUrl: String,
        moviesOnly: Boolean = true,
    ): List<Series> {
        val doc = Jsoup.parse(html, baseUrl)
        val out = mutableListOf<Series>()
        val seen = linkedSetOf<String>()

        fun addFrom(hrefRaw: String, titleRaw: String, posterRaw: String?) {
            val href = abs(baseUrl, hrefRaw)
            if (!STREAM_RE.containsMatchIn(href) || !seen.add(href)) return
            val title = cleanTitle(titleRaw)
            if (title.isBlank()) return
            if (moviesOnly && isEpisodeLike(title, href)) return
            val poster = posterRaw?.takeIf { it.isNotBlank() }?.let { abs(baseUrl, it) }
            out += Series(
                id = slugId(href, title),
                title = title,
                posterUrl = poster,
                backdropUrl = poster,
                detailPath = href,
                mediaKind = "movie",
            )
        }

        doc.select("article.liste, article.glowliste, article").forEach { art ->
            val a = art.selectFirst("h2 a[href*=/stream/]")
                ?: art.selectFirst("a[href*=/stream/]")
                ?: return@forEach
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val title = a.attr("title").ifBlank { a.text() }
            val img = art.selectFirst("img.cover-opacity, img.cover2, a img[src*=/files/movies/]")
                ?: art.selectFirst("img[src*=/files/movies/]")
            val poster = img?.let { imageSrc(it) }
            addFrom(href, title, poster)
        }

        if (out.isEmpty()) {
            doc.select("a[href*=/stream/]").forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val title = a.attr("title").ifBlank { a.text() }
                if (title.length < 2) return@forEach
                addFrom(href, title, null)
            }
        }
        return out
    }

    fun parseMovieDetail(html: String, pageUrl: String, idHint: String? = null): Series {
        val doc = Jsoup.parse(html, pageUrl)
        val title = cleanTitle(
            doc.selectFirst("article.detail h2, h2.bgDark, h2")?.text()
                ?: doc.selectFirst("meta[itemprop=name], .fn .value-title")?.attr("title")
                ?: doc.title()
        )
        val overview = doc.selectFirst("[itemprop=description]")?.text()?.trim()
            ?: doc.selectFirst("cite > span.hidden")?.text()?.trim()
        val year = Regex("""Veröffentlicht:\s*(\d{4})""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull()

        var poster: String? = null
        val posterEl = doc.selectFirst("img.cover2")
            ?: doc.selectFirst("img[itemprop=image], img[src*=/files/movies/]")
        if (posterEl != null) {
            poster = abs(pageUrl, imageSrc(posterEl).orEmpty()).takeIf { it.isNotBlank() }
        }
        if (poster.isNullOrBlank()) {
            Regex("""/files/movies/\d+/[^"'\\\s>]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                .find(html)?.value?.let { poster = abs(pageUrl, it) }
        }

        val pageLang = detectPageLanguage(html, title, pageUrl)
        val genres = doc.select("#detail-content-list a[href*=/search/genre/]")
            .map { cleanTitle(it.text()) }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        // Surface audio language as a genre chip for detail UI (Deutsch / Englisch).
        val langLabel = StreamLanguage.label(pageLang)
        if (genres.none { it.equals(langLabel, true) || it.equals("Deutsch", true) || it.equals("Englisch", true) }) {
            genres.add(0, langLabel)
        }

        val id = idHint?.takeIf { it.isNotBlank() } ?: slugId(pageUrl, title)
        return Series(
            id = id,
            title = title,
            overview = overview,
            year = year,
            posterUrl = poster,
            backdropUrl = poster,
            detailPath = pageUrl,
            mediaKind = "movie",
            genres = genres,
            availableLanguages = listOf(pageLang),
            languagePages = mapOf(pageLang to pageUrl),
            seasons = listOf(
                Season(
                    number = 1,
                    title = "Film",
                    posterUrl = poster,
                    backdropUrl = poster,
                    episodes = listOf(
                        Episode(
                            id = "$id-movie",
                            seriesId = id,
                            seasonNumber = 1,
                            number = 1,
                            title = title.ifBlank { "Film" },
                            overview = overview,
                            stillUrl = poster,
                            streamPageUrl = pageUrl,
                            streamUrl = null,
                        )
                    )
                )
            )
        )
    }

    fun scoreHoster(
        name: String,
        url: String = "",
        language: String = "",
        preferredLang: String = StreamLanguage.DE,
    ): Int {
        val n = "$name $url".lowercase()
        var s = 0
        // Firestream first for Filmpalast: progressive CDN, fewer geo/encoding fails than VOE.
        if (n.contains("firestream")) s += 120
        if (Regex("""\bvoe\b""").containsMatchIn(n) || n.contains("voe.sx")) s += 100
        if (n.contains("vidara") || n.contains("vidnest")) s += 70
        if (n.contains("vidsonic")) s += 40
        if (n.contains("playmate")) s += 20
        if (Regex("""\bhd\b""").containsMatchIn(n)) s += 5
        if (language.isNotBlank()) {
            if (StreamLanguage.matchesPreferred(language, preferredLang)) s += 80
            else s -= 40
        }
        return s
    }

    fun parseHosters(
        html: String,
        pageUrl: String,
        preferredLang: String = StreamLanguage.DE,
    ): List<Hoster> {
        val doc = Jsoup.parse(html, pageUrl)
        val pageLang = detectPageLanguage(
            html,
            doc.selectFirst("article.detail h2, h2.bgDark, h2")?.text().orEmpty(),
            pageUrl,
        )
        val hosters = mutableListOf<Hoster>()
        doc.select("ul.currentStreamLinks").forEach { ul ->
            val name = ul.selectFirst(".hostName")?.text()?.trim().orEmpty().ifBlank { "Hoster" }
            // Prefer live data-player-url; skip commented markup; accept plain iconPlay hrefs.
            val a = ul.select("a[data-player-url]").firstOrNull { it.attr("data-player-url").isNotBlank() }
                ?: ul.select("a.iconPlay[href], a.button.iconPlay[href], a.button[href]")
                    .firstOrNull { href ->
                        val h = href.attr("abs:href").ifBlank { href.attr("href") }
                        h.isNotBlank() && h != "#" && !h.contains("javascript:", true)
                    }
                ?: return@forEach
            val raw = a.attr("data-player-url").ifBlank { a.attr("abs:href") }.ifBlank { a.attr("href") }
            if (raw.isBlank() || raw == "#") return@forEach
            val url = abs(pageUrl, raw)
            if (url.isBlank()) return@forEach
            val hosterLang = a.attr("data-language-label").ifBlank { a.attr("data-language") }
                .ifBlank { ul.attr("data-language") }
                .ifBlank { pageLang }
            val score = scoreHoster(name, url, hosterLang, preferredLang)
            hosters += Hoster(
                provider = name,
                name = name,
                url = url,
                score = score,
                language = hosterLang,
            )
        }
        return hosters.sortedByDescending { it.score }
    }

    fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\*\d{4}\*"""), "")
            .trim()

    fun slugId(url: String, title: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val slug = Regex("""/stream/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)
            ?: path.split('/').filter { it.isNotBlank() }.lastOrNull()
            ?: title.ifBlank { "movie" }
        return slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    fun abs(base: String, href: String): String {
        if (href.isBlank()) return ""
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)
    }

    private fun imageSrc(el: org.jsoup.nodes.Element): String? {
        val raw = el.attr("abs:src").ifBlank { el.attr("src") }
            .ifBlank { el.attr("abs:data-src") }
            .ifBlank { el.attr("data-src") }
            .ifBlank { el.attr("content") }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }
}
