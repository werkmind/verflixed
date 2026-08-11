package com.streamvault.tv.data.model

data class Catalog(
    val series: List<Series>
)

data class Series(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val detailPath: String? = null,
    val genres: List<String> = emptyList(),
    val seasons: List<Season> = emptyList(),
    /** "series" (default) or "movie" — movies use one synthetic season/episode. */
    val mediaKind: String = "series",
) {
    val isMovie: Boolean get() = mediaKind == "movie"

    fun flatEpisodes(): List<Episode> =
        seasons.sortedBy { it.number }.flatMap { season ->
            season.episodes.sortedBy { it.number }
        }
}

data class Season(
    val number: Int,
    val title: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val episodes: List<Episode> = emptyList()
)

data class Episode(
    val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val number: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    /** Direct HLS playlist URL (.m3u8) or VOE player URL. */
    val streamUrl: String? = null,
    /** Optional page that contains the stream URL; resolved lazily. */
    val streamPageUrl: String? = null
)

data class WatchProgress(
    val episodeId: String,
    val seriesId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long
)

data class HomeRow(
    val title: String,
    val items: List<Series>
)

data class CalendarEntry(
    val seriesId: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val date: String,
    val time: String = "",
    val detailPath: String? = null,
    val coverUrl: String? = null,
    val language: String? = null,
    val released: Boolean = false
) {
    fun label(): String {
        val ep = if (episodeNumber > 0) "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
        else "S${seasonNumber}"
        val whenLabel = listOf(date, time).filter { it.isNotBlank() }.joinToString(" ")
        return "$whenLabel  •  $title $ep"
    }
}

data class FavoriteCacheProgress(
    val seriesId: String,
    val cached: Int,
    val total: Int,
    val status: String,
    val currentEpisodeLabel: String? = null
)

data class GenreChip(
    val id: String,
    val label: String,
    val exclude: Boolean = false
)

object CatalogFilters {
    val GENRES = listOf(
        GenreChip("action", "Action"),
        GenreChip("comedy", "Comedy"),
        GenreChip("drama", "Drama"),
        GenreChip("krimi", "Krimi"),
        GenreChip("thriller", "Thriller"),
        GenreChip("fantasy", "Fantasy"),
        GenreChip("science-fiction", "Sci-Fi"),
        GenreChip("horror", "Horror"),
        GenreChip("anime", "Anime"),
        GenreChip("animation", "Animation"),
        GenreChip("dokumentation", "Doku"),
        GenreChip("romantik", "Romantik"),
        GenreChip("mystery", "Mystery"),
        GenreChip("k-drama", "K-Drama")
    )

    val ANTI_PATTERNS = listOf(
        GenreChip("anime", "Keine Animes", exclude = true),
        GenreChip("comedy", "Keine Comedy", exclude = true),
        GenreChip("animation", "Kein Zeichentrick", exclude = true),
        GenreChip("telenovela", "Keine Telenovelas", exclude = true),
        GenreChip("reality-tv", "Kein Reality", exclude = true),
        GenreChip("kinderserie", "Keine Kinderserien", exclude = true)
    )
}
