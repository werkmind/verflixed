package com.streamvault.tv.data.catalog

import com.squareup.moshi.Moshi
import com.streamvault.tv.data.calendar.CalendarClient
import com.streamvault.tv.data.db.AppDatabase
import com.streamvault.tv.data.db.FavoriteEntity
import com.streamvault.tv.data.db.StreamCacheEntity
import com.streamvault.tv.data.db.WatchProgressEntity
import com.streamvault.tv.data.meta.TvMazeClient
import com.streamvault.tv.data.model.CalendarEntry
import com.streamvault.tv.data.model.Catalog
import com.streamvault.tv.data.model.CatalogFilters
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.FavoriteCacheProgress
import com.streamvault.tv.data.model.HomeRow
import com.streamvault.tv.data.model.Season
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.data.profile.ProfileRepository
import com.streamvault.tv.data.tmdb.TmdbClient
import com.streamvault.tv.util.VfCodes
import com.streamvault.tv.util.VfException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale

class CatalogRepository(
    private val http: OkHttpClient,
    private val parser: CatalogParser,
    private val prefs: UserPrefs,
    private val db: AppDatabase,
    private val tmdb: TmdbClient,
    private val tvMaze: TvMazeClient,
    private val calendar: CalendarClient,
    private val profiles: ProfileRepository,
    private val moshi: Moshi,
    private val cacheDir: File,
    private val artResolver: SeriesArtResolver,
    private val voeExtractor: VoeExtractor = VoeExtractor(http),
    private val vidaraExtractor: VidaraExtractor = VidaraExtractor(http),
    private val firestreamExtractor: FirestreamExtractor = FirestreamExtractor(http),
) {
    private val mutex = Mutex()
    @Volatile private var memoryCatalog: Catalog? = null
    @Volatile private var memoryMoviesCatalog: Catalog? = null
    @Volatile private var memoryKind: String? = null
    private val seriesAdapter get() = moshi.adapter(Series::class.java)
    private val genreMembers = mutableMapOf<String, Set<String>>()
    private val genreSeriesCache = mutableMapOf<String, List<Series>>()
    /** Live-search / deep-link hits that are not in the home catalog slice. */
    private val searchHitCache = java.util.concurrent.ConcurrentHashMap<String, Series>()

    private suspend fun pid(): String = profiles.activeId()

    fun rememberSeriesHit(series: Series) {
        if (series.id.isNotBlank()) searchHitCache[series.id] = series
    }

    fun rememberSeriesHits(hits: List<Series>) {
        hits.forEach { rememberSeriesHit(it) }
    }
    private fun activeBase(): String = prefs.activeBaseUrl().trimEnd('/')

    private fun isMoviesMode(): Boolean = prefs.isMovies

    /** Active profile stream language preference (default Deutsch). */
    private fun preferredLang(): String =
        StreamLanguage.normalize(prefs.streamLanguage(prefs.activeProfileId))

    private fun cacheKind(url: String, language: String? = null): String {
        val base = StreamKind.streamKindLabel(url)
        val lang = StreamLanguage.normalize(language ?: preferredLang())
        return "$base|$lang"
    }

    private fun cacheLangMatches(kind: String?, preferred: String): Boolean {
        val k = kind.orEmpty()
        val sep = k.lastIndexOf('|')
        // Legacy rows without |lang must not win — they often keep the wrong audio after DE/EN switch.
        if (sep < 0) return false
        return StreamLanguage.normalize(k.substring(sep + 1)) == StreamLanguage.normalize(preferred)
    }

    suspend fun validateBaseUrl(url: String): Result<Catalog> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = url.trim().trimEnd('/')
            require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
                "URL muss mit http:// oder https:// beginnen"
            }
            val catalog = fetchCatalog(normalized)
            if (catalog.series.isEmpty()) {
                // Filmpalast-like movie sites: /movies/new has articles
                val movies = fetchMoviesCatalog(normalized)
                if (movies.series.isEmpty()) {
                    throw VfException.of(VfCodes.CATALOG_EMPTY, "Katalog leer – keine Serien/Filme gefunden")
                }
                return@runCatching movies
            }
            catalog
        }.recoverCatching { t ->
            if (t is VfException) throw t
            throw VfException.of(VfCodes.CATALOG_UNREACHABLE, t.message ?: "Katalog nicht erreichbar", t)
        }
    }

    suspend fun getBrowseRows(forceRefresh: Boolean = false): List<HomeRow> = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            // Browse must not keep stale image/meta — wipe in-memory art only (never Room).
            artResolver.clear()
            genreSeriesCache.clear()
            genreMembers.clear()
        }
        val catalog = loadCatalog(forceRefresh)
        // Browse: no Room/meta cache. Hydrate cover URLs from genre HTML (in-memory) only.
        val page = prefs.browsePage
        val size = UserPrefs.BROWSE_PAGE_SIZE
        val filtered = if (isMoviesMode()) {
            catalog.series
        } else {
            applyGenreFilters(catalog.series)
        }.map { hydrateBrowseArt(it) }
        val from = (page * size).coerceAtMost(filtered.size)
        val to = (from + size).coerceAtMost(filtered.size)
        val slice = filtered.subList(from, to)
        val rows = mutableListOf<HomeRow>()

        // Page 0: put homepage hero / Brandneu first
        if (!isMoviesMode() && page == 0) {
            val base = activeBase()
            if (base.isNotBlank()) {
                val homeHtml = runCatching { getText(base) }.getOrNull().orEmpty()
                val neu = parser.parseHomeHeroNewReleases(homeHtml, base)
                    .map { hydrateBrowseArt(it) }
                    .take(16)
                if (neu.isNotEmpty()) rows += HomeRow("Neu", neu)
                val week = runCatching { calendar.weekAhead() }.getOrDefault(emptyList())
                if (week.isNotEmpty()) {
                    rows += HomeRow(
                        "Serienkalender",
                        week.distinctBy { it.seriesId + it.date }.take(16).map { e ->
                            Series(
                                id = e.seriesId,
                                title = "${e.title} · S${e.seasonNumber}E${e.episodeNumber}",
                                posterUrl = e.coverUrl,
                                backdropUrl = e.coverUrl,
                                overview = e.label(),
                                detailPath = e.detailPath,
                                mediaKind = "series",
                            )
                        }
                    )
                }
            }
        }

        if (slice.isNotEmpty()) {
            val allLabel = if (isMoviesMode()) "Alle Filme" else "Alle Serien"
            val label = if (filtered.size > size) {
                "$allLabel (${from + 1}–$to)"
            } else {
                allLabel
            }
            // Avoid duplicating Neu items in the first alle-slice
            val neuIds = rows.firstOrNull { it.title == "Neu" }?.items?.map { it.id }?.toSet().orEmpty()
            val deduped = if (neuIds.isEmpty()) slice else slice.filterNot { it.id in neuIds }
            if (deduped.isNotEmpty()) rows += HomeRow(label, deduped)
        }
        if (isMoviesMode()) {
            // Extra movie shelves from alternate browse paths when not already the main list.
            FilmParser.browsePaths().drop(1).forEach { path ->
                val base = activeBase()
                if (base.isBlank()) return@forEach
                val body = runCatching { getText("$base$path") }.getOrNull() ?: return@forEach
                val items = FilmParser.parseMovieList(body, base, moviesOnly = true)
                    .map { hydrateBrowseArt(it) }
                    .take(16)
                if (items.isNotEmpty()) {
                    val title = when {
                        path.contains("top") -> "Top Filme"
                        path == "/" -> "Beliebt"
                        else -> path.trim('/')
                    }
                    rows += HomeRow(title, items)
                }
            }
            // Category shelves scraped from Filmpalast genre search pages
            CatalogFilters.GENRES.take(8).forEach { genre ->
                val genreMovies = runCatching { seriesForGenre(genre.id) }.getOrDefault(emptyList())
                if (genreMovies.isEmpty()) return@forEach
                val items = genreMovies.map { hydrateBrowseArt(it) }.take(16)
                if (items.isNotEmpty()) rows += HomeRow(genre.label, items)
            }
            return@withContext rows
        }
        // Category rows (limited) from genre pages — premium “shelves” with real covers
        CatalogFilters.GENRES.take(8).forEach { genre ->
            val genreSeries = runCatching { seriesForGenre(genre.id) }.getOrDefault(emptyList())
            if (genreSeries.isEmpty()) return@forEach
            val items = genreSeries.map { hydrateBrowseArt(it) }.take(16)
            if (items.isNotEmpty()) rows += HomeRow(genre.label, items)
        }
        rows
    }

    /** In-memory cover hydrate for Browse — never writes Room. */
    private fun hydrateBrowseArt(series: Series): Series {
        artResolver.applyCached(series).let { cached ->
            if (!cached.posterUrl.isNullOrBlank() || !cached.backdropUrl.isNullOrBlank()) {
                return cached.copy(
                    posterUrl = SiteImages.preferJpeg(cached.posterUrl),
                    backdropUrl = SiteImages.preferJpeg(cached.backdropUrl ?: cached.posterUrl)
                )
            }
        }
        // Merge art discovered via genre pages
        genreSeriesCache.values.asSequence()
            .flatten()
            .firstOrNull { it.id == series.id }
            ?.let { g ->
                val poster = SiteImages.preferJpeg(series.posterUrl ?: g.posterUrl)
                val backdrop = SiteImages.preferJpeg(series.backdropUrl ?: g.backdropUrl ?: g.posterUrl)
                if (poster != null || backdrop != null) {
                    artResolver.putAll(listOf(series.copy(posterUrl = poster, backdropUrl = backdrop)))
                    return series.copy(posterUrl = poster, backdropUrl = backdrop)
                }
            }
        return series.copy(
            posterUrl = SiteImages.preferJpeg(series.posterUrl),
            backdropUrl = SiteImages.preferJpeg(series.backdropUrl)
        )
    }

    suspend fun resolveBrowseArt(series: Series): Series = artResolver.resolve(series)

    fun canLoadMoreBrowse(totalHint: Int = -1): Boolean {
        val mem = if (isMoviesMode()) memoryMoviesCatalog else memoryCatalog
        val total = if (totalHint >= 0) totalHint else mem?.series?.size ?: 0
        return (prefs.browsePage + 1) * UserPrefs.BROWSE_PAGE_SIZE < total
    }

    suspend fun loadMoreBrowse(): List<HomeRow> = withContext(Dispatchers.IO) {
        prefs.browsePage = prefs.browsePage + 1
        getBrowseRows(forceRefresh = false)
    }

    suspend fun resetBrowsePage() {
        prefs.browsePage = 0
    }

    suspend fun getLibraryRows(): List<HomeRow> = withContext(Dispatchers.IO) {
        val catalog = runCatching { loadCatalog(false) }.getOrNull()
        val favEntities = db.favorites().all(pid())
        val favorites = favEntities.mapNotNull { fav ->
            runCatching { seriesAdapter.fromJson(fav.cachedJson) }.getOrNull()
                ?: catalog?.series?.find { it.id == fav.seriesId }?.copy(
                    title = fav.title,
                    posterUrl = fav.posterUrl
                )
        }
        val continueIdsOrdered = db.watch().all(pid())
            .filter { !it.completed && it.positionMs > 5_000 }
            .sortedByDescending { it.updatedAt }
            .map { it.seriesId }
            .distinct()
        val continueWatching = continueIdsOrdered.mapNotNull { id ->
            favorites.find { it.id == id } ?: catalog?.series?.find { it.id == id }
        }
        // One big library: all favorites (and continue items that aren't favs), no redundant Favoriten row
        val continueIdSet = continueWatching.map { it.id }.toSet()
        // Favorites only in library shelves — do NOT re-merge continue items (avoids duplicates).
        val seriesFavs = favorites.filter { it.mediaKind != "movie" }
            .sortedBy { it.title.lowercase() }
        val movieFavs = favorites.filter { it.mediaKind == "movie" }
            .sortedBy { it.title.lowercase() }

        val recentlyWatched = db.watch().all(pid())
            .filter { it.completed }
            .sortedByDescending { it.updatedAt }
            .map { it.seriesId }
            .distinct()
            .mapNotNull { id -> favorites.find { it.id == id } ?: catalog?.series?.find { it.id == id } }
            .filterNot { it.id in continueIdSet }
            .take(12)

        val favIds = favorites.map { it.id.lowercase() }.toSet()
        val favTitles = favorites.map { it.title.lowercase() }.toSet()
        val shownIds = linkedSetOf<String>().apply {
            addAll(continueIdSet)
            addAll(seriesFavs.map { it.id })
            addAll(movieFavs.map { it.id })
        }
        val upcoming = runCatching { calendar.favoritesUpcoming(favIds, favTitles) }.getOrDefault(emptyList())
            .filterNot { it.seriesId in shownIds }
        val recentNew = runCatching { calendar.favoritesRecent(favIds, favTitles) }.getOrDefault(emptyList())
            .filterNot { it.seriesId in shownIds || upcoming.any { u -> u.seriesId == it.seriesId } }
        val weekCalendar = runCatching { calendar.weekAhead() }.getOrDefault(emptyList())
            .filterNot {
                it.seriesId in shownIds ||
                    upcoming.any { u -> u.seriesId == it.seriesId } ||
                    recentNew.any { u -> u.seriesId == it.seriesId }
            }

        // Represent calendar hits as lightweight Series cards for the row UI.
        fun calendarAsSeries(entries: List<CalendarEntry>): List<Series> =
            entries.distinctBy { it.seriesId + it.date + it.episodeNumber }.take(24).map { e ->
                val ep = if (e.episodeNumber > 0) {
                    "S${e.seasonNumber.toString().padStart(2, '0')}E${e.episodeNumber.toString().padStart(2, '0')}"
                } else {
                    "S${e.seasonNumber}"
                }
                val badge = when {
                    !e.released -> e.releaseLabel?.takeIf { it.isNotBlank() }?.let { "DEMNÄCHST · $it" }
                        ?: "DEMNÄCHST"
                    else -> e.releaseLabel ?: listOf(e.date, e.time).filter { it.isNotBlank() }.joinToString(" ")
                }
                Series(
                    id = e.seriesId,
                    title = e.title,
                    posterUrl = e.coverUrl,
                    backdropUrl = e.coverUrl,
                    overview = listOfNotNull(
                        badge,
                        e.episodeTitle?.takeIf { it.isNotBlank() }?.let { "$ep – $it" } ?: ep,
                    ).joinToString("\n"),
                    detailPath = e.detailPath,
                    mediaKind = "series",
                    year = null,
                    genres = listOfNotNull(badge.takeIf { it.isNotBlank() }),
                )
            }

        buildList {
            if (continueWatching.isNotEmpty()) {
                add(HomeRow("Weiterschauen", continueWatching.take(8)))
            }
            if (seriesFavs.isNotEmpty()) add(HomeRow("Meine Serien", seriesFavs))
            if (movieFavs.isNotEmpty()) add(HomeRow("Meine Filme", movieFavs))
            // A–Z combined once (no duplicate of Meine Serien/Filme items beyond the dedicated shelves)
            val az = (seriesFavs + movieFavs).distinctBy { it.id }.sortedBy { it.title.lowercase() }
            if (az.size >= 8) add(HomeRow("A–Z", az))
            if (upcoming.isNotEmpty()) add(HomeRow("Kalender · Demnächst", calendarAsSeries(upcoming)))
            if (recentNew.isNotEmpty()) add(HomeRow("Kalender · Neu", calendarAsSeries(recentNew)))
            if (weekCalendar.isNotEmpty()) add(HomeRow("Serienkalender", calendarAsSeries(weekCalendar)))
            if (recentlyWatched.isNotEmpty()) add(HomeRow("Zuletzt gesehen", recentlyWatched))
        }
    }

    /** @deprecated use getBrowseRows / getLibraryRows */
    suspend fun getHomeRows(forceRefresh: Boolean = false): List<HomeRow> = withContext(Dispatchers.IO) {
        getLibraryRows() + getBrowseRows(forceRefresh)
    }

    suspend fun search(query: String): List<Series> = withContext(Dispatchers.IO) {
        val raw = query.trim()
        val q = raw.lowercase()
        val kind = prefs.mediaKind
        val catalog = loadCatalog(false)
        val favs = db.favorites().all(pid()).mapNotNull {
            runCatching { seriesAdapter.fromJson(it.cachedJson) }.getOrNull()
        }.filter { fav ->
            val favKind = if (fav.mediaKind == "movie") "movie" else "series"
            favKind == kind
        }
        val pool = (favs + catalog.series).distinctBy { it.id }
        val filtered = if (isMoviesMode()) pool else applyGenreFilters(pool)
        if (q.isEmpty()) return@withContext filtered.take(48)

        val localHits = filtered.filter {
            it.title.lowercase().contains(q) ||
                it.overview?.lowercase()?.contains(q) == true ||
                it.genres.any { g -> g.contains(q) } ||
                it.id.contains(q)
        }

        // Live site search – uses active kind base.
        val base = activeBase()
        val siteHits = if (base.isNotBlank() && raw.length >= 2) {
            runCatching {
                SiteSearch.search(http, base, raw, USER_AGENT, mediaKind = kind)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val byId = LinkedHashMap<String, Series>()
        // Prefer catalog/fav entries (often have art) when IDs collide.
        for (s in localHits) byId[s.id] = s
        for (s in siteHits) {
            val existing = byId[s.id]
            if (existing == null) {
                byId[s.id] = s
            } else {
                byId[s.id] = existing.copy(
                    title = existing.title.ifBlank { s.title },
                    overview = existing.overview ?: s.overview,
                    detailPath = existing.detailPath ?: s.detailPath,
                    mediaKind = existing.mediaKind.ifBlank { s.mediaKind },
                )
            }
        }
        byId.values.toList().also { rememberSeriesHits(it) }
    }

    suspend fun searchGrouped(query: String): List<HomeRow> = withContext(Dispatchers.IO) {
        val hits = search(query).map { hydrateBrowseArt(it) }
        if (hits.isEmpty()) return@withContext emptyList()
        listOf(HomeRow(if (query.isBlank()) "Empfohlen" else "Live-Treffer", hits.take(48)))
    }

    /**
     * Global search across library + series catalog + movie catalog.
     * [priorityKind] ("series"|"movie"|null) rows are listed first.
     */
    suspend fun searchGlobal(query: String, priorityKind: String? = null): List<HomeRow> =
        withContext(Dispatchers.IO) {
            val raw = query.trim()
            val q = raw.lowercase()
            val catalogSeries = runCatching {
                val prev = prefs.mediaKind
                prefs.mediaKind = "series"
                try {
                    loadCatalog(false).series.filter { it.mediaKind != "movie" }
                } finally {
                    prefs.mediaKind = prev
                }
            }.getOrDefault(emptyList())
            val catalogMovies = runCatching {
                val prev = prefs.mediaKind
                prefs.mediaKind = "movie"
                try {
                    loadCatalog(false).series.filter { it.mediaKind == "movie" }
                } finally {
                    prefs.mediaKind = prev
                }
            }.getOrDefault(emptyList())
            val favs = db.favorites().all(pid()).mapNotNull {
                runCatching { seriesAdapter.fromJson(it.cachedJson) }.getOrNull()
            }
            fun match(list: List<Series>): List<Series> {
                if (q.isEmpty()) return list.take(24)
                return list.filter {
                    it.title.lowercase().contains(q) ||
                        it.overview?.lowercase()?.contains(q) == true ||
                        it.genres.any { g -> g.lowercase().contains(q) } ||
                        it.id.contains(q)
                }
            }
            val libHits = match(favs)
            val seriesHits = match(catalogSeries)
            val movieHits = match(catalogMovies)

            // Live site searches (best-effort)
            val liveSeries = if (raw.length >= 2) {
                runCatching {
                    SiteSearch.search(http, prefs.seriesBaseUrl, raw, USER_AGENT, mediaKind = "series")
                }.getOrDefault(emptyList())
            } else emptyList()
            val liveMovies = if (raw.length >= 2) {
                runCatching {
                    SiteSearch.search(http, prefs.moviesBaseUrl, raw, USER_AGENT, mediaKind = "movie")
                }.getOrDefault(emptyList())
            } else emptyList()

            fun merge(a: List<Series>, b: List<Series>): List<Series> {
                val map = LinkedHashMap<String, Series>()
                a.forEach { map[it.id] = it }
                b.forEach { s ->
                    val ex = map[s.id]
                    map[s.id] = ex?.copy(
                        title = ex.title.ifBlank { s.title },
                        overview = ex.overview ?: s.overview,
                        detailPath = ex.detailPath ?: s.detailPath,
                        posterUrl = ex.posterUrl ?: s.posterUrl,
                    ) ?: s
                }
                return map.values.toList()
            }

            val seriesRow = merge(seriesHits, liveSeries).take(36)
            val movieRow = merge(movieHits, liveMovies).take(36)
            val libRow = libHits.take(24)
            rememberSeriesHits(seriesRow + movieRow + libRow)

            val rows = mutableListOf<HomeRow>()
            fun addRow(title: String, items: List<Series>) {
                if (items.isNotEmpty()) rows += HomeRow(title, items)
            }
            when (priorityKind) {
                "movie" -> {
                    addRow("Filme", movieRow)
                    addRow("Serien", seriesRow)
                    addRow("Meine Bibliothek", libRow)
                }
                "series" -> {
                    addRow("Serien", seriesRow)
                    addRow("Filme", movieRow)
                    addRow("Meine Bibliothek", libRow)
                }
                else -> {
                    addRow("Meine Bibliothek", libRow)
                    addRow("Serien", seriesRow)
                    addRow("Filme", movieRow)
                }
            }
            rows
        }


    private suspend fun applyGenreFilters(series: List<Series>): List<Series> {
        // Antifilter UI removed — never filter by include/exclude genres.
        return series
    }

    private suspend fun seriesIdsForGenre(genreId: String): Set<String> =
        seriesForGenre(genreId).map { it.id }.toSet()

    private suspend fun seriesForGenre(genreId: String): List<Series> {
        val cacheKey = "${if (isMoviesMode()) "m" else "s"}:$genreId"
        genreSeriesCache[cacheKey]?.let { return it }
        genreSeriesCache[genreId]?.let { return it }
        genreMembers[genreId]?.let { ids ->
            // IDs without art — fall through to fetch
            if (ids.isNotEmpty() && genreSeriesCache.containsKey(genreId)) {
                return genreSeriesCache[genreId].orEmpty()
            }
        }
        val base = activeBase()
        if (base.isBlank()) return emptyList()
        val withArt = if (isMoviesMode()) {
            val label = CatalogFilters.GENRES.find { it.id == genreId }?.label ?: genreId
            val paths = listOf(
                "/search/genre/$label",
                "/search/genre/${label.lowercase(Locale.ROOT)}",
                "/genre/$genreId",
                "/movies/genre/$genreId",
            )
            var parsed = emptyList<Series>()
            for (path in paths) {
                val body = runCatching { getText("$base$path") }.getOrNull() ?: continue
                parsed = FilmParser.parseMovieList(body, base, moviesOnly = true)
                if (parsed.isNotEmpty()) break
            }
            parsed.map {
                it.copy(
                    posterUrl = SiteImages.preferJpeg(it.posterUrl),
                    backdropUrl = SiteImages.preferJpeg(it.backdropUrl ?: it.posterUrl),
                    genres = (it.genres + label).distinct(),
                )
            }
        } else {
            val url = "$base/genre/$genreId"
            val body = runCatching { getText(url) }.getOrNull() ?: return emptyList()
            val parsed = runCatching { parser.parseCatalog(body, base, null) }.getOrNull() ?: return emptyList()
            parsed.series.map {
                it.copy(
                    posterUrl = SiteImages.preferJpeg(it.posterUrl),
                    backdropUrl = SiteImages.preferJpeg(it.backdropUrl ?: it.posterUrl)
                )
            }
        }
        artResolver.putAll(withArt)
        genreMembers[genreId] = withArt.map { it.id }.toSet()
        genreSeriesCache[cacheKey] = withArt
        genreSeriesCache[genreId] = withArt
        return withArt
    }

    suspend fun getSeries(
        seriesId: String,
        enrich: Boolean = false,
        detailPathHint: String? = null,
        titleHint: String? = null,
        mediaKindHint: String? = null,
    ): Series = withContext(Dispatchers.IO) {
        val catalog = runCatching { loadCatalog(false) }.getOrNull()
        val fromCache = searchHitCache[seriesId]
        val fromFav = db.favorites().all(pid()).firstOrNull { it.seriesId == seriesId }?.let {
            seriesAdapter.fromJson(it.cachedJson)
        }
        // Prefer in-memory / favorite detail over catalog index so DE/EN page switches stick
        // (catalog rows always point at the original browse URL, usually German).
        val lightRaw = detailPathHint?.takeIf { it.isNotBlank() }?.let { path ->
            val base = fromCache ?: fromFav ?: catalog?.series?.find { it.id == seriesId }
            base?.copy(
                detailPath = path,
                mediaKind = when {
                    mediaKindHint == "movie" -> "movie"
                    mediaKindHint == "series" -> "series"
                    else -> base.mediaKind
                },
                seasons = emptyList(),
                title = titleHint?.takeIf { it.isNotBlank() }
                    ?: base.title.ifBlank { seriesId },
            ) ?: Series(
                id = seriesId,
                title = titleHint?.ifBlank { seriesId } ?: seriesId,
                detailPath = path,
                mediaKind = if (mediaKindHint == "movie") "movie" else "series",
            )
        } ?: fromCache?.takeIf { !it.detailPath.isNullOrBlank() }
            ?: fromFav?.takeIf { !it.detailPath.isNullOrBlank() }
            ?: catalog?.series?.find { it.id == seriesId }
            ?: fromCache
            ?: fromFav
            ?: throw VfException.of(VfCodes.SERIES_NOT_FOUND, "Serie nicht gefunden: $seriesId")

        // Language switch / alternate Filmpalast page must override cached detailPath.
        var light = if (!detailPathHint.isNullOrBlank() && detailPathHint != lightRaw.detailPath) {
            lightRaw.copy(
                detailPath = detailPathHint,
                mediaKind = if (mediaKindHint == "movie" || lightRaw.isMovie) "movie" else lightRaw.mediaKind,
                // Force re-fetch: drop stale seasons/episodes from other language page
                seasons = emptyList(),
            )
        } else {
            lightRaw
        }

        // Movies: if we already know language pages, open the preferred audio page.
        if ((light.isMovie || mediaKindHint == "movie") && detailPathHint.isNullOrBlank()) {
            val pref = preferredLang()
            val pages = light.languagePages.filterValues { it.isNotBlank() }
            val prefPage = pages[pref]
            if (!prefPage.isNullOrBlank() && prefPage != light.detailPath) {
                light = light.copy(detailPath = prefPage, seasons = emptyList())
            }
        }

        rememberSeriesHit(light)

        val detailed = try {
            if (light.isMovie || looksLikeMovie(light) || mediaKindHint == "movie") {
                loadMovieDetail(light)
            } else {
                loadAllSeasons(light)
            }
        } catch (t: Throwable) {
            if (t is VfException) throw t
            throw VfException.of(VfCodes.SEASONS_LOAD, "Staffeln konnten nicht geladen werden", t)
        }

        val withCachedStreams = applyStreamCache(detailed)
        val enriched = if (withCachedStreams.isMovie) {
            withCachedStreams
        } else {
            enrichSeries(withCachedStreams)
        }
        if (db.favorites().isFavorite(pid(), seriesId)) {
            persistFavoriteJson(enriched)
        }
        rememberSeriesHit(enriched)
        enriched
    }

    private fun looksLikeMovie(series: Series): Boolean {
        if (series.mediaKind == "movie") return true
        val path = series.detailPath.orEmpty()
        return StreamKind.isMovieWatchPage(path)
    }

    private suspend fun loadMovieDetail(light: Series): Series {
        var detail = light.detailPath
            ?: throw VfException.of(VfCodes.SEASONS_LOAD, "Keine Detail-URL für Film")
        var body = getText(detail)
        var parsed = FilmParser.parseMovieDetail(body, detail, light.id)
        val pref = preferredLang()
        val pageLang = FilmParser.detectPageLanguage(body, parsed.title, detail)
        // Auto-switch to preferred Filmpalast language page when available.
        if (pageLang != pref) {
            findMovieLanguagePage(detail, body, parsed.title, pref)?.let { alt ->
                if (alt != detail) {
                    val altBody = runCatching { getText(alt) }.getOrNull().orEmpty()
                    if (altBody.isNotBlank() && FilmParser.parseHosters(altBody, alt).isNotEmpty()) {
                        detail = alt
                        body = altBody
                        parsed = FilmParser.parseMovieDetail(altBody, alt, light.id)
                    }
                }
            }
        }
        val langs = runCatching {
            discoverTitleLanguages(
                parsed.copy(id = light.id, detailPath = detail, mediaKind = "movie")
            )
        }.getOrDefault(
            parsed.languagePages.ifEmpty { mapOf(pageLang to detail) }
        )
        return parsed.copy(
            id = light.id,
            title = parsed.title.ifBlank { light.title },
            posterUrl = SiteImages.preferJpeg(parsed.posterUrl ?: light.posterUrl),
            backdropUrl = SiteImages.preferJpeg(parsed.backdropUrl ?: light.backdropUrl ?: parsed.posterUrl),
            overview = pickOverview(parsed.overview, light.overview),
            detailPath = detail,
            mediaKind = "movie",
            availableLanguages = langs.keys.toList(),
            languagePages = langs,
            seasons = parsed.seasons.map { season ->
                season.copy(
                    episodes = season.episodes.map { ep ->
                        ep.copy(streamPageUrl = detail, seriesId = light.id, id = "${light.id}-movie")
                    }
                )
            },
        )
    }

    /**
     * Always expand seasons from detailPath: fetch series page + each /staffel-N page.
     * Fixes "only season 1 visible" when the index only embeds staffel 1.
     */
    private suspend fun loadAllSeasons(light: Series): Series {
        val detail = light.detailPath
        if (detail.isNullOrBlank()) {
            return if (light.seasons.isNotEmpty()) light
            else throw VfException.of(VfCodes.SEASONS_LOAD, "Keine Detail-URL für Staffeln")
        }

        val rootBody = getText(detail)
        val rootParsed = parser.parseSeriesDetail(rootBody, detail, light.id, null)
        val seasonUrls = parser.discoverSeasonUrls(rootBody, detail).toMutableList()
        val present = seasonUrls.map { it.first }.toSet()
        val maxKnown = present.maxOrNull() ?: 1
        // Prefer URL pattern discovered from HTML (often /serie/{slug}/staffel-N, not /serie/stream/...).
        val template = seasonUrls.firstOrNull { it.second.contains("staffel-", true) }?.second
            ?.replace(Regex("""(?i)staffel-\d+.*"""), "staffel-%d")
        for (n in 1..maxKnown.coerceAtLeast(1)) {
            if (n !in present) {
                val guessed = template?.format(n)
                    ?: (normalizeSeriesRoot(detail).trimEnd('/') + "/staffel-$n")
                seasonUrls.add(n to guessed)
            }
        }
        // Probe a few extra seasons beyond the highest discovered tab (sites sometimes omit later tabs).
        for (n in (maxKnown + 1)..(maxKnown + 4)) {
            if (n !in present) {
                val guessed = template?.format(n)
                    ?: (normalizeSeriesRoot(detail).trimEnd('/') + "/staffel-$n")
                seasonUrls.add(n to guessed)
            }
        }

        val merged = LinkedHashMap<Int, MutableList<Episode>>()
        val seasonArt = LinkedHashMap<Int, Pair<String?, String?>>()
        rootParsed.seasons.forEach { season ->
            merged.getOrPut(season.number) { mutableListOf() }.addAll(season.episodes)
            if (season.posterUrl != null || season.backdropUrl != null) {
                seasonArt[season.number] = season.posterUrl to season.backdropUrl
            }
        }

        coroutineScope {
            seasonUrls.distinctBy { it.first }.map { (num, url) ->
                async {
                    runCatching {
                        if (url == detail && !merged[num].isNullOrEmpty()) return@async
                        val body = getTextAllow404(url) ?: return@async
                        val parsed = parser.parseSeriesDetail(body, url, light.id, null)
                        parsed.seasons.forEach { season ->
                            val targetSeason = if (
                                parsed.seasons.size == 1 &&
                                url.contains("staffel-$num", true)
                            ) num else season.number
                            val list = merged.getOrPut(targetSeason) { mutableListOf() }
                            season.episodes.forEach { ep ->
                                val fixed = ep.copy(seasonNumber = targetSeason, seriesId = light.id)
                                if (list.none { it.number == fixed.number }) list.add(fixed)
                            }
                            val pagePoster = season.posterUrl ?: parsed.posterUrl
                            val pageBackdrop = season.backdropUrl ?: parsed.backdropUrl
                            if (pagePoster != null || pageBackdrop != null) {
                                synchronized(seasonArt) {
                                    val prev = seasonArt[targetSeason]
                                    seasonArt[targetSeason] =
                                        (prev?.first ?: pagePoster) to (prev?.second ?: pageBackdrop)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val seasons = merged.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { (n, eps) ->
                val art = seasonArt[n]
                Season(
                    number = n,
                    title = "Staffel $n",
                    posterUrl = SiteImages.preferJpeg(art?.first),
                    backdropUrl = SiteImages.preferJpeg(art?.second ?: art?.first),
                    episodes = eps.distinctBy { it.number }.sortedBy { it.number }
                        .map { it.copy(seasonNumber = n, seriesId = light.id) }
                )
            }

        return rootParsed.copy(
            id = light.id,
            title = rootParsed.title.ifBlank { light.title },
            posterUrl = SiteImages.preferJpeg(rootParsed.posterUrl ?: light.posterUrl),
            backdropUrl = SiteImages.preferJpeg(rootParsed.backdropUrl ?: light.backdropUrl ?: rootParsed.posterUrl),
            overview = pickOverview(rootParsed.overview, light.overview),
            detailPath = detail,
            seasons = seasons.ifEmpty { light.seasons }.map { season ->
                season.copy(
                    episodes = season.episodes.map { ep ->
                        // Drop stills that are just the series poster/backdrop stamped on every row.
                        // UI falls back to series art; TVMaze enrich supplies unique episode stills.
                        val jpeg = SiteImages.preferJpeg(ep.stillUrl)
                        val seriesArt = setOfNotNull(
                            SiteImages.preferJpeg(rootParsed.posterUrl ?: light.posterUrl),
                            SiteImages.preferJpeg(rootParsed.backdropUrl ?: light.backdropUrl),
                        )
                        ep.copy(stillUrl = jpeg?.takeUnless { it in seriesArt })
                    }
                )
            }
        )
    }

    private fun pickOverview(primary: String?, fallback: String?): String? {
        fun score(text: String): Int {
            var s = 0
            val lower = text.lowercase()
            if (lower.startsWith("schaue ")) s += 80
            if (lower.contains("alle episoden")) s += 30
            if (text.length < 80) s += 20
            return s
        }
        val candidates = listOfNotNull(primary, fallback).map { it.trim() }.filter { it.length > 20 }
        return candidates.minByOrNull { score(it) } ?: primary ?: fallback
    }

    /** Collapse /serie/stream/{slug} and /serie/{slug}/staffel-… to series root. */
    private fun normalizeSeriesRoot(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val path = uri.path.orEmpty()
        val root = Regex("""(/(?:serie|series)/(?:stream/)?[^/]+)""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1) ?: path
        return runCatching {
            java.net.URI(uri.scheme, uri.authority, root, null, null).toString()
        }.getOrDefault(url)
    }

    private fun getTextAllow404(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private suspend fun applyStreamCache(series: Series): Series {
        val pref = preferredLang()
        val cached = db.streams().forSeries(pid(), series.id).associateBy { it.episodeId }
        if (cached.isEmpty()) return series
        return series.copy(
            seasons = series.seasons.map { season ->
                season.copy(
                    episodes = season.episodes.map { ep ->
                        val hit = cached[ep.id] ?: return@map ep
                        if (!cacheLangMatches(hit.kind, pref)) return@map ep.copy(streamUrl = null)
                        ep.copy(streamUrl = hit.streamUrl)
                    }
                )
            }
        )
    }

    suspend fun resolveStream(episode: Episode): String = withContext(Dispatchers.IO) {
        val pref = preferredLang()
        // 1) Cached direct media for this profile language only.
        db.streams().get(pid(), episode.id)?.takeIf {
            StreamKind.isDirectMediaUrl(it.streamUrl) &&
                it.streamUrl.isNotBlank() &&
                cacheLangMatches(it.kind, pref)
        }?.let {
            return@withContext it.streamUrl
        }

        // Movie page: parse hosters → VOE / Vidara / Firestream → direct media only.
        // Never return embed/page URLs (no WebView / captcha scam).
        val pageHint = episode.streamPageUrl
        if (!pageHint.isNullOrBlank() && StreamKind.isMovieWatchPage(pageHint)) {
            resolveMovieStream(episode, pageHint)?.let { return@withContext it }
            throw VfException.of(
                VfCodes.STREAM_RESOLVE,
                "Film-Stream konnte nicht aufgelöst werden (kein direkter HLS/MP4). Kein Web-Player."
            )
        }

        // Bare episode.streamUrl (series only) — never for movies; never skip lang preference.
        episode.streamUrl?.takeIf {
            StreamKind.isDirectMediaUrl(it) &&
                !StreamKind.isMovieWatchPage(episode.streamPageUrl.orEmpty()) &&
                !episode.id.endsWith("-movie")
        }?.let {
            // Re-tag cache under current language preference.
            cacheStream(episode, it, pref)
            return@withContext it
        }

        // 2) Cached VOE → claim m3u8 now (xstream-style); cached blobs → try cookie resolve.
        db.streams().get(pid(), episode.id)?.takeIf { cacheLangMatches(it.kind, pref) }?.let { cachedRow ->
            val cached = cachedRow.streamUrl
            when {
                StreamKind.isVoePlayerUrl(cached) || StreamKind.isVoeEmbedPath(cached) -> {
                    claimVoeHls(cached, episode)?.let { return@withContext it }
                    return@withContext cached
                }
                StreamKind.isPlayBlobUrl(cached) -> {
                    claimHlsDeep(cached, episode)?.let { return@withContext it }
                    if (!episode.streamPageUrl.isNullOrBlank()) return@withContext episode.streamPageUrl!!
                    return@withContext episode.streamPageUrl ?: cached
                }
                StreamKind.isEpisodeWatchPage(cached) -> return@withContext cached
                else -> Unit
            }
        }

        // 3) Explicit streamUrl (VOE / blob / other)
        episode.streamUrl?.takeIf { it.isNotBlank() }?.let { url ->
            when {
                StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) -> {
                    claimVoeHls(url, episode)?.let { return@withContext it }
                    cacheStream(episode, url)
                    return@withContext url
                }
                StreamKind.isPlayBlobUrl(url) -> {
                    claimHlsDeep(url, episode)?.let { return@withContext it }
                    val body = runCatching { getText(url) }.getOrNull().orEmpty()
                    if (StreamKind.isIframeOnlyPlayBlobHtml(body) && !episode.streamPageUrl.isNullOrBlank()) {
                        claimHlsDeep(episode.streamPageUrl!!, episode)?.let { return@withContext it }
                        return@withContext episode.streamPageUrl!!
                    }
                    parser.extractClaimableMedia(body, url)?.let {
                        cacheStream(episode, it)
                        return@withContext it
                    }
                    return@withContext episode.streamPageUrl?.takeIf { it.isNotBlank() } ?: url
                }
                else -> {
                    claimHlsDeep(url, episode)?.let { return@withContext it }
                    val body = getText(url)
                    parser.extractClaimableMedia(body, url)?.let {
                        cacheStream(episode, it)
                        return@withContext it
                    }
                    parser.extractPlayerUrl(body, url)?.let {
                        if (StreamKind.isDirectMediaUrl(it)) {
                            cacheStream(episode, it)
                            return@withContext it
                        }
                        if (StreamKind.isVoePlayerUrl(it) || StreamKind.isVoeEmbedPath(it)) {
                            claimVoeHls(it, episode)?.let { hls -> return@withContext hls }
                            cacheStream(episode, it)
                            return@withContext it
                        }
                        if (StreamKind.isPlayBlobUrl(it) && StreamKind.isEpisodeWatchPage(url)) {
                            return@withContext url
                        }
                        cacheStream(episode, it)
                        return@withContext it
                    }
                }
            }
        }

        // 4) Episode watch page: VOE blob → m3u8 when cookies allow; else page for WebView bootstrap.
        val page = episode.streamPageUrl
            ?: throw VfException.of(VfCodes.STREAM_MISSING, "Keine Stream-URL für Episode ${episode.id}")

        if (StreamKind.isDirectMediaUrl(page)) {
            cacheStream(episode, page)
            return@withContext page
        }
        if (StreamKind.isVoePlayerUrl(page) || StreamKind.isVoeEmbedPath(page)) {
            claimVoeHls(page, episode)?.let { return@withContext it }
            cacheStream(episode, page)
            return@withContext page
        }

        // Movie page fallback (if not caught earlier)
        if (StreamKind.isMovieWatchPage(page)) {
            resolveMovieStream(episode, page)?.let { return@withContext it }
            throw VfException.of(
                VfCodes.STREAM_RESOLVE,
                "Film-Stream konnte nicht aufgelöst werden (kein direkter HLS/MP4). Kein Web-Player."
            )
        }

        claimHlsDeep(page, episode)?.let { return@withContext it }

        val body = runCatching { getText(page) }.getOrNull().orEmpty()
        parser.extractClaimableMedia(body, page)?.let {
            cacheStream(episode, it)
            return@withContext it
        }
        parser.extractPlayBlob(body, page, preferredLang = preferredLang())?.let { blob ->
            claimHlsDeep(blob, episode)?.let { return@withContext it }
        }
        if (StreamKind.isEpisodeWatchPage(page)) {
            cacheStream(episode.copy(streamUrl = page), page)
            return@withContext page
        }

        val player = parser.extractPlayerUrl(body, page)
            ?: throw VfException.of(
                VfCodes.STREAM_MISSING,
                "Kein Player-Link (m3u8 /r?t= / VOE) in Episode ${episode.id}"
            )
        if (StreamKind.isDirectMediaUrl(player)) {
            cacheStream(episode, player)
            return@withContext player
        }
        if (StreamKind.isVoePlayerUrl(player) || StreamKind.isVoeEmbedPath(player)) {
            claimVoeHls(player, episode)?.let { return@withContext it }
            cacheStream(episode, player)
            return@withContext player
        }
        if (StreamKind.isPlayBlobUrl(player)) {
            claimHlsDeep(player, episode)?.let { return@withContext it }
            return@withContext page
        }
        cacheStream(episode, player)
        player
    }

    /**
     * Filmpalast movie page: hosters → direct HLS/mp4 only. Never returns embed/WebView URLs.
     * Tries every hoster in score order; VOE encoding/geo failures fall through to Firestream etc.
     */
    private suspend fun resolveMovieStream(episode: Episode, pageUrl: String): String? {
        val pref = preferredLang()
        var effectiveUrl = pageUrl
        var body = runCatching { getText(effectiveUrl) }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        val pageTitle = FilmParser.cleanTitle(
            org.jsoup.Jsoup.parse(body, effectiveUrl)
                .selectFirst("article.detail h2, h2.bgDark, h2")?.text().orEmpty()
        )
        var pageLang = FilmParser.detectPageLanguage(body, pageTitle, effectiveUrl)

        // Filmpalast: DE and EN are separate pages — switch page when preference differs.
        if (pageLang != pref) {
            val alt = findMovieLanguagePage(effectiveUrl, body, pageTitle, pref)
            if (!alt.isNullOrBlank() && alt != effectiveUrl) {
                val altBody = runCatching { getText(alt) }.getOrNull().orEmpty()
                if (altBody.isNotBlank() && FilmParser.parseHosters(altBody, alt).isNotEmpty()) {
                    effectiveUrl = alt
                    body = altBody
                    pageLang = FilmParser.detectPageLanguage(
                        altBody,
                        FilmParser.cleanTitle(
                            org.jsoup.Jsoup.parse(altBody, alt)
                                .selectFirst("article.detail h2, h2.bgDark, h2")?.text().orEmpty()
                        ),
                        alt,
                    )
                }
            }
        }

        val hosters = FilmParser.parseHosters(body, effectiveUrl, preferredLang = pref)
        if (hosters.isEmpty()) return null

        for (hoster in hosters) {
            val url = hoster.url
            val got = runCatching {
                when {
                    StreamKind.isDirectMediaUrl(url) -> url
                    firestreamExtractor.isFirestreamUrl(url) ||
                        hoster.name.contains("firestream", true) -> {
                        firestreamExtractor.extractDirect(url, referer = effectiveUrl)
                    }
                    StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) ||
                        hoster.name.contains("voe", true) -> {
                        claimVoeHls(url, episode)
                    }
                    vidaraExtractor.isVidaraUrl(url) ||
                        hoster.name.contains("vidara", true) ||
                        hoster.name.contains("vidnest", true) -> {
                        vidaraExtractor.extractHls(url, referer = effectiveUrl)
                            ?.takeIf { StreamKind.isDirectMediaUrl(it) }
                    }
                    else -> null
                }
            }.getOrNull()
            if (!got.isNullOrBlank() && (
                    StreamKind.isDirectMediaUrl(got) ||
                        got.contains(".mp4", true) ||
                        got.contains(".m3u8", true) ||
                        got.contains("firestream", true) && got.contains("http", true)
                    )
            ) {
                if (StreamKind.isVoePlayerUrl(got) || StreamKind.isVoeEmbedPath(got)) continue
                if (got.contains("/e/", true) && !StreamKind.isDirectMediaUrl(got) &&
                    !got.contains(".mp4", true) && !got.contains("md5=", true)
                ) continue
                val lang = hoster.language.ifBlank { pageLang }
                cacheStream(episode, got, lang)
                return got
            }
        }
        return null
    }

    /**
     * Find Filmpalast page URL for [wantedLang] given the current movie page.
     * Uses sibling slug heuristics + title search.
     */
    private suspend fun findMovieLanguagePage(
        pageUrl: String,
        html: String,
        title: String,
        wantedLang: String,
    ): String? {
        val want = StreamLanguage.normalize(wantedLang)
        val current = FilmParser.detectPageLanguage(html, title, pageUrl)
        if (current == want) return pageUrl

        // 1) Sibling slug heuristics + related links embedded in the current page
        for (cand in FilmParser.siblingLanguageUrls(pageUrl, current, html)) {
            val body = runCatching { getText(cand) }.getOrNull().orEmpty()
            if (body.isBlank()) continue
            val lang = FilmParser.detectPageLanguage(
                body,
                FilmParser.cleanTitle(
                    org.jsoup.Jsoup.parse(body, cand)
                        .selectFirst("article.detail h2, h2.bgDark, h2")?.text().orEmpty()
                ),
                cand,
            )
            if (lang == want && FilmParser.parseHosters(body, cand).isNotEmpty()) return cand
        }

        // 2) Site search by cleaned title (and shorter fallbacks)
        val base = activeBase().ifBlank {
            runCatching { java.net.URI(pageUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        }
        if (base.isBlank()) return null
        val cleaned = StreamLanguage.cleanTitleForSearch(title).ifBlank { title }
        val queries = linkedSetOf(
            cleaned,
            cleaned.replace("&", " ").replace(Regex("""\s+"""), " ").trim(),
            cleaned.split(Regex("""\s+""")).take(2).joinToString(" "),
            cleaned.split(Regex("""\s+""")).firstOrNull().orEmpty(),
        ).filter { it.length >= 2 }
        for (query in queries) {
            val hits = runCatching {
                SiteSearch.search(http, base, query, USER_AGENT, mediaKind = "movie")
            }.getOrDefault(emptyList())
            for (hit in hits) {
                val hitUrl = hit.detailPath ?: continue
                val hitLang = FilmParser.languageFromMovieHit(hit.title, hitUrl)
                if (hitLang != want) continue
                // Prefer close title match
                val a = StreamLanguage.cleanTitleForSearch(hit.title).lowercase()
                val b = cleaned.lowercase()
                val close = a.contains(b.take(8)) || b.contains(a.take(8)) || a == b ||
                    a.split(Regex("""\s+""")).firstOrNull() == b.split(Regex("""\s+""")).firstOrNull()
                if (!close) continue
                val body = runCatching { getText(hitUrl) }.getOrNull().orEmpty()
                if (body.isNotBlank() && FilmParser.parseHosters(body, hitUrl).isNotEmpty()) {
                    return hitUrl
                }
            }
        }
        return null
    }

    /**
     * Discover available audio languages for a title (movies: sibling pages; series: episode hosters).
     * Returns map lang → page URL (series: episode page URL reused for all langs).
     */
    suspend fun discoverTitleLanguages(series: Series): Map<String, String> = withContext(Dispatchers.IO) {
        if (series.isMovie) {
            val page = series.detailPath ?: series.flatEpisodes().firstOrNull()?.streamPageUrl
                ?: return@withContext emptyMap()
            val body = runCatching { getText(page) }.getOrNull().orEmpty()
            if (body.isBlank()) return@withContext emptyMap()
            val title = series.title
            val current = FilmParser.detectPageLanguage(body, title, page)
            val out = linkedMapOf(current to page)
            for (want in listOf(StreamLanguage.DE, StreamLanguage.EN)) {
                if (want in out) continue
                findMovieLanguagePage(page, body, title, want)?.let { out[want] = it }
            }
            return@withContext out
        }
        // Series: probe first episode page of first season for labeled hosters
        val ep = series.flatEpisodes().firstOrNull { !it.streamPageUrl.isNullOrBlank() }
            ?: series.flatEpisodes().firstOrNull()
            ?: return@withContext emptyMap()
        val page = ep.streamPageUrl ?: series.detailPath ?: return@withContext emptyMap()
        // If page is series root, try first episode path later via resolve — still probe page
        val body = runCatching { getText(page) }.getOrNull().orEmpty()
        if (body.isBlank()) return@withContext emptyMap()
        val langs = parser.extractAvailableLanguages(body, page)
        if (langs.size >= 2) {
            return@withContext langs.associateWith { page }
        }
        if (langs.size == 1) {
            // Only one labeled language on hosters — do not invent a second from nav text.
            return@withContext langs.associateWith { page }
        }
        // Strict heading probe (avoid matching site-wide "Deutsch/Englisch" nav chrome)
        val headingBlob = org.jsoup.Jsoup.parse(body, page)
            .select("h3, h4, h5, .hosterSiteTitle, .language, [data-language-label]")
            .joinToString(" ") { it.text() + " " + it.attr("data-language-label") }
            .lowercase()
        val hasDe = headingBlob.contains("deutsch") || headingBlob.contains("german") ||
            Regex("""\bde\b""").containsMatchIn(headingBlob)
        val hasEn = headingBlob.contains("englisch") || headingBlob.contains("english") ||
            Regex("""\ben\b""").containsMatchIn(headingBlob)
        return@withContext buildMap {
            if (hasDe) put(StreamLanguage.DE, page)
            if (hasEn) put(StreamLanguage.EN, page)
        }
    }

    /** Public helper for PlayerActivity: VOE embed URL → direct HLS playlist. */
    suspend fun claimVoeToHls(voeUrl: String, episode: Episode): String? = withContext(Dispatchers.IO) {
        claimVoeHls(voeUrl, episode)
    }

    private suspend fun claimVoeHls(voeUrl: String, episode: Episode): String? {
        val hls = runCatching {
            voeExtractor.extractHls(voeUrl, referer = episode.streamPageUrl ?: activeBase())
        }.getOrNull()
        if (!hls.isNullOrBlank() && StreamKind.isDirectMediaUrl(hls)) {
            cacheStream(episode, hls)
            return hls
        }
        return null
    }

    /**
     * Pre-WebView HLS claim:
     * play-blob (+ WebView cookies) → VOE → m3u8, or episode HTML → iframe/VOE → m3u8.
     */
    private suspend fun claimHlsDeep(startUrl: String, episode: Episode, depth: Int = 0): String? {
        if (depth > 3) return null
        if (StreamKind.isDirectMediaUrl(startUrl)) {
            cacheStream(episode, startUrl)
            return startUrl
        }

        // Play-blob: try cookie-backed resolve to VOE, then extract m3u8 (skips SerienStream player UI).
        if (StreamKind.isPlayBlobUrl(startUrl)) {
            val voe = runCatching {
                voeExtractor.resolvePlayBlobToVoe(startUrl, episode.streamPageUrl)
            }.getOrNull()
            if (!voe.isNullOrBlank()) {
                claimVoeHls(voe, episode)?.let { return it }
                cacheStream(episode, voe)
                // Return VOE URL so Player can finish-claim / show briefly
                return voe
            }
            // Also try episode page mining at depth+1
            episode.streamPageUrl?.takeIf { depth == 0 }?.let { page ->
                claimHlsDeep(page, episode, depth + 1)?.let { return it }
            }
            return null
        }

        if (StreamKind.isVoePlayerUrl(startUrl) || StreamKind.isVoeEmbedPath(startUrl)) {
            return claimVoeHls(startUrl, episode)
        }

        val body = runCatching { getText(startUrl) }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        parser.extractClaimableMedia(body, startUrl)?.let {
            cacheStream(episode, it)
            return it
        }

        // Prefer VOE hoster blobs on episode pages — preferred language first, then fallback.
        val pref = preferredLang()
        val blobs = parser.extractPlayBlobCandidates(body, startUrl, preferredLang = pref)
        val preferredBlobs = blobs.filter {
            it.language.isNotBlank() && StreamLanguage.matchesPreferred(it.language, pref)
        }
        val ordered = (preferredBlobs + blobs.filterNot { it in preferredBlobs }).map { it.url }.distinct()
        ordered.take(6).forEach { blob ->
            if (blob != startUrl) claimHlsDeep(blob, episode, depth + 1)?.let { return it }
        }

        // AniWorld /redirect/{id} → follow Location to VOE embed (any proxy host)
        parser.extractRedirectUrls(body, startUrl).take(4).forEach { redirect ->
            runCatching {
                val req = Request.Builder()
                    .url(redirect)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", startUrl)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val final = resp.request.url.toString()
                    if (StreamKind.isVoePlayerUrl(final) || StreamKind.isVoeEmbedPath(final)) {
                        claimVoeHls(final, episode)?.let { return it }
                    }
                    val html = resp.body?.string().orEmpty()
                    Regex("""https?://[^\s"'<>]+/e/[A-Za-z0-9_-]+[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(html)
                        .map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
                        .firstOrNull { StreamKind.isVoePlayerUrl(it) }
                        ?.let { claimVoeHls(it, episode)?.let { hls -> return hls } }
                }
            }
        }

        parser.extractIframeSources(body, startUrl).take(4).forEach { iframe ->
            if (StreamKind.isDirectMediaUrl(iframe)) {
                cacheStream(episode, iframe)
                return iframe
            }
            if (StreamKind.isVoePlayerUrl(iframe) || StreamKind.isVoeEmbedPath(iframe)) {
                claimVoeHls(iframe, episode)?.let { return it }
            }
            runCatching {
                val req = Request.Builder()
                    .url(iframe)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", if (startUrl.endsWith("/")) startUrl else "$startUrl/")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty()
                    if (ct.contains("mpegurl", true) || ct.contains("m3u8", true)) {
                        cacheStream(episode, iframe)
                        return iframe
                    }
                    val peek = resp.peekBody(64).string()
                    if (peek.startsWith("#EXTM3U")) {
                        cacheStream(episode, iframe)
                        return iframe
                    }
                    val html = resp.body?.string().orEmpty()
                    voeExtractor.extractSourceFromHtml(html)?.let {
                        cacheStream(episode, it)
                        return it
                    }
                    // Soft redirect / any /e/ proxy in iframe HTML
                    Regex("""https?://[^\s"'<>]+/e/[A-Za-z0-9_-]+[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                        .find(html)?.value?.trimEnd(')', ']', '.', ',', '"', '\'')
                        ?.takeIf { StreamKind.isVoePlayerUrl(it) }
                        ?.let { claimVoeHls(it, episode)?.let { hls -> return hls } }
                }
            }
            if (StreamKind.isPlayBlobUrl(iframe)) {
                claimHlsDeep(iframe, episode, depth + 1)?.let { return it }
            }
        }

        // Explicit VOE URLs in HTML (host-agnostic /e/ first)
        Regex("""https?://[^\s"'<>]+/e/[A-Za-z0-9_-]+[^\s"'<>]*|https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
            .filter { StreamKind.isVoePlayerUrl(it) || StreamKind.isVoeEmbedPath(it) }
            .distinct()
            .take(5)
            .forEach { voe ->
                claimVoeHls(voe, episode)?.let { return it }
            }

        return null
    }

    /**
     * Light favorite cache: store episode page URLs for all episodes, deep-claim HLS
     * only for the latest season (avoids hanging on 90+ episode shows like Rick & Morty).
     */
    suspend fun collectAllEpisodePlayerLinks(
        seriesId: String,
        onProgress: (FavoriteCacheProgress) -> Unit = {}
    ): FavoriteCacheProgress = withContext(Dispatchers.IO) {
        val series = getSeries(seriesId, enrich = true)
        val episodes = series.flatEpisodes()
        val total = episodes.size
        var cached = 0
        updateFavoriteMeta(series = series, cached = 0, total = total, status = "caching")
        onProgress(FavoriteCacheProgress(seriesId, 0, total, "caching"))

        val latestSeason = series.seasons.maxByOrNull { it.number }?.number
        val deepTargets = episodes
            .filter { it.seasonNumber == latestSeason }
            .take(8)
            .map { it.id }
            .toSet()

        val resolvedEpisodes = episodes.map { ep ->
            val label = "S${ep.seasonNumber}E${ep.number}"
            onProgress(FavoriteCacheProgress(seriesId, cached, total, "caching", label))
            // Always keep watch-page URL so Player can claim on demand.
            val page = ep.streamPageUrl
            if (!page.isNullOrBlank()) {
                runCatching { cacheStream(ep, page) }
            }
            val url = if (ep.id in deepTargets) {
                runCatching { resolveStream(ep) }.getOrNull()
            } else {
                page ?: ep.streamUrl
            }
            if (url != null) {
                cached++
                ep.copy(streamUrl = url)
            } else ep
        }

        val bySeason = resolvedEpisodes.groupBy { it.seasonNumber }
        val hydrated = series.copy(
            seasons = series.seasons.sortedBy { it.number }.map { season ->
                season.copy(
                    episodes = (bySeason[season.number] ?: season.episodes).sortedBy { it.number }
                )
            }
        )
        val status = when {
            total == 0 -> "ready"
            cached >= total -> "ready"
            cached > 0 -> "partial"
            else -> "partial"
        }
        updateFavoriteMeta(hydrated, cached, total, status)
        FavoriteCacheProgress(seriesId, cached, total, status).also(onProgress)
    }

    suspend fun prefetchSeriesStreams(series: Series) {
        collectAllEpisodePlayerLinks(series.id)
    }

    suspend fun toggleFavorite(seriesId: String): Boolean = withContext(Dispatchers.IO) {
        if (db.favorites().isFavorite(pid(), seriesId)) {
            db.favorites().remove(pid(), seriesId)
            db.streams().deleteSeries(pid(), seriesId)
            false
        } else {
            val series = enrichSeries(getSeries(seriesId, enrich = true))
            val total = series.flatEpisodes().size
            db.favorites().upsert(
                FavoriteEntity(
                    profileId = pid(),
                    seriesId = series.id,
                    title = series.title,
                    posterUrl = series.posterUrl,
                    cachedJson = seriesAdapter.toJson(series),
                    addedAt = System.currentTimeMillis(),
                    streamsCached = 0,
                    streamsTotal = total,
                    cacheStatus = "caching"
                )
            )
            true
        }
    }

    suspend fun isFavorite(seriesId: String): Boolean = db.favorites().isFavorite(pid(), seriesId)

    suspend fun favoriteCacheState(seriesId: String): FavoriteCacheProgress? {
        val fav = db.favorites().get(pid(), seriesId) ?: return null
        return FavoriteCacheProgress(
            seriesId = seriesId,
            cached = fav.streamsCached,
            total = fav.streamsTotal,
            status = fav.cacheStatus
        )
    }

    /** Episode IDs that already have a stream URL cached for the active profile. */
    suspend fun cachedEpisodeIds(seriesId: String): Set<String> = withContext(Dispatchers.IO) {
        db.streams().forSeries(pid(), seriesId).map { it.episodeId }.toSet()
    }

    suspend fun saveProgress(
        episodeId: String,
        seriesId: String,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ) = withContext(Dispatchers.IO) {
        val completed = durationMs > 0 && positionMs >= durationMs * 0.9
        db.watch().upsert(
            WatchProgressEntity(
                profileId = pid(),
                episodeId = episodeId,
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setEpisodeWatched(episode: Episode, watched: Boolean) = withContext(Dispatchers.IO) {
        if (watched) {
            db.watch().upsert(
                WatchProgressEntity(
                    profileId = pid(),
                    episodeId = episode.id,
                    seriesId = episode.seriesId,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.number,
                    positionMs = 1L,
                    durationMs = 1L,
                    completed = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            db.watch().delete(pid(), episode.id)
        }
    }

    suspend fun setSeasonWatched(series: Series, seasonNumber: Int, watched: Boolean) =
        withContext(Dispatchers.IO) {
            val eps = series.seasons.find { it.number == seasonNumber }?.episodes.orEmpty()
            if (!watched) {
                db.watch().deleteSeason(pid(), series.id, seasonNumber)
                return@withContext
            }
            eps.forEach { ep ->
                db.watch().upsert(
                    WatchProgressEntity(
                        profileId = pid(),
                        episodeId = ep.id,
                        seriesId = series.id,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.number,
                        positionMs = 1L,
                        durationMs = 1L,
                        completed = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

    suspend fun progressForSeries(seriesId: String): Map<String, WatchProgressEntity> =
        db.watch().forSeries(pid(), seriesId).associateBy { it.episodeId }

    suspend fun getProgress(episodeId: String): WatchProgressEntity? = db.watch().get(pid(), episodeId)

    /** Next episode across seasons (S01ELast → S02E01). */
    fun nextEpisode(series: Series, current: Episode): Episode? {
        val flat = series.flatEpisodes()
        val idx = flat.indexOfFirst { it.id == current.id }
        if (idx < 0 || idx >= flat.lastIndex) return null
        return flat[idx + 1]
    }

    /** Resume unfinished episode, else first unwatched, else first episode. */
    fun continueEpisode(series: Series, progress: Map<String, WatchProgressEntity>): Episode? {
        val flat = series.flatEpisodes()
        if (flat.isEmpty()) return null
        flat.firstOrNull { ep ->
            val p = progress[ep.id]
            p != null && !p.completed && p.positionMs > 5_000
        }?.let { return it }
        return flat.firstOrNull { progress[it.id]?.completed != true } ?: flat.first()
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        memoryCatalog = null
        memoryMoviesCatalog = null
        memoryKind = null
        genreMembers.clear()
        genreSeriesCache.clear()
        artResolver.clear()
        db.streams().clear()
        cacheFile().delete()
        moviesCacheFile().delete()
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("catalog") || file.name.endsWith(".html")) {
                file.delete()
            }
        }
    }

    private fun findVoeUrl(body: String): String? =
        Regex("""https?://[^\s"'<>]*voe[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
            .firstOrNull { StreamKind.isVoePlayerUrl(it) }

    private suspend fun updateFavoriteMeta(
        series: Series,
        cached: Int,
        total: Int,
        status: String
    ) {
        persistFavoriteJson(series, cached, total, status)
    }

    private suspend fun persistFavoriteJson(
        series: Series,
        streamsCached: Int? = null,
        streamsTotal: Int? = null,
        status: String? = null
    ) {
        val existing = db.favorites().get(pid(), series.id) ?: return
        db.favorites().upsert(
            existing.copy(
                title = series.title,
                posterUrl = series.posterUrl,
                cachedJson = seriesAdapter.toJson(series),
                streamsCached = streamsCached ?: existing.streamsCached,
                streamsTotal = streamsTotal ?: existing.streamsTotal,
                cacheStatus = status ?: existing.cacheStatus
            )
        )
    }

    private suspend fun enrichSeries(series: Series): Series {
        var enriched = tvMaze.enrich(series)
        enriched = tmdb.enrich(enriched)
        return enriched
    }

    /** @deprecated use enrichSeries + persistFavoriteJson */
    private suspend fun enrichAndMaybeCache(series: Series): Series {
        val enriched = enrichSeries(series)
        if (db.favorites().get(pid(), series.id) != null) persistFavoriteJson(enriched)
        return enriched
    }

    private suspend fun loadCatalog(forceRefresh: Boolean): Catalog = mutex.withLock {
        val kind = prefs.mediaKind
        if (memoryKind != null && memoryKind != kind) {
            // Switching Serien ↔ Filme: drop in-memory genre caches.
            genreMembers.clear()
            genreSeriesCache.clear()
        }
        memoryKind = kind

        if (!forceRefresh) {
            if (kind == "movie") {
                memoryMoviesCatalog?.let { return it }
                moviesCacheFile().takeIf { it.exists() }?.readText()?.let { cached ->
                    runCatching {
                        val obj = JSONObject(cached)
                        val base = obj.optString("base").ifBlank { activeBase() }
                        Catalog(FilmParser.parseMovieList(obj.getString("body"), base, moviesOnly = true))
                    }.getOrNull()?.let {
                        memoryMoviesCatalog = it
                        return it
                    }
                }
            } else {
                memoryCatalog?.let { return it }
                cacheFile().takeIf { it.exists() }?.readText()?.let { cached ->
                    runCatching {
                        val obj = JSONObject(cached)
                        val base = obj.optString("base").ifBlank { activeBase() }
                        parser.parseCatalog(obj.getString("body"), base, obj.optString("contentType"))
                    }.getOrNull()?.let {
                        memoryCatalog = it
                        return it
                    }
                }
            }
        }

        val base = activeBase()
        if (base.isBlank()) {
            throw VfException.of(VfCodes.CATALOG_UNREACHABLE, "Keine Base-URL konfiguriert")
        }
        val catalog = if (kind == "movie") {
            fetchMoviesCatalog(base).also { memoryMoviesCatalog = it }
        } else {
            fetchCatalog(base).also { memoryCatalog = it }
        }
        catalog
    }

    private fun fetchMoviesCatalog(baseUrl: String): Catalog {
        val base = baseUrl.trimEnd('/')
        val seen = LinkedHashMap<String, Series>()
        var lastError: Throwable? = null
        for (path in FilmParser.browsePaths()) {
            try {
                val url = if (path == "/") base else "$base$path"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${resp.code} for $url")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val movies = FilmParser.parseMovieList(body, base, moviesOnly = true)
                    for (m in movies) {
                        if (!seen.containsKey(m.id)) seen[m.id] = m
                    }
                    if (movies.isNotEmpty() && path == FilmParser.browsePaths().first()) {
                        moviesCacheFile().writeText(
                            JSONObject()
                                .put("body", body)
                                .put("base", base)
                                .put("contentType", resp.header("Content-Type") ?: "")
                                .toString()
                        )
                    }
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (seen.isEmpty()) {
            throw lastError ?: IllegalStateException("Filmkatalog konnte nicht geladen werden")
        }
        return Catalog(seen.values.toList())
    }

    private fun fetchCatalog(baseUrl: String): Catalog {
        val candidates = listOf(
            "$baseUrl/catalog.json",
            "$baseUrl/api/catalog.json",
            "$baseUrl/api/catalog",
            baseUrl
        )
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${resp.code} for $url")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val contentType = resp.header("Content-Type")
                    val catalog = parser.parseCatalog(body, baseUrl, contentType)
                    if (catalog.series.isNotEmpty() || url == baseUrl) {
                        cacheFile().writeText(
                            JSONObject()
                                .put("body", body)
                                .put("base", baseUrl)
                                .put("contentType", contentType ?: "")
                                .toString()
                        )
                        return catalog
                    }
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        // Movie-site fallback when validating / loading a film base as series-like URL
        runCatching { fetchMoviesCatalog(baseUrl) }.getOrNull()?.takeIf { it.series.isNotEmpty() }?.let {
            return it
        }
        throw lastError ?: IllegalStateException("Katalog konnte nicht geladen werden")
    }

    private fun getText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} für $url")
            return resp.body?.string().orEmpty()
        }
    }

    private suspend fun cacheStream(episode: Episode, url: String, language: String? = null) {
        db.streams().upsert(
            StreamCacheEntity(
                profileId = pid(),
                episodeId = episode.id,
                seriesId = episode.seriesId,
                streamUrl = url,
                kind = cacheKind(url, language),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Drop cached stream for an episode (e.g. after DE/EN language switch). */
    suspend fun clearCachedStream(episodeId: String) = withContext(Dispatchers.IO) {
        db.streams().delete(pid(), episodeId)
    }

    suspend fun setPreferredStreamLanguage(code: String) = withContext(Dispatchers.IO) {
        val lang = StreamLanguage.normalize(code)
        prefs.setStreamLanguage(prefs.activeProfileId, lang)
    }

    fun preferredStreamLanguage(): String = preferredLang()

    /** Detect / report movie page language for UI badges. */
    suspend fun moviePageLanguage(pageUrl: String?): String? = withContext(Dispatchers.IO) {
        if (pageUrl.isNullOrBlank()) return@withContext null
        val body = runCatching { getText(pageUrl) }.getOrNull().orEmpty()
        if (body.isBlank()) return@withContext null
        FilmParser.detectPageLanguage(body)
    }

    /** Avatar candidates from current profile favorites (poster/backdrop art). */
    suspend fun favoriteAvatarOptions(): List<com.streamvault.tv.data.profile.AvatarOption> =
        withContext(Dispatchers.IO) {
            db.favorites().all(pid()).mapNotNull { fav ->
                val series = runCatching { seriesAdapter.fromJson(fav.cachedJson) }.getOrNull()
                val art = series?.backdropUrl ?: series?.posterUrl ?: fav.posterUrl
                    ?: return@mapNotNull null
                com.streamvault.tv.data.profile.AvatarOption(
                    id = "fav:${fav.seriesId}",
                    label = fav.title,
                    url = art,
                    source = com.streamvault.tv.data.profile.AvatarSource.FAVORITE
                )
            }.distinctBy { it.url }.take(24)
        }

    private fun cacheFile(): File = File(cacheDir, "catalog_cache.json")
    private fun moviesCacheFile(): File = File(cacheDir, "catalog_movies_cache.json")

    companion object {
        // Browser-like UA so HTML catalogs / series pages return full markup (not bot shells).
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
