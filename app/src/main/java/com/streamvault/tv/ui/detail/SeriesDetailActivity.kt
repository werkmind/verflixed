package com.streamvault.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object StreamLanguageLabel {
    fun fromPrefs(prefs: UserPrefs): String =
        StreamLanguage.label(prefs.streamLanguage(prefs.activeProfileId))
}
class SeriesDetailActivity : AppCompatActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.seasonTabs.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.seasonTabs.adapter = seasonAdapter
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = episodeAdapter
        listOf(binding.btnPlay, binding.btnFavorite, binding.btnSeasonWatched, binding.btnLanguage).forEach {
            FocusFx.bindScale(it, 1.08f)
        }

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnSeasonWatched.setOnClickListener { toggleSeasonWatched() }
        binding.btnLanguage.visibility = View.GONE
        binding.btnLanguage.setOnClickListener { toggleStreamLanguage() }
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
            }
            val watched = progressMap.values.count { it.completed }
            if (watched > 0) {
                append("  •  $watched gesehen")
            }
        }
        binding.overview.text = s.overview ?: "Keine Beschreibung verfügbar."
        PosterLoader.loadSeries(binding.poster, s.posterUrl ?: s.backdropUrl, browseMode = false)
        PosterLoader.loadHero(binding.backdrop, s.backdropUrl ?: s.posterUrl, browseMode = false)
        binding.btnFavorite.text = if (favorite) {
            getString(R.string.detail_favorite_remove)
        } else getString(R.string.detail_favorite_add)

        val continueEp = (application as VerflixedApp).container.catalog.continueEpisode(s, progressMap)
        binding.btnPlay.text = when {
            s.isMovie -> getString(R.string.detail_play)
            continueEp == null -> getString(R.string.detail_play)
            progressMap[continueEp.id]?.let { !it.completed && it.positionMs > 5_000 } == true ->
                "Weiter S${continueEp.seasonNumber}E${continueEp.number}"
            else -> "Play S${continueEp.seasonNumber}E${continueEp.number}"
        }

        if (s.isMovie) {
            binding.seasonTabs.visibility = View.GONE
            binding.btnSeasonWatched.visibility = View.GONE
            selectedSeason = 1
            seasonAdapter.submit(emptyList(), 1)
            // Single synthetic episode — keep list visible for play/watched toggle
            renderEpisodes()
        } else {
            binding.seasonTabs.visibility = View.VISIBLE
            val seasons = s.seasons.map { it.number }.ifEmpty { listOf(1) }
            selectedSeason = continueEp?.seasonNumber ?: seasons.first()
            seasonAdapter.submit(seasons, selectedSeason)
            applySeasonArt()
            renderEpisodes()
            updateSeasonWatchedButton()
        }
        refreshAvailableLanguages(s)
        binding.btnPlay.requestFocus()
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
            }
        }
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
                    "Ton: ${StreamLanguage.label(next)} – lade Version…",
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
        val eps = s.seasons.find { it.number == selectedSeason }?.episodes.orEmpty()
        val allWatched = eps.isNotEmpty() && eps.all { progressMap[it.id]?.completed == true }
        val repo = (application as VerflixedApp).container.catalog
        lifecycleScope.launch {
            runCatching {
                repo.setSeasonWatched(s, selectedSeason, watched = !allWatched)
                repo.progressForSeries(s.id)
            }.onSuccess { p ->
                progressMap = p
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
                renderEpisodes()
                updateSeasonWatchedButton()
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
            "caching" -> "Medien werden für dieses Profil vorbereitet… $cached/$total"
            "ready" -> "Bereit • $cached Episoden im Profil-Cache"
            "partial" -> "Teilweise bereit • $cached/$total"
            else -> if (total > 0) "Cache • $cached/$total" else ""
        }
    }

    private fun renderEpisodes() {
        val s = series ?: return
        val eps = s.seasons.find { it.number == selectedSeason }?.episodes.orEmpty()
        episodeAdapter.submit(eps, progressMap)
        updateSeasonWatchedButton()
    }

    private fun applySeasonArt() {
        val s = series ?: return
        val season = s.seasons.find { it.number == selectedSeason }
        val poster = season?.posterUrl ?: s.posterUrl
        val backdrop = season?.backdropUrl ?: season?.posterUrl ?: s.backdropUrl ?: s.posterUrl
        PosterLoader.loadSeries(binding.poster, poster ?: backdrop, browseMode = false)
        PosterLoader.loadHero(binding.backdrop, backdrop ?: poster, browseMode = false)
    }

    private fun toggleFavorite() {
        val s = series ?: return
        val repo = (application as VerflixedApp).container.catalog
        val app = application as VerflixedApp
        binding.btnFavorite.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val nowFav = repo.toggleFavorite(s.id)
                if (nowFav) {
                    renderCacheStatus(0, s.flatEpisodes().size, "caching")
                    // Background — do not block Detail UI / cancel into VF-999
                    app.appScope.launch {
                        runCatching {
                            repo.collectAllEpisodePlayerLinks(s.id) { progress ->
                                // best-effort status if still on screen
                            }
                        }
                    }
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
                    if (nowFav) "Favorit gespeichert – Links werden im Hintergrund vorbereitet"
                    else "Favorit entfernt",
                    Toast.LENGTH_SHORT
                ).show()
                load(s.id, s.detailPath, s.title, s.mediaKind)
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
        }
        options += "Staffel als gesehen"
        actions += { toggleSeasonWatched() }
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
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SERIES_ID, s.id)
                .putExtra(PlayerActivity.EXTRA_EPISODE_ID, episode.id)
        )
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
    private var focused: Episode? = null

    fun submit(data: List<Episode>, progressMap: Map<String, WatchProgressEntity>) {
        items.clear()
        items.addAll(data)
        progress = progressMap
        notifyDataSetChanged()
    }

    fun focusedEpisode(): Episode? = focused ?: items.firstOrNull()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        val holder = VH(v)
        v.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focused = holder.bound
        }
        FocusFx.bindScale(v, 1.02f)
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
        holder.meta.text = when {
            ep.upcoming || !ep.releaseLabel.isNullOrBlank() -> {
                listOfNotNull(
                    if (ep.upcoming) "DEMNÄCHST" else null,
                    ep.releaseLabel,
                    ep.overview?.takeIf { it.isNotBlank() && it != ep.releaseLabel },
                ).distinct().joinToString(" · ")
            }
            !ep.overview.isNullOrBlank() -> ep.overview
            p == null && ep.streamUrl != null -> "Bereit • Ungesehen"
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
        holder.itemView.setOnLongClickListener {
            if (!ep.upcoming) onToggleWatched(ep)
            true
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
    }
}
