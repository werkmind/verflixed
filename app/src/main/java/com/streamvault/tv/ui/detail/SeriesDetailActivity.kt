package com.streamvault.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.streamvault.tv.ui.util.ScaledAppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.catalog.StreamLanguage
import com.streamvault.tv.data.db.WatchProgressEntity
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.databinding.ActivityDetailBinding
import com.streamvault.tv.ui.player.PlayerActivity
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.ui.util.PosterLoader
import com.streamvault.tv.ui.util.TvLinearLayoutManager
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object StreamLanguageLabel {
    fun fromPrefs(prefs: UserPrefs): String =
        StreamLanguage.label(prefs.streamLanguage(prefs.activeProfileId))
}
class SeriesDetailActivity : ScaledAppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private var series: Series? = null
    private var selectedSeason = 1
    private var progressMap: Map<String, WatchProgressEntity> = emptyMap()
    /** lang → page URL; button only visible when size >= 2 */
    private var languagePages: Map<String, String> = emptyMap()
    private var activePageLang: String = StreamLanguage.DE

    private val seasonAdapter = SeasonAdapter { season ->
        selectedSeason = season
        applySeasonArt()
        renderEpisodes()
    }
    private val episodeAdapter = EpisodeAdapter(
        onClick = { ep -> play(ep) },
        onToggleWatched = { ep -> toggleEpisodeWatched(ep) },
        seriesArtProvider = { series?.backdropUrl ?: series?.posterUrl }
    )
    private var readyEpisodeIds: Set<String> = emptySet()
    private var warmingStreams = false
    private var readyDotsJob: Job? = null
    private var warmupSeriesId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val seasonLm = TvLinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.seasonTabs.layoutManager = seasonLm
        seasonLm.attachPendingFocus(binding.seasonTabs)
        binding.seasonTabs.adapter = seasonAdapter
        val episodeLm = TvLinearLayoutManager(this)
        binding.episodeList.layoutManager = episodeLm
        episodeLm.attachPendingFocus(binding.episodeList)
        binding.episodeList.adapter = episodeAdapter
        binding.episodeList.itemAnimator = null
        binding.episodeList.setHasFixedSize(true)
        binding.episodeList.isFocusable = false
        binding.episodeList.clipChildren = true
        binding.episodeList.clipToPadding = false
        binding.seasonTabs.clipChildren = true
        binding.seasonTabs.clipToPadding = false
        binding.seasonTabs.itemAnimator = null
        listOf(
            binding.btnPlay,
            binding.btnFavorite,
            binding.btnSeasonWatched,
            binding.btnLanguage,
            binding.btnMore,
        ).forEach {
            FocusFx.bindScale(it, 1.04f)
        }

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnSeasonWatched.setOnClickListener { toggleSeasonWatched() }
        binding.btnMore.setOnClickListener { showContextMenu() }
        binding.btnLanguage.visibility = View.GONE
        binding.btnLanguage.setOnClickListener { toggleStreamLanguage() }
        wireActionFocusChain()
        binding.btnPlay.setOnClickListener {
            val s = series ?: return@setOnClickListener
            val target = (application as VerflixedApp).container.catalog
                .continueEpisode(s, progressMap)
            if (target != null) play(target)
        }

        val id = intent.getStringExtra(EXTRA_SERIES_ID) ?: run {
            finish(); return
        }
        val detailPath = intent.getStringExtra(EXTRA_DETAIL_PATH)
        val titleHint = intent.getStringExtra(EXTRA_TITLE)
        val mediaKind = intent.getStringExtra(EXTRA_MEDIA_KIND)
        load(id, detailPath, titleHint, mediaKind)
    }

    private fun load(
        id: String,
        detailPath: String? = null,
        titleHint: String? = null,
        mediaKind: String? = null,
    ) {
        val repo = (application as VerflixedApp).container.catalog
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                val s = repo.getSeries(
                    id,
                    enrich = true,
                    detailPathHint = detailPath,
                    titleHint = titleHint,
                    mediaKindHint = mediaKind,
                )
                val p = repo.progressForSeries(id)
                val fav = repo.isFavorite(id)
                val cache = repo.favoriteCacheState(id)
                Quad(s, p, fav, cache)
            }.onSuccess { (s, p, fav, cache) ->
                binding.progress.visibility = View.GONE
                series = s
                progressMap = p
                bindSeries(s, fav)
                renderCacheStatus(cache?.cached ?: 0, cache?.total ?: s.flatEpisodes().size, cache?.status)
                refreshReadyDots(s.id)
                if (fav && cache?.status != "ready") {
                    startBackgroundStreamWarmup(s)
                }
            }.onFailure {
                binding.progress.visibility = View.GONE
                Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun bindSeries(s: Series, favorite: Boolean) {
        binding.title.text = s.title
        binding.meta.text = buildString {
            s.year?.let { append(it) }
            s.rating?.let {
                if (isNotEmpty()) append("  •  ")
                append("★ ${String.format(java.util.Locale.GERMAN, "%.1f", it)}")
            }
            s.runtimeMinutes?.let {
                if (isNotEmpty()) append("  •  ")
                append("$it Min.")
            }
            val langChip = s.genres.firstOrNull {
                it.equals("Deutsch", true) || it.equals("Englisch", true)
            } ?: run {
                val app = application as VerflixedApp
                StreamLanguageLabel.fromPrefs(app.container.prefs)
            }
            if (s.isMovie) {
                if (isNotEmpty()) append("  •  ")
                append(langChip)
            } else {
                if (isNotEmpty()) append("  •  ")
                append("Ton: $langChip")
                if (s.seasons.isNotEmpty()) {
                    append("  •  ")
                    append("${s.seasons.size} Staffeln")
                    val eps = s.flatEpisodes().size
                    if (eps > 0) {
                        append("  •  ")
                        append("$eps Episoden")
                    }
                }
                if (s.status.equals("Returning Series", true)) {
                    append("  •  Laufend")
                } else if (s.status.equals("Ended", true) || s.status.equals("Canceled", true)) {
                    append("  •  Beendet")
                }
            }
            val watched = progressMap.values.count { it.completed }
            if (watched > 0) {
                append("  •  $watched gesehen")
            }
        }
        binding.overview.maxLines = if (s.isMovie) 6 else 3
        binding.overview.text = s.overview ?: "Keine Beschreibung verfügbar."
        PosterLoader.loadSeries(binding.poster, s.posterUrl ?: s.backdropUrl, browseMode = false)
        PosterLoader.loadHero(binding.backdrop, s.backdropUrl ?: s.posterUrl, browseMode = false)
        binding.btnFavorite.text = if (favorite) {
            getString(R.string.detail_favorite_remove)
        } else getString(R.string.detail_favorite_add)

        val continueEp = updatePlayButtonLabel(s)

        if (s.isMovie) {
            binding.seasonTabs.visibility = View.GONE
            binding.episodeList.visibility = View.GONE
            binding.btnSeasonWatched.visibility = View.VISIBLE
            val movieEp = s.flatEpisodes().firstOrNull()
            val seen = movieEp?.let { progressMap[it.id]?.completed == true } == true
            binding.btnSeasonWatched.text = if (seen) "Als ungesehen" else "Als gesehen"
            selectedSeason = 1
            seasonAdapter.submit(emptyList(), 1)
        } else {
            binding.seasonTabs.visibility = View.VISIBLE
            binding.episodeList.visibility = View.VISIBLE
            val seasons = s.seasons.map { it.number }.ifEmpty { listOf(1) }
            selectedSeason = continueEp?.seasonNumber ?: seasons.first()
            seasonAdapter.submit(seasons, selectedSeason)
            applySeasonArt()
            renderEpisodes()
            updateSeasonWatchedButton()
        }
        refreshAvailableLanguages(s)
        wireActionFocusChain()
        if (binding.episodeList.findFocus() == null && binding.seasonTabs.findFocus() == null) {
            binding.btnPlay.requestFocus()
        }
    }

    private fun refreshAvailableLanguages(s: Series) {
        binding.btnLanguage.visibility = View.GONE
        languagePages = s.languagePages.filterValues { it.isNotBlank() }
        if (languagePages.size >= 2) {
            activePageLang = languagePages.keys.firstOrNull {
                it == StreamLanguage.normalize(
                    (application as VerflixedApp).container.prefs.streamLanguage(
                        (application as VerflixedApp).container.prefs.activeProfileId
                    )
                )
            } ?: languagePages.keys.first()
            paintLanguageButton()
            binding.btnLanguage.visibility = View.VISIBLE
            wireActionFocusChain()
        }
        lifecycleScope.launch {
            val pages = runCatching {
                (application as VerflixedApp).container.catalog.discoverTitleLanguages(s)
            }.getOrDefault(emptyMap())
            if (pages.size >= 2) {
                languagePages = pages
                val prefs = (application as VerflixedApp).container.prefs
                val pref = StreamLanguage.normalize(prefs.streamLanguage(prefs.activeProfileId))
                activePageLang = when {
                    pref in pages -> pref
                    s.detailPath != null -> pages.entries.firstOrNull { it.value == s.detailPath }?.key
                        ?: pages.keys.first()
                    else -> pages.keys.first()
                }
                paintLanguageButton()
                binding.btnLanguage.visibility = View.VISIBLE
                wireActionFocusChain()
                // Update meta chip
                series = s.copy(
                    availableLanguages = pages.keys.toList(),
                    languagePages = pages,
                    genres = (listOf(StreamLanguage.label(activePageLang)) + s.genres.filterNot {
                        it.equals("Deutsch", true) || it.equals("Englisch", true)
                    }).distinct(),
                )
            } else {
                languagePages = pages
                binding.btnLanguage.visibility = View.GONE
                wireActionFocusChain()
            }
        }
    }

    private fun wireActionFocusChain() {
        val langVisible = binding.btnLanguage.visibility == View.VISIBLE
        binding.btnSeasonWatched.nextFocusRightId =
            if (langVisible) R.id.btnLanguage else R.id.btnMore
        binding.btnMore.nextFocusLeftId =
            if (langVisible) R.id.btnLanguage else R.id.btnSeasonWatched
        val down = if (series?.isMovie == true) View.NO_ID else {
            if (binding.seasonTabs.visibility == View.VISIBLE) R.id.seasonTabs else R.id.episodeList
        }
        listOf(
            binding.btnPlay,
            binding.btnFavorite,
            binding.btnSeasonWatched,
            binding.btnLanguage,
            binding.btnMore,
        ).forEach { it.nextFocusDownId = down }
    }

    private fun paintLanguageButton() {
        binding.btnLanguage.text = StreamLanguage.shortLabel(activePageLang)
        binding.btnLanguage.contentDescription = "Ton: ${StreamLanguage.label(activePageLang)}"
    }

    private fun toggleStreamLanguage() {
        if (languagePages.size < 2) {
            binding.btnLanguage.visibility = View.GONE
            return
        }
        val app = application as VerflixedApp
        val next = languagePages.keys.firstOrNull { it != activePageLang }
            ?: StreamLanguage.toggle(activePageLang)
        val nextPage = languagePages[next]
        lifecycleScope.launch {
            app.container.catalog.setPreferredStreamLanguage(next)
            series?.flatEpisodes()?.forEach { ep ->
                runCatching { app.container.catalog.clearCachedStream(ep.id) }
            }
            activePageLang = next
            paintLanguageButton()
            val s = series
            if (s?.isMovie == true && !nextPage.isNullOrBlank()) {
                Toast.makeText(
                    this@SeriesDetailActivity,
                    "Ton: ${StreamLanguage.label(next)} - lade Version…",
                    Toast.LENGTH_SHORT
                ).show()
                // Reload movie from the other Filmpalast page
                load(s.id, nextPage, s.title, "movie")
            } else {
                Toast.makeText(
                    this@SeriesDetailActivity,
                    "Ton: ${StreamLanguage.label(next)}",
                    Toast.LENGTH_SHORT
                ).show()
                s?.let { bindSeries(it, binding.btnFavorite.text.contains("entfernen", true)) }
            }
        }
    }

    /** Re-derives „Weiter SxEy“ from current progress; safe to call after any watched-toggle. */
    private fun updatePlayButtonLabel(s: Series): Episode? {
        val continueEp = (application as VerflixedApp).container.catalog.continueEpisode(s, progressMap)
        binding.btnPlay.text = when {
            s.isMovie -> getString(R.string.detail_play)
            continueEp == null -> getString(R.string.detail_play)
            progressMap[continueEp.id]?.let { !it.completed && it.positionMs > 5_000 } == true ->
                "Weiter S${continueEp.seasonNumber}E${continueEp.number}"
            else -> "Play S${continueEp.seasonNumber}E${continueEp.number}"
        }
        return continueEp
    }

    private fun updateSeasonWatchedButton() {
        val s = series ?: return
        val eps = s.seasons.find { it.number == selectedSeason }?.episodes.orEmpty()
        if (eps.isEmpty()) {
            binding.btnSeasonWatched.visibility = View.GONE
            return
        }
        binding.btnSeasonWatched.visibility = View.VISIBLE
        val allWatched = eps.all { progressMap[it.id]?.completed == true }
        binding.btnSeasonWatched.text = if (allWatched) {
            getString(R.string.detail_mark_season_unwatched)
        } else getString(R.string.detail_mark_season_watched)
    }

    private fun toggleSeasonWatched() {
        val s = series ?: return
        if (s.isMovie) {
            val ep = s.flatEpisodes().firstOrNull() ?: return
            toggleEpisodeWatched(ep)
            return
        }
        val eps = s.seasons.find { it.number == selectedSeason }?.episodes.orEmpty()
        val allWatched = eps.isNotEmpty() && eps.all { progressMap[it.id]?.completed == true }
        val repo = (application as VerflixedApp).container.catalog
        lifecycleScope.launch {
            runCatching {
                repo.setSeasonWatched(s, selectedSeason, watched = !allWatched)
                repo.progressForSeries(s.id)
            }.onSuccess { p ->
                progressMap = p
                series?.let { updatePlayButtonLabel(it) }
                renderEpisodes()
                updateSeasonWatchedButton()
                Toast.makeText(
                    this@SeriesDetailActivity,
                    if (!allWatched) "Staffel $selectedSeason als gesehen markiert"
                    else "Staffel $selectedSeason zurückgesetzt",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleEpisodeWatched(ep: Episode) {
        val repo = (application as VerflixedApp).container.catalog
        val watched = progressMap[ep.id]?.completed == true
        lifecycleScope.launch {
            runCatching {
                repo.setEpisodeWatched(ep, watched = !watched)
                repo.progressForSeries(ep.seriesId)
            }.onSuccess { p ->
                progressMap = p
                series?.let { updatePlayButtonLabel(it) }
                if (series?.isMovie == true) {
                    val seen = p[ep.id]?.completed == true
                    binding.btnSeasonWatched.text = if (seen) "Als ungesehen" else "Als gesehen"
                } else {
                    renderEpisodes()
                    updateSeasonWatchedButton()
                }
            }.onFailure {
                Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderCacheStatus(cached: Int, total: Int, status: String?) {
        if (status.isNullOrBlank() && cached <= 0) {
            binding.cacheStatus.visibility = View.GONE
            return
        }
        binding.cacheStatus.visibility = View.VISIBLE
        binding.cacheStatus.text = when (status) {
            "caching" -> "◌  Wird vorbereitet · $cached/$total"
            "ready" -> "●  Offline bereit · $cached Episoden"
            "partial" -> "◐  Teilweise bereit · $cached/$total"
            else -> if (total > 0) "◌  Cache · $cached/$total" else ""
        }
    }

    private fun renderEpisodes() {
        val s = series ?: return
        val eps = s.seasons.find { it.number == selectedSeason }?.episodes.orEmpty()
        episodeAdapter.submit(eps, progressMap, readyEpisodeIds)
        updateSeasonWatchedButton()
    }

    private fun refreshReadyDots(seriesId: String) {
        if (series?.id != seriesId) return
        readyDotsJob?.cancel()
        readyDotsJob = lifecycleScope.launch {
            delay(250) // coalesce rapid warmup callbacks
            if (series?.id != seriesId || isFinishing || isDestroyed) return@launch
            val repo = (application as VerflixedApp).container.catalog
            val ids = withContext(Dispatchers.IO) {
                runCatching { repo.cachedEpisodeIds(seriesId) }.getOrDefault(emptySet())
            }
            if (series?.id != seriesId) return@launch
            var merged = ids
            series?.flatEpisodes()?.forEach { ep ->
                if (!ep.streamUrl.isNullOrBlank()) merged = merged + ep.id
            }
            readyEpisodeIds = merged
            if (series?.isMovie != true) {
                episodeAdapter.updateReady(merged)
            }
        }
    }

    private fun startBackgroundStreamWarmup(s: Series, forceRefresh: Boolean = false) {
        if (warmingStreams && warmupSeriesId == s.id && !forceRefresh) return
        if (s.flatEpisodes().isEmpty()) return
        warmingStreams = true
        warmupSeriesId = s.id
        val repo = (application as VerflixedApp).container.catalog
        val app = application as VerflixedApp
        renderCacheStatus(readyEpisodeIds.size, s.flatEpisodes().size, "caching")
        app.appScope.launch {
            var lastCached = -1
            runCatching {
                repo.collectAllEpisodePlayerLinks(
                    seriesId = s.id,
                    clearExisting = forceRefresh,
                ) { progress ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (series?.id != s.id) return@runOnUiThread
                        renderCacheStatus(progress.cached, progress.total, progress.status)
                        if (progress.cached != lastCached) {
                            lastCached = progress.cached
                            refreshReadyDots(s.id)
                        }
                    }
                }
            }.onSuccess {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (series?.id != s.id) return@runOnUiThread
                    warmingStreams = false
                    refreshReadyDots(s.id)
                }
            }.onFailure {
                if (warmupSeriesId == s.id) warmingStreams = false
            }
        }
    }

    private fun applySeasonArt() {
        val s = series ?: return
        val season = s.seasons.find { it.number == selectedSeason }
        val poster = season?.posterUrl ?: s.posterUrl
        val backdrop = season?.backdropUrl ?: season?.posterUrl ?: s.backdropUrl ?: s.posterUrl
        FocusFx.crossfade(binding.poster) {
            PosterLoader.loadSeries(binding.poster, poster ?: backdrop, browseMode = false)
        }
        FocusFx.crossfade(binding.backdrop) {
            PosterLoader.loadHero(binding.backdrop, backdrop ?: poster, browseMode = false)
        }
    }

    private fun toggleFavorite() {
        val s = series ?: return
        val repo = (application as VerflixedApp).container.catalog
        binding.btnFavorite.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val nowFav = repo.toggleFavorite(s.id)
                if (nowFav) {
                    renderCacheStatus(0, s.flatEpisodes().size, "caching")
                    warmingStreams = false
                    startBackgroundStreamWarmup(s)
                } else {
                    readyEpisodeIds = emptySet()
                    if (!s.isMovie) episodeAdapter.updateReady(emptySet())
                }
                nowFav
            }.onSuccess { nowFav ->
                binding.btnFavorite.isEnabled = true
                binding.btnFavorite.text = if (nowFav) {
                    getString(R.string.detail_favorite_remove)
                } else {
                    binding.cacheStatus.visibility = View.GONE
                    getString(R.string.detail_favorite_add)
                }
                Toast.makeText(
                    this@SeriesDetailActivity,
                    if (nowFav) "Favorit gespeichert - alle Staffeln werden im Hintergrund gecacht"
                    else "Favorit entfernt - Stream-Cache gelöscht",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                binding.btnFavorite.isEnabled = true
                val msg = it.toVfMessage()
                if (msg.isNotBlank()) {
                    Toast.makeText(this@SeriesDetailActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
            keyCode == android.view.KeyEvent.KEYCODE_INFO ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y
        ) {
            showContextMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showContextMenu() {
        val s = series ?: return
        val focusedEp = episodeAdapter.focusedEpisode()
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val repo = (application as VerflixedApp).container.catalog

        options += if (binding.btnFavorite.text.toString().contains("entfernen", true)) {
            "Aus Favoriten entfernen"
        } else {
            "Zu Favoriten hinzufügen"
        }
        actions += { toggleFavorite() }

        if (focusedEp != null) {
            options += "Als gesehen markieren"
            actions += { toggleEpisodeWatched(focusedEp) }
            options += "Noch nicht gesehen"
            actions += {
                lifecycleScope.launch {
                    runCatching { repo.setEpisodeWatched(focusedEp, false) }
                    load(s.id, s.detailPath, s.title, s.mediaKind)
                }
            }
            options += "Stream-Link Episode neu laden"
            actions += {
                lifecycleScope.launch {
                    Toast.makeText(this@SeriesDetailActivity, "Lade Stream…", Toast.LENGTH_SHORT).show()
                    runCatching { repo.refreshEpisodeStream(focusedEp.id, s.id) }
                        .onSuccess { ok ->
                            Toast.makeText(
                                this@SeriesDetailActivity,
                                if (ok) "Episode-Stream aktualisiert" else "Kein Direkt-Stream gefunden",
                                Toast.LENGTH_SHORT,
                            ).show()
                            refreshReadyDots(s.id)
                        }
                        .onFailure {
                            Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
        if (!s.isMovie) {
            options += "Staffel als gesehen"
            actions += { toggleSeasonWatched() }
            options += "Zufällige Folge abspielen"
            actions += {
                val pool = s.flatEpisodes().filter { !it.upcoming }
                pool.randomOrNull()?.let { ep ->
                    Toast.makeText(
                        this,
                        "Zufall: S${ep.seasonNumber}E${ep.number}",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchPlayer(ep, startMs = 0L)
                }
            }
            options += "Aus „Weiterschauen“ entfernen"
            actions += {
                lifecycleScope.launch {
                    runCatching { repo.removeFromContinueWatching(s.id) }
                    progressMap = runCatching { repo.progressForSeries(s.id) }.getOrDefault(progressMap)
                    updatePlayButtonLabel(s)
                    renderEpisodes()
                    Toast.makeText(this@SeriesDetailActivity, "Aus Weiterschauen entfernt", Toast.LENGTH_SHORT).show()
                }
            }
        }
        options += "Stream-Cache dieser Serie leeren"
        actions += {
            lifecycleScope.launch {
                runCatching { repo.clearSeriesStreamCache(s.id) }
                readyEpisodeIds = emptySet()
                if (!s.isMovie) episodeAdapter.updateReady(emptySet())
                renderCacheStatus(0, s.flatEpisodes().size, "idle")
                Toast.makeText(this@SeriesDetailActivity, "Stream-Cache geleert", Toast.LENGTH_SHORT).show()
            }
        }
        if (!s.isMovie) {
            options += "Stream-Links Staffel neu laden"
            actions += {
                renderCacheStatus(0, s.flatEpisodes().count { it.seasonNumber == selectedSeason }, "caching")
                warmingStreams = false
                Toast.makeText(this@SeriesDetailActivity, "Staffel-Streams werden geladen…", Toast.LENGTH_SHORT).show()
                val app = application as VerflixedApp
                app.appScope.launch {
                    runCatching {
                        repo.collectAllEpisodePlayerLinks(
                            seriesId = s.id,
                            seasonNumber = selectedSeason,
                            clearExisting = true,
                        ) { progress ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (series?.id != s.id) return@runOnUiThread
                                renderCacheStatus(progress.cached, progress.total, progress.status)
                            }
                        }
                    }.onSuccess {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            refreshReadyDots(s.id)
                            Toast.makeText(this@SeriesDetailActivity, "Staffel-Streams fertig", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        runOnUiThread {
                            Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        options += "Stream-Links Serie neu laden (HLS/MP4)"
        actions += {
            warmingStreams = false
            startBackgroundStreamWarmup(s, forceRefresh = true)
            Toast.makeText(this@SeriesDetailActivity, "Alle Streams werden neu geladen…", Toast.LENGTH_SHORT).show()
        }
        options += "Metadaten neu laden"
        actions += {
            lifecycleScope.launch {
                binding.progress.visibility = View.VISIBLE
                runCatching {
                    repo.getSeries(
                        s.id,
                        enrich = true,
                        detailPathHint = s.detailPath,
                        titleHint = s.title,
                        mediaKindHint = s.mediaKind,
                    )
                }.onSuccess {
                    load(s.id, s.detailPath, s.title, s.mediaKind)
                }.onFailure {
                    binding.progress.visibility = View.GONE
                    val msg = it.toVfMessage()
                    if (msg.isNotBlank()) Toast.makeText(this@SeriesDetailActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun play(episode: Episode) {
        val s = series ?: return
        val progress = progressMap[episode.id]
        val pos = progress?.takeIf { !it.completed }?.positionMs ?: 0L
        if (pos > 5_000L) {
            val label = formatResumeTime(pos)
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.resume_title)
                .setMessage(getString(R.string.resume_message, label))
                .setPositiveButton(R.string.resume_continue) { _, _ ->
                    launchPlayer(episode, startMs = pos)
                }
                .setNeutralButton(R.string.resume_restart) { _, _ ->
                    lifecycleScope.launch {
                        runCatching {
                            (application as VerflixedApp).container.catalog.saveProgress(
                                episodeId = episode.id,
                                seriesId = episode.seriesId,
                                positionMs = 0L,
                                durationMs = progress?.durationMs ?: 0L,
                                seasonNumber = episode.seasonNumber,
                                episodeNumber = episode.number,
                            )
                        }
                        launchPlayer(episode, startMs = 0L)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            launchPlayer(episode, startMs = 0L)
        }
    }

    private fun launchPlayer(episode: Episode, startMs: Long) {
        val s = series ?: return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SERIES_ID, s.id)
                .putExtra(PlayerActivity.EXTRA_EPISODE_ID, episode.id)
                .putExtra(PlayerActivity.EXTRA_DETAIL_PATH, episode.streamPageUrl ?: s.detailPath)
                .putExtra(PlayerActivity.EXTRA_MEDIA_KIND, s.mediaKind)
                .putExtra(PlayerActivity.EXTRA_TITLE, s.title)
                .putExtra(PlayerActivity.EXTRA_START_POSITION_MS, startMs)
        )
    }

    private fun formatResumeTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) {
            getString(R.string.resume_time_hours, h.toInt(), m.toInt(), s.toInt())
        } else {
            getString(R.string.resume_time_minutes, m.toInt(), s.toInt())
        }
    }

    override fun onDestroy() {
        readyDotsJob?.cancel()
        warmingStreams = false
        warmupSeriesId = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_DETAIL_PATH = "detail_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MEDIA_KIND = "media_kind"
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private class SeasonAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.VH>() {
    private val items = mutableListOf<Int>()
    private var selected = 1

    fun submit(data: List<Int>, selectedSeason: Int) {
        items.clear()
        items.addAll(data)
        selected = selectedSeason
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
        FocusFx.bindScale(v, 1.04f)
        v.nextFocusDownId = R.id.episodeList
        return VH(v as TextView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = items[position]
        holder.label.text = "Staffel $season"
        holder.label.isSelected = season == selected
        holder.label.setOnClickListener {
            selected = season
            notifyDataSetChanged()
            onClick(season)
        }
    }

    class VH(val label: TextView) : RecyclerView.ViewHolder(label)
}

private class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
    private val onToggleWatched: (Episode) -> Unit,
    private val seriesArtProvider: () -> String?
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    private val items = mutableListOf<Episode>()
    private var progress: Map<String, WatchProgressEntity> = emptyMap()
    private var readyIds: Set<String> = emptySet()
    private var focused: Episode? = null

    fun submit(
        data: List<Episode>,
        progressMap: Map<String, WatchProgressEntity>,
        ready: Set<String> = emptySet(),
    ) {
        val sameIds = items.size == data.size && items.indices.all { items[it].id == data[it].id }
        items.clear()
        items.addAll(data)
        progress = progressMap
        readyIds = ready
        if (sameIds) notifyItemRangeChanged(0, items.size, "meta")
        else notifyDataSetChanged()
    }

    fun updateReady(ready: Set<String>) {
        if (readyIds == ready) return
        readyIds = ready
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, "meta")
    }

    fun focusedEpisode(): Episode? = focused ?: items.firstOrNull()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        val holder = VH(v)
        v.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focused = holder.bound
        }
        FocusFx.bindScale(v, 1.015f)
        return holder
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.bound = ep
        holder.number.text = if (ep.id.endsWith("-movie")) "" else "E${ep.number}"
        holder.number.visibility = if (ep.id.endsWith("-movie")) View.GONE else View.VISIBLE
        holder.title.text = ep.title
        val p = progress[ep.id]
        val ready = ep.id in readyIds || !ep.streamUrl.isNullOrBlank()
        val airDateLabel = ep.airDate?.let { iso ->
            Regex("""(\d{4})-(\d{2})-(\d{2})""").find(iso)?.destructured
                ?.let { (y, m, d) -> "$d.$m.$y" }
        }
        holder.meta.text = when {
            ep.upcoming || !ep.releaseLabel.isNullOrBlank() -> {
                listOfNotNull(
                    if (ep.upcoming) "DEMNÄCHST" else null,
                    ep.releaseLabel ?: airDateLabel,
                    ep.overview?.takeIf { it.isNotBlank() && it != ep.releaseLabel },
                ).distinct().joinToString(" · ")
            }
            !ep.overview.isNullOrBlank() -> listOfNotNull(airDateLabel, ep.overview)
                .joinToString(" · ")
            p == null && ready -> "Bereit • Ungesehen"
            p == null -> "Ungesehen"
            p.completed -> "Gesehen"
            else -> {
                val pct = (p.positionMs * 100 / p.durationMs.coerceAtLeast(1)).toInt()
                "Weiter bei $pct%"
            }
        }
        holder.badge.text = when {
            ep.upcoming -> "DEMNÄCHST"
            p?.completed == true -> "✓ Gesehen"
            ep.id.endsWith("-movie") -> "○ Ungesehen"
            else -> "○ Ungesehen"
        }
        holder.badge.isFocusable = false
        holder.badge.isClickable = false
        holder.badge.setOnClickListener(null)
        // Tiny ready indicator (green = cached, dim = pending, gone for upcoming)
        val dot = holder.readyDot
        if (dot != null) {
            if (ep.upcoming) {
                dot.visibility = View.GONE
            } else {
                dot.visibility = View.VISIBLE
                dot.setBackgroundResource(
                    if (ready) R.drawable.bg_stream_dot_ready else R.drawable.bg_stream_dot
                )
                dot.alpha = if (ready) 1f else 0.45f
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!ep.upcoming) onToggleWatched(ep)
            true
        }
        val frac = if (p != null && !p.completed && p.durationMs > 0) {
            (p.positionMs.toFloat() / p.durationMs).coerceIn(0.04f, 0.98f)
        } else null
        val bar = holder.progressBar
        val track = holder.progressTrack
        if (bar != null && track != null) {
            if (frac != null) {
                track.visibility = View.VISIBLE
                bar.visibility = View.VISIBLE
                bar.post {
                    val w = (track.width * frac).toInt().coerceAtLeast(8)
                    bar.layoutParams = bar.layoutParams.apply { width = w }
                }
            } else {
                track.visibility = View.GONE
                bar.visibility = View.GONE
            }
        }
        PosterLoader.loadEpisodeStill(holder.still, ep.stillUrl, seriesArtProvider())
        holder.itemView.alpha = if (ep.upcoming) 0.78f else 1f
        holder.itemView.isEnabled = true
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        if (ep.upcoming) {
            holder.itemView.setOnClickListener {
                android.widget.Toast.makeText(
                    holder.itemView.context,
                    ep.releaseLabel?.let { "Erscheint $it" } ?: "Episode erscheint demnächst",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            holder.itemView.setOnClickListener { onClick(ep) }
        }
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var bound: Episode? = null
        val still: ImageView = itemView.findViewById(R.id.episodeStill)
        val number: TextView = itemView.findViewById(R.id.episodeNumber)
        val title: TextView = itemView.findViewById(R.id.episodeTitle)
        val meta: TextView = itemView.findViewById(R.id.episodeMeta)
        val badge: TextView = itemView.findViewById(R.id.watchedBadge)
        val readyDot: View? = itemView.findViewById(R.id.streamReadyDot)
        val progressBar: View? = itemView.findViewById(R.id.episodeProgressBar)
        val progressTrack: View? = itemView.findViewById(R.id.episodeProgressTrack)
    }
}
