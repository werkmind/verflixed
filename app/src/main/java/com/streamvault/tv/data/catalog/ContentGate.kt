package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.CatalogFilters
import com.streamvault.tv.data.model.Series

/**
 * Profile content gate: blocked categories are never shown and their
 * genre pages are never fetched (browse shelves + search).
 */
object ContentGate {
    val DEFAULT_BLOCKED = setOf("horror", "anime")

    private val ALIASES: Map<String, List<String>> = mapOf(
        "horror" to listOf("horror", "splatter", "slasher"),
        "anime" to listOf("anime", "aniworld", "manga"),
        "animation" to listOf("animation", "zeichentrick", "cartoon", "animiert"),
        "action" to listOf("action"),
        "comedy" to listOf("comedy", "komödie", "komodie"),
        "drama" to listOf("drama"),
        "krimi" to listOf("krimi", "crime"),
        "thriller" to listOf("thriller"),
        "fantasy" to listOf("fantasy"),
        "science-fiction" to listOf("science-fiction", "sci-fi", "scifi", "science fiction"),
        "dokumentation" to listOf("dokumentation", "doku", "documentary"),
        "romantik" to listOf("romantik", "romance"),
        "mystery" to listOf("mystery"),
        "k-drama" to listOf("k-drama", "kdrama", "k drama"),
    )

    fun isBlocked(series: Series, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        if ("anime" in blocked && series.malId != null) return true
        return matches(
            title = series.title,
            genres = series.genres,
            detailPath = series.detailPath,
            id = series.id,
            overview = series.overview,
            blocked = blocked,
        )
    }

    fun isBlocked(
        title: String,
        genres: List<String> = emptyList(),
        detailPath: String? = null,
        id: String = "",
        overview: String? = null,
        blocked: Set<String>,
    ): Boolean = matches(title, genres, detailPath, id, overview, blocked)

    private fun matches(
        title: String,
        genres: List<String>,
        detailPath: String?,
        id: String,
        overview: String?,
        blocked: Set<String>,
    ): Boolean {
        if (blocked.isEmpty()) return false
        val genreNorm = genres.map { it.lowercase().trim() }
        val blob = buildString {
            append(title.lowercase())
            append(' ')
            append(id.lowercase())
            if (!detailPath.isNullOrBlank()) {
                append(' ')
                append(detailPath.lowercase())
            }
        }
        val overviewNorm = overview?.lowercase().orEmpty().take(240)
        for (blockedId in blocked) {
            val keys = keysFor(blockedId)
            if (genreNorm.any { g -> keys.any { k -> g == k || g.contains(k) } }) return true
            if (keys.any { k -> k.length >= 4 && (blob.contains(k) || overviewNorm.contains(k)) }) {
                return true
            }
        }
        return false
    }

    private fun keysFor(id: String): List<String> {
        val label = CatalogFilters.GENRES.find { it.id == id }?.label?.lowercase()
        return (ALIASES[id].orEmpty() + listOfNotNull(id, label))
            .map { it.lowercase() }
            .distinct()
    }
}
