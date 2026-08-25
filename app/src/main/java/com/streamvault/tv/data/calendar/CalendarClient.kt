package com.streamvault.tv.data.calendar

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.streamvault.tv.data.model.CalendarEntry
import com.streamvault.tv.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Serienkalender – same structure as SerienStream `/api/calendar`,
 * with HTML fallback for `/serienkalender` pages.
 */
class CalendarClient(
    private val http: OkHttpClient,
    private val prefs: UserPrefs,
    moshi: Moshi
) {
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Types.newParameterizedType(List::class.java, ApiCalendarEpisode::class.java)
    )
    private val adapter = moshi.adapter<Map<String, List<ApiCalendarEpisode>>>(mapType)

    suspend fun fetchSchedule(): Map<String, List<CalendarEntry>> = withContext(Dispatchers.IO) {
        val base = prefs.baseUrl.trimEnd('/')
        if (base.isBlank()) return@withContext emptyMap()
        val candidates = listOf(
            "$base/api/calendar",
            "$base/serienkalender/api",
            "$base/api/serienkalender"
        )
        for (url in candidates) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val parsed = adapter.fromJson(body) ?: return@use null
                    return@withContext parsed.mapValues { (day, eps) ->
                        eps.map { it.toDomain(base, day) }
                    }
                }
            }
        }
        // HTML fallback: scrape Serienkalender page(s)
        scrapeHtmlCalendar(base)
    }

    private fun scrapeHtmlCalendar(base: String): Map<String, List<CalendarEntry>> {
        val pages = listOf(
            "$base/serienkalender",
            "$base/kalender",
            "$base/serienkalender.html",
        )
        val out = linkedMapOf<String, MutableList<CalendarEntry>>()
        for (url in pages) {
            val html = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "text/html")
                    .header("User-Agent", UA)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            }.getOrNull().orEmpty()
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, url)
            doc.select(
                "tr.episode-row, .episode-row, .calendar-episode, [data-episode], " +
                    "a[href*=/serie/][href*=episode], a[href*=/serie/][href*=folge]"
            ).forEach { el ->
                val link = el.selectFirst("a[href*=episode], a[href*=folge]")?.attr("abs:href")
                    ?: el.attr("abs:href").takeIf { it.contains("episode", true) || it.contains("folge", true) }
                    ?: Regex("""['"]([^'"]+(?:episode|folge)[^'"]+)['"]""")
                        .find(el.attr("onclick"))?.groupValues?.get(1)
                        ?.let { if (it.startsWith("http")) it else "$base/${it.trimStart('/')}" }
                    ?: return@forEach
                val season = Regex("""(?i)(?:staffel|season)[/-]?(\d+)""").find(link)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = el.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("""(?i)(?:episode|folge|ep)[/-]?(\d+)""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                val seriesSlug = Regex("""/(?:serie|series)/(?:stream/)?([^/]+)""", RegexOption.IGNORE_CASE)
                    .find(link)?.groupValues?.get(1)?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')
                    .orEmpty()
                val seriesTitle = el.selectFirst(".series-title, .serie-title, .calendar-series-title")?.text()
                    ?.trim()
                    ?: seriesSlug.replace('-', ' ').replaceFirstChar { it.uppercase() }
                val epTitle = el.selectFirst(".episode-title-ger, .episode-title-eng, .episode-title")
                    ?.attr("title")?.ifBlank { null }
                    ?: el.selectFirst(".episode-title-ger, .episode-title-eng, .episode-title")?.text()?.trim()
                val releaseLabel = el.selectFirst(".badge-release")?.text()?.replace('\u00a0', ' ')?.trim()
                val dayKey = releaseLabel?.let { parseGermanReleaseDay(it) }
                    ?: el.attr("data-date").takeIf { it.isNotBlank() }
                    ?: DAY.format(Date())
                // A future air date always means upcoming, even when the site
                // forgets the badge — mislabeled future episodes confuse users.
                val dayInFuture = runCatching { DAY.parse(dayKey) }.getOrNull()
                    ?.after(startOfDay(Date())) == true
                val upcoming = el.hasClass("upcoming") ||
                    el.selectFirst(".badge-upcoming") != null ||
                    el.text().contains("DEMNÄCHST", true) ||
                    dayInFuture
                val cover = el.selectFirst("img[src], img[data-src]")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("abs:data-src") }.ifBlank { img.attr("src") }
                }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                val entry = CalendarEntry(
                    seriesId = seriesSlug.ifBlank { seriesTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-") },
                    title = seriesTitle.ifBlank { seriesSlug },
                    seasonNumber = season,
                    episodeNumber = episode,
                    date = dayKey,
                    time = Regex("""~?\d{1,2}:\d{2}""").find(releaseLabel.orEmpty())?.value.orEmpty(),
                    detailPath = link,
                    coverUrl = cover,
                    released = !upcoming,
                    episodeTitle = epTitle?.takeIf { it.isNotBlank() },
                    releaseLabel = releaseLabel,
                )
                out.getOrPut(dayKey) { mutableListOf() }.add(entry)
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** Best-effort parse of "Freitag, 14.08.2026 ~00:00 Uhr" → yyyy-MM-dd */
    private fun parseGermanReleaseDay(label: String): String? {
        val m = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""").find(label) ?: return null
        val (d, mo, y) = m.destructured
        return "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
    }

    suspend fun favoritesUpcoming(
        favoriteIds: Set<String>,
        favoriteTitles: Set<String>,
        daysAhead: Long = 14
    ): List<CalendarEntry> {
        val schedule = fetchSchedule()
        val today = startOfDay(Date())
        val end = Date(today.time + TimeUnit.DAYS.toMillis(daysAhead))
        val out = mutableListOf<CalendarEntry>()
        schedule.forEach { (day, eps) ->
            val date = parseDay(day) ?: return@forEach
            if (date.before(today) || date.after(end)) return@forEach
            eps.forEach { ep ->
                if (matchesFavorite(ep, favoriteIds, favoriteTitles)) {
                    out += if (ep.released) ep.copy(released = false) else ep
                }
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.time }, { it.title }))
    }

    suspend fun favoritesRecent(
        favoriteIds: Set<String>,
        favoriteTitles: Set<String>,
        daysBack: Long = 3
    ): List<CalendarEntry> {
        val schedule = fetchSchedule()
        val today = startOfDay(Date())
        val start = Date(today.time - TimeUnit.DAYS.toMillis(daysBack))
        val out = mutableListOf<CalendarEntry>()
        schedule.forEach { (day, eps) ->
            val date = parseDay(day) ?: return@forEach
            if (date.before(start) || date.after(today)) return@forEach
            eps.forEach { ep ->
                if (matchesFavorite(ep, favoriteIds, favoriteTitles)) out += ep.copy(released = true)
            }
        }
        return out.sortedWith(compareByDescending<CalendarEntry> { it.date }.thenByDescending { it.time })
    }

    /** Next N days of the full Serienkalender (not only favorites). */
    suspend fun weekAhead(daysAhead: Long = 7): List<CalendarEntry> {
        val schedule = fetchSchedule()
        val today = startOfDay(Date())
        val end = Date(today.time + TimeUnit.DAYS.toMillis(daysAhead))
        val out = mutableListOf<CalendarEntry>()
        schedule.forEach { (day, eps) ->
            val date = parseDay(day) ?: return@forEach
            if (date.before(today) || date.after(end)) return@forEach
            out += eps
        }
        return out.sortedWith(compareBy({ it.date }, { it.time }, { it.title })).take(48)
    }

    private fun parseDay(day: String): Date? =
        runCatching { DAY.parse(day) }.getOrNull()?.let { startOfDay(it) }

    private fun startOfDay(date: Date): Date {
        val c = Calendar.getInstance()
        c.time = date
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.time
    }

    private fun matchesFavorite(
        ep: CalendarEntry,
        favoriteIds: Set<String>,
        favoriteTitles: Set<String>
    ): Boolean {
        val id = ep.seriesId.lowercase()
        if (id in favoriteIds) return true
        val title = ep.title.lowercase()
        if (title in favoriteTitles) return true
        return favoriteIds.any { id.contains(it) || it.contains(id) } ||
            favoriteTitles.any { title.contains(it) || it.contains(title) }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val displayDate = SimpleDateFormat("dd.MM.", Locale.GERMAN)
    }
}

@JsonClass(generateAdapter = true)
data class ApiCalendarEpisode(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val date: String? = null,
    val time: String? = null,
    val url: String? = null,
    val cover_url: String? = null,
    val poster: String? = null,
    val language: String? = null,
    val released: Boolean? = null,
    val episode_title: String? = null,
    val release_label: String? = null,
) {
    fun toDomain(base: String, dayKey: String): CalendarEntry {
        val path = url.orEmpty()
        val slug = Regex("""/(?:serie|series)/(?:stream/)?([^/]+)""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            .orEmpty()
        val cover = when {
            !cover_url.isNullOrBlank() && cover_url.startsWith("http") -> cover_url
            !cover_url.isNullOrBlank() -> base.trimEnd('/') + "/" + cover_url.trimStart('/')
            !poster.isNullOrBlank() && poster.startsWith("http") -> poster
            !poster.isNullOrBlank() -> base.trimEnd('/') + "/" + poster.trimStart('/')
            else -> null
        }
        return CalendarEntry(
            seriesId = slug.ifBlank { title.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-") },
            title = title.orEmpty().ifBlank { slug },
            seasonNumber = season ?: 1,
            episodeNumber = episode ?: 0,
            date = date ?: dayKey,
            time = time.orEmpty(),
            detailPath = when {
                path.startsWith("http") -> path
                path.isNotBlank() -> base.trimEnd('/') + "/" + path.trimStart('/')
                else -> null
            },
            coverUrl = cover,
            language = language,
            released = released == true,
            episodeTitle = episode_title,
            releaseLabel = release_label,
        )
    }
}
