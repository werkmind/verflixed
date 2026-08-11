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
            return@withContext rows
        }
        // Category rows (limited) from genre pages — premium “shelves” with real covers
        CatalogFilters.GENRES.take(6).forEach { genre ->
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
        val kind = prefs.mediaKind
        val favEntities = db.favorites().all(pid())
        val allFavorites = favEntities.mapNotNull { fav ->
            runCatching { seriesAdapter.fromJson(fav.cachedJson) }.getOrNull()
                ?: catalog?.series?.find { it.id == fav.seriesId }?.copy(
                    title = fav.title,
                    posterUrl = fav.posterUrl
                )
        }
        // Filter favorites by active mediaKind (mediaKind lives in cachedJson).
        val favorites = allFavorites.filter { fav ->
            val favKind = if (fav.mediaKind == "movie") "movie" else "series"
            favKind == kind
        }
        val continueIdsOrdered = db.watch().all(pid())
            .filter { !it.completed && it.positionMs > 5_000 }
            .sortedByDescending { it.updatedAt }
            .map { it.seriesId }
            .distinct()
        val continueWatching = continueIdsOrdered.mapNotNull { id ->
            favorites.find { it.id == id } ?: catalog?.series?.find { it.id == id }
        }.filter { s ->
            val sk = if (s.mediaKind == "movie") "movie" else "series"
            sk == kind
        }
        // One big library: all favorites (and continue items that aren't favs), no redundant Favoriten row
        val continueIdSet = continueWatching.map { it.id }.toSet()
        val libraryPool = (favorites + continueWatching)
            .distinctBy { it.id }
            .sortedBy { it.title.lowercase() }

        val recentlyWatched = db.watch().all(pid())
            .filter { it.completed }
            .sortedByDescending { it.updatedAt }
            .map { it.seriesId }
            .distinct()
            .mapNotNull { id -> favorites.find { it.id == id } ?: catalog?.series?.find { it.id == id } }
            .filter { s ->
                val sk = if (s.mediaKind == "movie") "movie" else "series"
                sk == kind
            }
            .filterNot { it.id in continueIdSet }
            .take(12)

        val favIds = favorites.map { it.id.lowercase() }.toSet()
        val favTitles = favorites.map { it.title.lowercase() }.toSet()
        val upcoming = if (kind == "series") {
            runCatching { calendar.favoritesUpcoming(favIds, favTitles) }.getOrDefault(emptyList())
        } else emptyList()
        val recentNew = if (kind == "series") {
            runCatching { calendar.favoritesRecent(favIds, favTitles) }.getOrDefault(emptyList())
        } else emptyList()
        val weekCalendar = if (kind == "series") {
            runCatching { calendar.weekAhead() }.getOrDefault(emptyList())
        } else emptyList()

        // Represent calendar hits as lightweight Series cards for the row UI.
        fun calendarAsSeries(entries: List<CalendarEntry>): List<Series> =
            entries.distinctBy { it.seriesId + it.date + it.episodeNumber }.take(24).map { e ->
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

        val libLabel = if (kind == "movie") "Meine Filme" else "Meine Bibliothek"
        buildList {
            // Last watched / in progress first (profile-scoped)
            if (continueWatching.isNotEmpty()) {
                add(HomeRow("Weiterschauen", continueWatching.take(8)))
            }
            if (libraryPool.isNotEmpty()) add(HomeRow(libLabel, libraryPool))
            if (upcoming.isNotEmpty()) add(HomeRow("Kalender · Demnächst", calendarAsSeries(upcoming)))
            if (recentNew.isNotEmpty()) add(HomeRow("Kalender · Neu für Favoriten", calendarAsSeries(recentNew)))
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

    private suspend fun applyGenreFilters(series: List<Series>): List<Series> {
        // Antifilter UI removed — never filter by include/exclude genres.
        return series
    }

    private suspend fun seriesIdsForGenre(genreId: String): Set<String> =
        seriesForGenre(genreId).map { it.id }.toSet()

    private suspend fun seriesForGenre(genreId: String): List<Series> {
        genreSeriesCache[genreId]?.let { return it }
        genreMembers[genreId]?.let { ids ->
            // IDs without art — fall through to fetch
            if (ids.isNotEmpty() && genreSeriesCache.containsKey(genreId)) {
                return genreSeriesCache[genreId].orEmpty()
            }
        }
        val base = activeBase()
        if (base.isBlank() || isMoviesMode()) return emptyList()
        val url = "$base/genre/$genreId"
        val body = runCatching { getText(url) }.getOrNull() ?: return emptyList()
        val parsed = runCatching { parser.parseCatalog(body, base, null) }.getOrNull() ?: return emptyList()
        val withArt = parsed.series.map {
            it.copy(
                posterUrl = SiteImages.preferJpeg(it.posterUrl),
                backdropUrl = SiteImages.preferJpeg(it.backdropUrl ?: it.posterUrl)
            )
        }
        artResolver.putAll(withArt)
        genreMembers[genreId] = withArt.map { it.id }.toSet()
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
        val light = catalog?.series?.find { it.id == seriesId }
            ?: fromFav
            ?: fromCache
            ?: detailPathHint?.takeIf { it.isNotBlank() }?.let { path ->
                Series(
                    id = seriesId,
                    title = titleHint?.ifBlank { seriesId } ?: seriesId,
                    detailPath = path,
                    mediaKind = if (mediaKindHint == "movie") "movie" else "series",
                )
            }
            ?: throw VfException.of(VfCodes.SERIES_NOT_FOUND, "Serie nicht gefunden: $seriesId")

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
        val detail = light.detailPath
            ?: throw VfException.of(VfCodes.SEASONS_LOAD, "Keine Detail-URL für Film")
        val body = getText(detail)
        val parsed = FilmParser.parseMovieDetail(body, detail, light.id)
        return parsed.copy(
            id = light.id,
            title = parsed.title.ifBlank { light.title },
            posterUrl = SiteImages.preferJpeg(parsed.posterUrl ?: light.posterUrl),
            backdropUrl = SiteImages.preferJpeg(parsed.backdropUrl ?: light.backdropUrl ?: parsed.posterUrl),
            overview = pickOverview(parsed.overview, light.overview),
            detailPath = detail,
            mediaKind = "movie",
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
        val cached = db.streams().forSeries(pid(), series.id).associateBy { it.episodeId }
        if (cached.isEmpty()) return series
        return series.copy(
            seasons = series.seasons.map { season ->
                season.copy(
                    episodes = season.episodes.map { ep ->
                        val hit = cached[ep.id] ?: return@map ep
                        ep.copy(streamUrl = hit.streamUrl)
                    }
                )
            }
        )
    }

    suspend fun resolveStream(episode: Episode): String = withContext(Dispatchers.IO) {
        // 1) Prefer already-known direct media (HLS/mp4) — never downgrade to HTML pages.
        episode.streamUrl?.takeIf { StreamKind.isDirectMediaUrl(it) }?.let {
            cacheStream(episode, it)
            return@withContext it
        }
        db.streams().get(pid(), episode.id)?.streamUrl?.takeIf { StreamKind.isDirectMediaUrl(it) }?.let {
            return@withContext it
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

        // 2) Cached VOE → claim m3u8 now (xstream-style); cached blobs → try cookie resolve.
        db.streams().get(pid(), episode.id)?.streamUrl?.let { cached ->
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
        parser.extractPlayBlob(body, page)?.let { blob ->
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
     */
    private suspend fun resolveMovieStream(episode: Episode, pageUrl: String): String? {
        val body = runCatching { getText(pageUrl) }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        val hosters = FilmParser.parseHosters(body, pageUrl)
        if (hosters.isEmpty()) return null

        for (hoster in hosters) {
            val url = hoster.url
            when {
                StreamKind.isDirectMediaUrl(url) -> {
                    cacheStream(episode, url)
                    return url
                }
                StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) ||
                    hoster.name.contains("voe", true) -> {
                    claimVoeHls(url, episode)?.let { return it }
                }
                vidaraExtractor.isVidaraUrl(url) ||
                    hoster.name.contains("vidara", true) ||
                    hoster.name.contains("vidnest", true) -> {
                    val hls = runCatching {
                        vidaraExtractor.extractHls(url, referer = pageUrl)
                    }.getOrNull()
                    if (!hls.isNullOrBlank() && StreamKind.isDirectMediaUrl(hls)) {
                        cacheStream(episode, hls)
                        return hls
                    }
                }
                firestreamExtractor.isFirestreamUrl(url) ||
                    hoster.name.contains("firestream", true) -> {
                    val direct = runCatching {
                        firestreamExtractor.extractDirect(url, referer = pageUrl)
                    }.getOrNull()
                    if (!direct.isNullOrBlank()) {
                        cacheStream(episode, direct)
                        return direct
                    }
                }
            }
        }
        // Never fall back to hoster embed / iframe / captcha pages.
        return null
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

        // Prefer VOE hoster blobs on episode pages — try several (DE first, then EN)
        parser.extractPlayBlobs(body, startUrl).take(4).forEach { blob ->
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

    private suspend fun cacheStream(episode: Episode, url: String) {
        db.streams().upsert(
            StreamCacheEntity(
                profileId = pid(),
                episodeId = episode.id,
                seriesId = episode.seriesId,
                streamUrl = url,
                kind = StreamKind.streamKindLabel(url),
                updatedAt = System.currentTimeMillis()
            )
        )
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
