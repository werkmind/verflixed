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
import com.streamvault.tv.data.db.WatchProgressEntity
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.databinding.ActivityDetailBinding
import com.streamvault.tv.ui.player.PlayerActivity
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.ui.util.PosterLoader
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private var series: Series? = null
    private var selectedSeason = 1
    private var progressMap: Map<String, WatchProgressEntity> = emptyMap()

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
        listOf(binding.btnPlay, binding.btnFavorite, binding.btnSeasonWatched).forEach {
            FocusFx.bindScale(it, 1.08f)
        }

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnSeasonWatched.setOnClickListener { toggleSeasonWatched() }
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
            if (s.isMovie) {
                if (isNotEmpty()) append("  •  ")
                append("Film")
            } else if (s.seasons.isNotEmpty()) {
                if (isNotEmpty()) append("  •  ")
                append("${s.seasons.size} Staffeln")
                val eps = s.flatEpisodes().size
                if (eps > 0) {
                    if (isNotEmpty()) append("  •  ")
                    append("$eps Episoden")
                }
            }
            val watched = progressMap.values.count { it.completed }
            if (watched > 0) {
                append("  •  $watched gesehen")
            }
        }
        binding.overview.text = s.overview ?: "Keine Beschreibung verfügbar."
        PosterLoader.loadSeries(binding.poster, s.backdropUrl ?: s.posterUrl, browseMode = false, rounded = 12)
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
        binding.btnPlay.requestFocus()
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
        PosterLoader.loadSeries(binding.poster, backdrop ?: poster, browseMode = false, rounded = 12)
        PosterLoader.loadHero(binding.backdrop, backdrop ?: poster, browseMode = false)
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
                    // Collect all episode player links on IO, publish progress on Main.
                    withContext(Dispatchers.IO) {
                        repo.collectAllEpisodePlayerLinks(s.id) { progress ->
                            launch(Dispatchers.Main) {
                                renderCacheStatus(progress.cached, progress.total, progress.status)
                                if (progress.currentEpisodeLabel != null && progress.status == "caching") {
                                    binding.cacheStatus.text =
                                        "Sammle ${progress.currentEpisodeLabel}… ${progress.cached}/${progress.total}"
                                }
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
                    if (nowFav) "Favorit gespeichert – alle Player-Links werden gesammelt" else "Favorit entfernt",
                    Toast.LENGTH_SHORT
                ).show()
                load(s.id, s.detailPath, s.title, s.mediaKind)
            }.onFailure {
                binding.btnFavorite.isEnabled = true
                Toast.makeText(this@SeriesDetailActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
            }
        }
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

    fun submit(data: List<Episode>, progressMap: Map<String, WatchProgressEntity>) {
        items.clear()
        items.addAll(data)
        progress = progressMap
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        FocusFx.bindScale(v, 1.02f)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.number.text = if (ep.id.endsWith("-movie")) "Film" else "E${ep.number}"
        holder.title.text = ep.title
        val p = progress[ep.id]
        holder.meta.text = when {
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
            p?.completed == true -> "✓ Gesehen"
            else -> "○ Markieren"
        }
        holder.badge.isFocusable = true
        holder.itemView.isFocusable = true
        holder.itemView.nextFocusRightId = R.id.watchedBadge
        holder.badge.nextFocusLeftId = holder.itemView.id
        holder.badge.setOnClickListener { onToggleWatched(ep) }
        holder.badge.setOnLongClickListener {
            onToggleWatched(ep)
            true
        }
        holder.itemView.setOnLongClickListener {
            onToggleWatched(ep)
            true
        }
        PosterLoader.loadEpisodeStill(holder.still, ep.stillUrl, seriesArtProvider())
        holder.itemView.setOnClickListener { onClick(ep) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val still: ImageView = itemView.findViewById(R.id.episodeStill)
        val number: TextView = itemView.findViewById(R.id.episodeNumber)
        val title: TextView = itemView.findViewById(R.id.episodeTitle)
        val meta: TextView = itemView.findViewById(R.id.episodeMeta)
        val badge: TextView = itemView.findViewById(R.id.watchedBadge)
    }
}
