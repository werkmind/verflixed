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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Serienkalender – same structure as SerienStream `/api/calendar`.
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
        emptyMap()
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
                if (matchesFavorite(ep, favoriteIds, favoriteTitles)) out += ep
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
    val released: Boolean? = null
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
            released = released == true
        )
    }
}
