package com.streamvault.tv.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.model.GenreChip
import com.streamvault.tv.data.model.HomeRow
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.databinding.ActivityHomeBinding
import com.streamvault.tv.ui.detail.SeriesDetailActivity
import com.streamvault.tv.ui.profile.ProfilesActivity
import com.streamvault.tv.ui.settings.SettingsActivity
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.ui.util.PosterLoader
import com.streamvault.tv.ui.util.UiSound
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HomeMode { LIBRARY, BROWSE, SEARCH }

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private var heroSeries: Series? = null
    private var mode = HomeMode.LIBRARY
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var searchQuery = ""
    private lateinit var searchPanel: View
    private lateinit var searchQueryLabel: TextView
    private lateinit var searchKeyboard: RecyclerView
    private lateinit var searchResultsList: RecyclerView
    private val prefs by lazy { (application as VerflixedApp).container.prefs }

    private val rowsAdapter = RowsAdapter(
        onClick = {
            UiSound.click(this, prefs)
            openSeries(it)
        },
        onFocused = { updateHero(it) },
        onHeroPlay = {
            UiSound.click(this, prefs)
            heroSeries?.let { openSeries(it) }
        },
        onHeroInfo = {
            UiSound.click(this, prefs)
            heroSeries?.let { openSeries(it) }
        },
        prefsProvider = { prefs },
        browseModeProvider = { mode == HomeMode.BROWSE || mode == HomeMode.SEARCH },
        resolveArt = { series, onResolved ->
            // Browse/Search: lazy site covers only — never enrich/TVMaze/Room
            if (mode == HomeMode.LIBRARY) return@RowsAdapter
            if (!series.posterUrl.isNullOrBlank() || !series.backdropUrl.isNullOrBlank()) return@RowsAdapter
            lifecycleScope.launch {
                runCatching {
                    (application as VerflixedApp).container.catalog.resolveBrowseArt(series)
                }.onSuccess { resolved ->
                    if (!resolved.posterUrl.isNullOrBlank() || !resolved.backdropUrl.isNullOrBlank()) {
                        onResolved(resolved)
                        if (heroSeries?.id == resolved.id) updateHero(resolved)
                    }
                }
            }
        }
    )

    private val searchResultsAdapter = RowsAdapter(
        onClick = {
            UiSound.click(this, prefs)
            openSeries(it)
        },
        onFocused = { updateHero(it) },
        onHeroPlay = {
            UiSound.click(this, prefs)
            heroSeries?.let { openSeries(it) }
        },
        onHeroInfo = {
            UiSound.click(this, prefs)
            heroSeries?.let { openSeries(it) }
        },
        prefsProvider = { prefs },
        browseModeProvider = { true },
        resolveArt = { series, onResolved ->
            if (!series.posterUrl.isNullOrBlank() || !series.backdropUrl.isNullOrBlank()) return@RowsAdapter
            lifecycleScope.launch {
                runCatching {
                    (application as VerflixedApp).container.catalog.resolveBrowseArt(series)
                }.onSuccess { resolved ->
                    if (!resolved.posterUrl.isNullOrBlank() || !resolved.backdropUrl.isNullOrBlank()) {
                        onResolved(resolved)
                        if (heroSeries?.id == resolved.id) updateHero(resolved)
                    }
                }
            }
        }
    )

    private val chipAdapter = ChipAdapter { chip ->
        toggleChip(chip)
    }

    private val searchKeyAdapter = SearchKeyAdapter { key ->
        onSearchKey(key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        searchPanel = findViewById(R.id.searchPanel)
        searchQueryLabel = findViewById(R.id.searchQueryLabel)
        searchKeyboard = findViewById(R.id.searchKeyboard)
        searchResultsList = findViewById(R.id.searchResults)

        binding.rows.layoutManager = LinearLayoutManager(this)
        binding.rows.adapter = rowsAdapter
        binding.rows.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 180
            removeDuration = 140
            changeDuration = 120
        }
        binding.rows.isNestedScrollingEnabled = true
        binding.rows.setHasFixedSize(false)
        // Keep focused row visible for D-pad TV navigation
        binding.rows.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = Unit
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })

        searchKeyboard.layoutManager = GridLayoutManager(this, 6)
        searchKeyboard.adapter = searchKeyAdapter
        searchKeyboard.itemAnimator = null
        searchResultsList.layoutManager = LinearLayoutManager(this)
        searchResultsList.adapter = searchResultsAdapter
        searchResultsList.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 180
            removeDuration = 140
            changeDuration = 120
        }
        updateSearchQueryLabel()

        binding.profileNameLabel.isFocusable = true
        binding.profileNameLabel.isFocusableInTouchMode = true
        binding.profileNameLabel.setOnClickListener { openProfiles() }
        binding.profileNameLabel.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1f else 0.85f
        }
        // Explicit Fire TV focus chain: avatar → tabs → kind → profile → update → refresh → settings
        binding.profileAvatar.nextFocusRightId = R.id.tabLibrary
        binding.tabLibrary.nextFocusLeftId = R.id.profileAvatar
        binding.tabLibrary.nextFocusRightId = R.id.tabBrowse
        binding.tabBrowse.nextFocusLeftId = R.id.tabLibrary
        binding.tabBrowse.nextFocusRightId = R.id.tabSearch
        binding.tabSearch.nextFocusLeftId = R.id.tabBrowse
        binding.tabSearch.nextFocusRightId = R.id.btnKindSeries
        binding.btnKindSeries.nextFocusLeftId = R.id.tabSearch
        binding.btnKindSeries.nextFocusRightId = R.id.btnKindMovies
        binding.btnKindMovies.nextFocusLeftId = R.id.btnKindSeries
        binding.btnKindMovies.nextFocusRightId = R.id.btnProfile
        binding.btnProfile.nextFocusLeftId = R.id.btnKindMovies
        binding.btnProfile.nextFocusRightId = R.id.btnUpdate
        binding.btnUpdate.nextFocusLeftId = R.id.btnProfile
        binding.btnUpdate.nextFocusRightId = R.id.btnRefresh
        binding.btnRefresh.nextFocusLeftId = R.id.btnUpdate
        binding.btnRefresh.nextFocusRightId = R.id.btnSettings
        binding.btnSettings.nextFocusLeftId = R.id.btnRefresh
        binding.tabLibrary.nextFocusDownId = R.id.rows
        binding.tabBrowse.nextFocusDownId = R.id.rows
        binding.tabSearch.nextFocusDownId = R.id.rows
        binding.btnKindSeries.nextFocusDownId = R.id.rows
        binding.btnKindMovies.nextFocusDownId = R.id.rows
        binding.btnProfile.nextFocusDownId = R.id.rows
        binding.btnUpdate.nextFocusDownId = R.id.rows
        binding.btnRefresh.nextFocusDownId = R.id.rows
        binding.btnSettings.nextFocusDownId = R.id.rows
        binding.profileAvatar.nextFocusDownId = R.id.rows
        binding.rows.nextFocusUpId = R.id.tabBrowse

        // HSV must not steal DPAD focus from nav buttons
        binding.navScroll.isFocusable = false
        binding.navScroll.isFocusableInTouchMode = false
        binding.navScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        listOf(
            binding.btnUpdate, binding.btnRefresh, binding.btnSettings, binding.btnProfile,
        ).forEach { btn ->
            btn.isClickable = true
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            btn.setOnKeyListener { v, keyCode, event ->
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

        binding.filterChips.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.filterChips.adapter = chipAdapter
        binding.filterChips.visibility = View.GONE

        listOf(
            binding.tabLibrary, binding.tabBrowse, binding.tabSearch,
            binding.btnKindSeries, binding.btnKindMovies,
            binding.btnProfile, binding.btnSettings, binding.btnRefresh, binding.btnUpdate,
            binding.profileAvatar,
        ).forEach { FocusFx.bindScale(it, 1.08f, prefs) }

        binding.btnSettings.setOnClickListener {
            UiSound.click(this, prefs)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnProfile.setOnClickListener { openProfiles() }
        binding.profileAvatar.setOnClickListener { openProfiles() }
        binding.profileNameLabel.setOnClickListener { openProfiles() }
        binding.btnRefresh.setOnClickListener {
            UiSound.click(this, prefs)
            lifecycleScope.launch {
                if (mode == HomeMode.BROWSE) {
                    (application as VerflixedApp).container.catalog.resetBrowsePage()
                }
                load(force = true)
            }
        }
        binding.btnLoadMore.setOnClickListener {
            UiSound.click(this, prefs)
            lifecycleScope.launch {
                binding.progress.visibility = View.VISIBLE
                runCatching { (application as VerflixedApp).container.catalog.loadMoreBrowse() }
                    .onSuccess {
                        binding.progress.visibility = View.GONE
                        rowsAdapter.submit(it, heroSeries)
                        updateLoadMore()
                    }
                    .onFailure {
                        binding.progress.visibility = View.GONE
                        Toast.makeText(this@HomeActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                    }
            }
        }
        binding.btnUpdate.setOnClickListener { checkUpdate() }

        binding.tabLibrary.setOnClickListener { setMode(HomeMode.LIBRARY) }
        binding.tabBrowse.setOnClickListener { setMode(HomeMode.BROWSE) }
        binding.tabSearch.setOnClickListener { setMode(HomeMode.SEARCH) }
        binding.btnKindSeries.setOnClickListener { setMediaKind(UserPrefs.KIND_SERIES) }
        binding.btnKindMovies.setOnClickListener { setMediaKind(UserPrefs.KIND_MOVIE) }

        // Legacy EditText stays gone — custom keyboard only (no system IME)
        binding.searchInput.visibility = View.GONE

        styleKindButtons()
        updateSearchHint()
        setMode(HomeMode.LIBRARY)
        refreshActiveProfile()
    }

    private fun onSearchKey(key: String) {
        UiSound.click(this, prefs)
        when (key) {
            "␣" -> searchQuery += " "
            "⌫" -> if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
            "CLR" -> searchQuery = ""
            else -> searchQuery += key
        }
        updateSearchQueryLabel()
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(180)
            runSearch(searchQuery)
        }
    }

    private fun updateSearchQueryLabel() {
        searchQueryLabel.text = if (searchQuery.isEmpty()) "Suche…" else searchQuery
    }

    private fun focusFirstSearchKey() {
        searchKeyboard.post {
            val holder = searchKeyboard.findViewHolderForAdapterPosition(0)
            holder?.itemView?.requestFocus()
                ?: searchKeyboard.getChildAt(0)?.requestFocus()
        }
    }

    private fun setMediaKind(kind: String) {
        if (prefs.mediaKind == kind) return
        UiSound.click(this, prefs)
        prefs.mediaKind = kind
        prefs.browsePage = 0
        styleKindButtons()
        updateSearchHint()
        when (mode) {
            HomeMode.SEARCH -> runSearch(searchQuery)
            else -> load(force = true)
        }
    }

    private fun styleKindButtons() {
        paintNavPill(binding.btnKindSeries, !prefs.isMovies)
        paintNavPill(binding.btnKindMovies, prefs.isMovies)
    }

    /**
     * Active nav item = filled pill + full-strength label. Selection is a drawable state,
     * never a persistent scale, so it cannot fight the focus animation.
     */
    private fun paintNavPill(btn: View, active: Boolean) {
        btn.isSelected = active
        btn.alpha = if (active) 1f else 0.7f
    }

    private fun updateSearchHint() {
        binding.searchInput.hint = getString(
            if (prefs.isMovies) R.string.search_hint_movies else R.string.search_hint
        )
    }

    private fun openProfiles() {
        UiSound.click(this, prefs)
        startActivity(Intent(this, ProfilesActivity::class.java))
    }

    private fun refreshActiveProfile() {
        lifecycleScope.launch {
            runCatching { (application as VerflixedApp).container.profiles.active() }
                .onSuccess { p ->
                    binding.profileNameLabel.text = p.name
                    Glide.with(binding.profileAvatar)
                        .load(p.avatarUrl)
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .error(R.drawable.ic_avatar_placeholder)
                        .transform(CircleCrop())
                        .into(binding.profileAvatar)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            refreshActiveProfile()
            load(force = false)
        }
    }

    private fun setMode(newMode: HomeMode) {
        mode = newMode
        styleTabs()
        UiSound.click(this, prefs)
        binding.searchInput.visibility = View.GONE
        binding.filterChips.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        when (mode) {
            HomeMode.SEARCH -> {
                binding.heroContainer.visibility = View.GONE
                binding.rows.visibility = View.GONE
                searchPanel.visibility = View.VISIBLE
                binding.tabSearch.nextFocusDownId = R.id.searchKeyboard
                updateSearchQueryLabel()
                focusFirstSearchKey()
                runSearch(searchQuery)
            }
            HomeMode.LIBRARY, HomeMode.BROWSE -> {
                searchPanel.visibility = View.GONE
                binding.rows.visibility = View.VISIBLE
                binding.heroContainer.visibility = View.GONE
                binding.tabSearch.nextFocusDownId = R.id.rows
                load(force = false)
            }
        }
    }

    private fun styleTabs() {
        paintNavPill(binding.tabLibrary, mode == HomeMode.LIBRARY)
        paintNavPill(binding.tabBrowse, mode == HomeMode.BROWSE)
        paintNavPill(binding.tabSearch, mode == HomeMode.SEARCH)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun refreshChips() = Unit

    @Suppress("UNUSED_PARAMETER")
    private fun toggleChip(chip: GenreChip) = Unit

    private fun load(force: Boolean) {
        if (mode == HomeMode.SEARCH) return
        val repo = (application as VerflixedApp).container.catalog
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching {
                when (mode) {
                    HomeMode.LIBRARY -> repo.getLibraryRows()
                    HomeMode.BROWSE -> {
                        if (force) repo.resetBrowsePage()
                        repo.getBrowseRows(forceRefresh = force)
                    }
                    HomeMode.SEARCH -> emptyList()
                }
            }.onSuccess { rows ->
                binding.progress.visibility = View.GONE
                val featured = rows.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
                if (featured != null) heroSeries = featured
                rowsAdapter.submit(rows, featured)
                if (featured != null) updateHero(featured)
                if (rows.all { it.items.isEmpty() }) {
                    binding.emptyText.text = when (mode) {
                        HomeMode.LIBRARY -> getString(R.string.library_empty)
                        else -> if (prefs.isMovies) {
                            "Keine Filme gefunden. [VF-102]"
                        } else {
                            "Keine Serien gefunden. [VF-102]"
                        }
                    }
                    binding.emptyText.visibility = View.VISIBLE
                }
                updateLoadMore()
            }.onFailure {
                binding.progress.visibility = View.GONE
                val msg = it.toVfMessage()
                if (msg.isBlank()) return@onFailure
                binding.emptyText.text = msg
                binding.emptyText.visibility = View.VISIBLE
                Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateLoadMore() {
        binding.btnLoadMore.visibility =
            if (mode == HomeMode.BROWSE &&
                (application as VerflixedApp).container.catalog.canLoadMoreBrowse()
            ) View.VISIBLE else View.GONE
    }

    private fun runSearch(query: String) {
        val repo = (application as VerflixedApp).container.catalog
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { repo.searchGrouped(query) }
                .onSuccess { rows ->
                    binding.progress.visibility = View.GONE
                    if (rows.isEmpty() || rows.all { it.items.isEmpty() }) {
                        searchResultsAdapter.submit(emptyList(), null)
                        binding.emptyText.text = getString(R.string.search_empty)
                        binding.emptyText.visibility = View.VISIBLE
                    } else {
                        binding.emptyText.visibility = View.GONE
                        val featured = rows.firstOrNull()?.items?.firstOrNull()
                        searchResultsAdapter.submit(rows, featured)
                        featured?.let { updateHero(it) }
                    }
                }
                .onFailure {
                    binding.progress.visibility = View.GONE
                    val msg = it.toVfMessage()
                    if (msg.isNotBlank()) {
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun checkUpdate() {
        val updates = (application as VerflixedApp).container.updates
        Toast.makeText(this, "Prüfe Update…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { updates.check() }
                .onSuccess { manifest ->
                    if (manifest == null) {
                        Toast.makeText(this@HomeActivity, "Kein Update verfügbar", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@HomeActivity,
                            "Update ${manifest.versionName ?: manifest.versionCode} – Download startet",
                            Toast.LENGTH_LONG
                        ).show()
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(manifest.apkUrl)))
                    }
                }
                .onFailure {
                    val msg = it.toVfMessage()
                    if (msg.isNotBlank()) {
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
            keyCode == android.view.KeyEvent.KEYCODE_INFO ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y
        ) {
            showHomeContextMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showHomeContextMenu() {
        val s = heroSeries ?: return
        val repo = (application as VerflixedApp).container.catalog
        val app = application as VerflixedApp
        lifecycleScope.launch {
            val isFav = runCatching { repo.isFavorite(s.id) }.getOrDefault(false)
            val options = mutableListOf(
                if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                "Als gesehen markieren",
                "Noch nicht gesehen markieren",
                "Metadaten neu laden",
                "Details öffnen",
            )
            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle(s.title)
                .setItems(options.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> lifecycleScope.launch {
                            runCatching {
                                val now = repo.toggleFavorite(s.id)
                                if (now) {
                                    app.appScope.launch {
                                        runCatching { repo.collectAllEpisodePlayerLinks(s.id) }
                                    }
                                }
                                now
                            }.onSuccess { now ->
                                Toast.makeText(
                                    this@HomeActivity,
                                    if (now) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (mode == HomeMode.LIBRARY) load(false)
                            }.onFailure {
                                val msg = it.toVfMessage()
                                if (msg.isNotBlank()) Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                        1 -> lifecycleScope.launch {
                            runCatching {
                                val full = repo.getSeries(s.id, enrich = false, detailPathHint = s.detailPath, titleHint = s.title)
                                val ep = repo.continueEpisode(full, repo.progressForSeries(s.id))
                                    ?: full.flatEpisodes().firstOrNull()
                                if (ep != null) repo.setEpisodeWatched(ep, true)
                            }.onSuccess {
                                Toast.makeText(this@HomeActivity, "Als gesehen markiert", Toast.LENGTH_SHORT).show()
                                if (mode == HomeMode.LIBRARY) load(false)
                            }
                        }
                        2 -> lifecycleScope.launch {
                            runCatching {
                                val full = repo.getSeries(s.id, enrich = false, detailPathHint = s.detailPath, titleHint = s.title)
                                full.flatEpisodes().forEach { repo.setEpisodeWatched(it, false) }
                            }.onSuccess {
                                Toast.makeText(this@HomeActivity, "Als ungesehen markiert", Toast.LENGTH_SHORT).show()
                                if (mode == HomeMode.LIBRARY) load(false)
                            }
                        }
                        3 -> lifecycleScope.launch {
                            Toast.makeText(this@HomeActivity, "Lade Metadaten…", Toast.LENGTH_SHORT).show()
                            runCatching {
                                repo.getSeries(
                                    s.id,
                                    enrich = true,
                                    detailPathHint = s.detailPath,
                                    titleHint = s.title,
                                    mediaKindHint = s.mediaKind,
                                )
                            }.onSuccess { refreshed ->
                                updateHero(refreshed)
                                Toast.makeText(this@HomeActivity, "Metadaten aktualisiert", Toast.LENGTH_SHORT).show()
                                if (mode == HomeMode.LIBRARY) load(true)
                            }.onFailure {
                                val msg = it.toVfMessage()
                                if (msg.isNotBlank()) Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                        4 -> openSeries(s)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateHero(series: Series) {
        heroSeries = series
        rowsAdapter.updateHero(series)
        if (mode == HomeMode.SEARCH) searchResultsAdapter.updateHero(series)
        // Keep stub binding fields in sync (legacy IDs, gone in layout).
        binding.heroTitle.text = series.title
        val meta = buildString {
            series.year?.let { append(it) }
            if (series.genres.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(series.genres.take(2).joinToString(" · "))
            }
            if (series.seasons.isNotEmpty() && !series.isMovie) {
                if (isNotEmpty()) append("  ·  ")
                append("${series.seasons.size} Staffeln")
            }
        }
        binding.heroMeta.text = meta
        val overview = series.overview?.trim().orEmpty()
        binding.heroOverview.text = overview
    }

    private fun openSeries(series: Series) {
        (application as VerflixedApp).container.catalog.rememberSeriesHit(series)
        val id = series.id
        startActivity(
            Intent(this, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
                .putExtra(SeriesDetailActivity.EXTRA_DETAIL_PATH, series.detailPath)
                .putExtra(SeriesDetailActivity.EXTRA_TITLE, series.title)
                .putExtra(SeriesDetailActivity.EXTRA_MEDIA_KIND, series.mediaKind)
        )
    }
}

private class SearchKeyAdapter(
    private val onKey: (String) -> Unit
) : RecyclerView.Adapter<SearchKeyAdapter.VH>() {
    private val keys: List<String> = buildList {
        addAll(('A'..'Z').map { it.toString() })
        addAll(('0'..'9').map { it.toString() })
        add("␣")
        add("⌫")
        add("CLR")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_key, parent, false)
        return VH(v as Button)
    }

    override fun getItemCount(): Int = keys.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val key = keys[position]
        holder.btn.text = key
        holder.btn.setOnClickListener { onKey(key) }
    }

    class VH(val btn: Button) : RecyclerView.ViewHolder(btn)
}

private class ChipAdapter(
    private val onClick: (GenreChip) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {
    private val items = mutableListOf<GenreChip>()
    private var include = emptySet<String>()
    private var exclude = emptySet<String>()

    fun submit(data: List<GenreChip>, includeGenres: Set<String>, excludeGenres: Set<String>) {
        items.clear()
        items.addAll(data)
        include = includeGenres
        exclude = excludeGenres
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_filter_chip, parent, false)
        return VH(v as TextView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = items[position]
        val active = if (chip.exclude) chip.id in exclude else chip.id in include
        holder.label.text = chip.label
        holder.label.alpha = if (active) 1f else 0.55f
        holder.label.isSelected = active
        holder.label.setOnClickListener { onClick(chip) }
    }

    class VH(val label: TextView) : RecyclerView.ViewHolder(label)
}

private class RowsAdapter(
    private val onClick: (Series) -> Unit,
    private val onFocused: (Series) -> Unit,
    private val onHeroPlay: () -> Unit,
    private val onHeroInfo: () -> Unit,
    private val prefsProvider: () -> com.streamvault.tv.data.prefs.UserPrefs,
    private val browseModeProvider: () -> Boolean,
    private val resolveArt: (Series, (Series) -> Unit) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<HomeRow>()
    private var hero: Series? = null

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    fun submit(data: List<HomeRow>, featured: Series? = null) {
        rows.clear()
        rows.addAll(data.filter { it.items.isNotEmpty() })
        hero = featured ?: rows.firstOrNull()?.items?.firstOrNull()
        notifyDataSetChanged()
    }

    fun updateHero(series: Series) {
        if (hero?.id == series.id &&
            hero?.posterUrl == series.posterUrl &&
            hero?.title == series.title &&
            hero?.overview == series.overview
        ) {
            hero = series
            return
        }
        hero = series
        if (itemCount > 0) notifyItemChanged(0)
    }

    override fun getItemViewType(position: Int): Int =
        if (hero != null && position == 0) TYPE_HERO else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HERO) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_hero, parent, false)
            HeroVH(view, onHeroPlay, onHeroInfo, browseModeProvider)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            RowVH(view, onClick, onFocused, prefsProvider, browseModeProvider, resolveArt)
        }
    }

    override fun getItemCount(): Int = rows.size + if (hero != null) 1 else 0

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeroVH -> hero?.let { holder.bind(it) }
            is RowVH -> {
                val idx = if (hero != null) position - 1 else position
                if (idx in rows.indices) holder.bind(rows[idx])
            }
        }
    }

    class HeroVH(
        itemView: View,
        onPlay: () -> Unit,
        onInfo: () -> Unit,
        private val browseModeProvider: () -> Boolean,
    ) : RecyclerView.ViewHolder(itemView) {
        private val backdrop: ImageView = itemView.findViewById(R.id.heroBackdrop)
        private val title: TextView = itemView.findViewById(R.id.heroTitle)
        private val meta: TextView = itemView.findViewById(R.id.heroMeta)
        private val overview: TextView = itemView.findViewById(R.id.heroOverview)
        private val play: Button = itemView.findViewById(R.id.btnHeroPlay)
        private val info: Button = itemView.findViewById(R.id.btnHeroInfo)

        init {
            play.setOnClickListener { onPlay() }
            info.setOnClickListener { onInfo() }
            FocusFx.bindScale(play, 1.06f)
            FocusFx.bindScale(info, 1.06f)
        }

        fun bind(series: Series) {
            title.text = series.title
            val metaText = buildString {
                series.year?.let { append(it) }
                val badges = series.genres.take(2)
                if (badges.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(badges.joinToString(" · "))
                }
            }
            meta.text = metaText
            meta.visibility = if (metaText.isBlank()) View.GONE else View.VISIBLE
            val ov = series.overview?.trim().orEmpty()
            overview.text = ov
            overview.visibility = if (ov.isBlank()) View.GONE else View.VISIBLE
            PosterLoader.loadHero(
                backdrop,
                series.backdropUrl ?: series.posterUrl,
                browseMode = browseModeProvider(),
            )
        }
    }

    class RowVH(
        itemView: View,
        onClick: (Series) -> Unit,
        onFocused: (Series) -> Unit,
        prefsProvider: () -> com.streamvault.tv.data.prefs.UserPrefs,
        browseModeProvider: () -> Boolean,
        resolveArt: (Series, (Series) -> Unit) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val list: RecyclerView = itemView.findViewById(R.id.rowList)
        private val posterAdapter = PosterAdapter(onClick, onFocused, prefsProvider, browseModeProvider, resolveArt)

        init {
            list.layoutManager = LinearLayoutManager(itemView.context, RecyclerView.HORIZONTAL, false)
            list.adapter = posterAdapter
            list.itemAnimator = null
            list.clipChildren = true
            list.clipToPadding = true
            list.isNestedScrollingEnabled = false
            list.overScrollMode = View.OVER_SCROLL_NEVER
        }

        fun bind(row: HomeRow) {
            title.text = row.title
            posterAdapter.submit(row.items)
        }
    }
}

private class PosterAdapter(
    private val onClick: (Series) -> Unit,
    private val onFocused: (Series) -> Unit,
    private val prefsProvider: () -> com.streamvault.tv.data.prefs.UserPrefs,
    private val browseModeProvider: () -> Boolean,
    private val resolveArt: (Series, (Series) -> Unit) -> Unit
) : RecyclerView.Adapter<PosterAdapter.PosterVH>() {
    private val items = mutableListOf<Series>()

    init {
        setHasStableIds(true)
    }

    fun submit(data: List<Series>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    fun updateItem(series: Series) {
        val idx = items.indexOfFirst { it.id == series.id }
        if (idx >= 0) {
            items[idx] = series
            notifyItemChanged(idx)
        }
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        return PosterVH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PosterVH, position: Int) {
        val item = items[position]
        holder.bind(item, browseModeProvider())
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        val open = View.OnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        holder.itemView.setOnClickListener(open)
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
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.04f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(160).start()
            v.elevation = if (hasFocus) 6f else 0f
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    (holder.itemView.parent as? RecyclerView)?.smoothScrollToPosition(pos)
                    val row = (holder.itemView.parent as? View)?.parent as? View
                    val rowsRv = row?.parent as? RecyclerView
                    val rowHolder = row?.let { rowsRv?.getChildViewHolder(it) }
                    rowHolder?.bindingAdapterPosition?.takeIf { it >= 0 }?.let { rowsRv?.smoothScrollToPosition(it) }
                    onFocused(items[pos])
                    resolveArt(items[pos]) { resolved -> updateItem(resolved) }
                }
            }
        }
        if (item.posterUrl.isNullOrBlank() && item.backdropUrl.isNullOrBlank() && browseModeProvider()) {
            resolveArt(item) { resolved -> updateItem(resolved) }
        }
    }

    class PosterVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.poster)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val badge: TextView? = itemView.findViewById(R.id.posterBadge)

        fun bind(series: Series, browseMode: Boolean) {
            title.text = series.title
            // Prefer portrait poster art — backdrop (16:9) causes letterbox/whitespace in 2:3 cards
            PosterLoader.loadSeries(poster, series.posterUrl ?: series.backdropUrl, browseMode = browseMode)
            val badgeText = series.genres.firstOrNull {
                it.contains("DEMNÄCHST", true) || it.contains("Uhr", true) || it.contains('.')
            } ?: series.overview?.lineSequence()?.firstOrNull {
                it.contains("DEMNÄCHST", true)
            }
            if (badge != null) {
                if (!badgeText.isNullOrBlank()) {
                    badge.text = badgeText.take(28)
                    badge.visibility = View.VISIBLE
                } else {
                    badge.visibility = View.GONE
                }
            }
        }
    }
}
