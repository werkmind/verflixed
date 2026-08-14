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
import androidx.constraintlayout.widget.ConstraintLayout
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

enum class HomeMode { LIBRARY, SERIES, MOVIES, SEARCH }

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private var heroSeries: Series? = null
    private var mode = HomeMode.LIBRARY
    /** Last content tab — used so global search prioritizes the active section. */
    private var lastContentMode = HomeMode.LIBRARY
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var searchQuery = ""
    private var lastAppBackAt = 0L
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
        browseModeProvider = { mode == HomeMode.SERIES || mode == HomeMode.MOVIES || mode == HomeMode.SEARCH },
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

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Double-back to leave the app (same UX as player).
                    val now = System.currentTimeMillis()
                    if (mode == HomeMode.SEARCH) {
                        setMode(lastContentMode)
                        return
                    }
                    if (now - lastAppBackAt < 2_000L) {
                        finish()
                        return
                    }
                    lastAppBackAt = now
                    Toast.makeText(
                        this@HomeActivity,
                        getString(R.string.back_again_exit),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        )

        searchPanel = findViewById(R.id.searchPanel)
            ?: error("searchPanel missing from activity_home")
        searchQueryLabel = findViewById(R.id.searchQueryLabel)
            ?: error("searchQueryLabel missing")
        searchKeyboard = findViewById(R.id.searchKeyboard)
            ?: error("searchKeyboard missing")
        searchResultsList = findViewById(R.id.searchResults)
            ?: error("searchResults missing")

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

        // Side-nav focus → content
        listOf(
            binding.navLibrary, binding.navSeries, binding.navMovies,
            binding.navSearch, binding.navUpdate, binding.navSettings,
            binding.profileAvatar,
        ).forEach { it.nextFocusRightId = R.id.rows }
        binding.rows.nextFocusUpId = R.id.navLibrary
        binding.rows.nextFocusLeftId = R.id.navLibrary

        // Scroll-up fix: from top row / hero, DPAD_UP returns to sidebar/topbar
        binding.rows.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            val lm = binding.rows.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            if (lm.findFirstCompletelyVisibleItemPosition() <= 0) {
                binding.rows.stopScroll()
                binding.rows.scrollToPosition(0)
                focusActiveNav()
                true
            } else {
                binding.rows.smoothScrollBy(0, -binding.rows.height / 3)
                true
            }
        }

        binding.navScroll.isFocusable = false
        binding.navScroll.isFocusableInTouchMode = false
        binding.navScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        listOf(
            binding.btnUpdate, binding.btnRefresh, binding.btnSettings, binding.btnProfile,
            binding.navLibrary, binding.navSeries, binding.navMovies, binding.navSearch,
            binding.navUpdate, binding.navSettings, binding.profileAvatar,
            binding.tabLibrary, binding.tabBrowse, binding.tabSearch, binding.btnKindMovies,
        ).forEach { btn ->
            btn.isClickable = true
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            FocusFx.bindScale(btn, 1.04f, prefs)
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

        binding.btnSettings.setOnClickListener {
            UiSound.click(this, prefs)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            UiSound.click(this, prefs)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnProfile.setOnClickListener { openProfiles() }
        binding.profileAvatar.setOnClickListener { openProfiles() }
        binding.profileNameLabel.setOnClickListener { openProfiles() }
        binding.btnRefresh.setOnClickListener {
            UiSound.click(this, prefs)
            lifecycleScope.launch {
                if (mode == HomeMode.SERIES || mode == HomeMode.MOVIES) {
                    (application as VerflixedApp).container.catalog.resetBrowsePage()
                }
                load(force = true)
            }
        }
        binding.btnLoadMore.setOnClickListener {
            UiSound.click(this, prefs)
            lifecycleScope.launch {
                showSkeleton(true)
                runCatching { (application as VerflixedApp).container.catalog.loadMoreBrowse() }
                    .onSuccess {
                        showSkeleton(false)
                        rowsAdapter.submit(it, heroSeries)
                        updateLoadMore()
                    }
                    .onFailure {
                        showSkeleton(false)
                        Toast.makeText(this@HomeActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                    }
            }
        }
        binding.btnUpdate.setOnClickListener { checkUpdate() }
        binding.navUpdate.setOnClickListener { checkUpdate() }

        binding.tabLibrary.setOnClickListener { setMode(HomeMode.LIBRARY) }
        binding.tabBrowse.setOnClickListener { setMode(HomeMode.SERIES) }
        binding.tabSearch.setOnClickListener { setMode(HomeMode.SEARCH) }
        binding.btnKindSeries.setOnClickListener { setMode(HomeMode.SERIES) }
        binding.btnKindMovies.setOnClickListener { setMode(HomeMode.MOVIES) }
        binding.navLibrary.setOnClickListener { setMode(HomeMode.LIBRARY) }
        binding.navSeries.setOnClickListener { setMode(HomeMode.SERIES) }
        binding.navMovies.setOnClickListener { setMode(HomeMode.MOVIES) }
        binding.navSearch.setOnClickListener { setMode(HomeMode.SEARCH) }

        binding.searchInput.visibility = View.GONE

        applyNavChrome()
        setMode(HomeMode.LIBRARY)
        refreshActiveProfile()
    }

    private fun applyNavChrome() {
        val sidebar = prefs.isSidebarNav
        binding.sideNav.visibility = if (sidebar) View.VISIBLE else View.GONE
        binding.navScroll.visibility = if (sidebar) View.GONE else View.VISIBLE
        val rowsParams = binding.rows.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            ?: return
        if (sidebar) {
            rowsParams.startToEnd = R.id.sideNav
            rowsParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            rowsParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            rowsParams.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            rowsParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            rowsParams.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            rowsParams.topToBottom = R.id.navScroll
            rowsParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        }
        binding.rows.layoutParams = rowsParams
        // Mirror for search + skeleton + empty
        listOf(binding.skeletonHost, binding.emptyText, binding.progress, searchPanel).forEach { v ->
            val p = v.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                ?: return@forEach
            p.startToEnd = if (sidebar) R.id.sideNav else androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            p.startToStart = if (sidebar) androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            else androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            p.topToTop = if (sidebar) androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            else androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            p.topToBottom = if (sidebar) androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            else R.id.navScroll
            v.layoutParams = p
        }
    }

    private fun focusActiveNav() {
        val target = when (mode) {
            HomeMode.LIBRARY -> if (prefs.isSidebarNav) binding.navLibrary else binding.tabLibrary
            HomeMode.SERIES -> if (prefs.isSidebarNav) binding.navSeries else binding.tabBrowse
            HomeMode.MOVIES -> if (prefs.isSidebarNav) binding.navMovies else binding.btnKindMovies
            HomeMode.SEARCH -> if (prefs.isSidebarNav) binding.navSearch else binding.tabSearch
        }
        target.requestFocus()
    }

    private fun showSkeleton(show: Boolean) {
        binding.skeletonHost.visibility = if (show) View.VISIBLE else View.GONE
        binding.progress.visibility = View.GONE
        if (show) binding.rows.alpha = 0.35f else binding.rows.alpha = 1f
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
        runSearch(searchQuery)
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
            applyNavChrome()
            refreshActiveProfile()
            if (mode != HomeMode.SEARCH) load(force = false)
        }
    }

    private fun setMode(newMode: HomeMode) {
        if (newMode != HomeMode.SEARCH) lastContentMode = newMode
        mode = newMode
        styleTabs()
        UiSound.click(this, prefs)
        binding.searchInput.visibility = View.GONE
        binding.filterChips.visibility = View.GONE
        binding.btnLoadMore.visibility = View.GONE
        when (mode) {
            HomeMode.SEARCH -> {
                loadJob?.cancel()
                binding.heroContainer.visibility = View.GONE
                binding.rows.visibility = View.GONE
                searchPanel.visibility = View.VISIBLE
                binding.tabSearch.nextFocusDownId = R.id.searchKeyboard
                updateSearchQueryLabel()
                focusFirstSearchKey()
                runSearch(searchQuery)
            }
            HomeMode.LIBRARY -> {
                searchJob?.cancel()
                showSkeleton(false)
                searchPanel.visibility = View.GONE
                binding.rows.visibility = View.VISIBLE
                binding.heroContainer.visibility = View.GONE
                binding.tabSearch.nextFocusDownId = R.id.rows
                load(force = false)
            }
            HomeMode.SERIES -> {
                searchJob?.cancel()
                showSkeleton(false)
                searchPanel.visibility = View.GONE
                binding.rows.visibility = View.VISIBLE
                binding.heroContainer.visibility = View.GONE
                binding.tabSearch.nextFocusDownId = R.id.rows
                if (prefs.mediaKind != UserPrefs.KIND_SERIES) {
                    prefs.mediaKind = UserPrefs.KIND_SERIES
                    prefs.browsePage = 0
                }
                updateSearchHint()
                load(force = true)
            }
            HomeMode.MOVIES -> {
                searchJob?.cancel()
                showSkeleton(false)
                searchPanel.visibility = View.GONE
                binding.rows.visibility = View.VISIBLE
                binding.heroContainer.visibility = View.GONE
                binding.tabSearch.nextFocusDownId = R.id.rows
                if (prefs.mediaKind != UserPrefs.KIND_MOVIE) {
                    prefs.mediaKind = UserPrefs.KIND_MOVIE
                    prefs.browsePage = 0
                }
                updateSearchHint()
                load(force = true)
            }
        }
    }

    private fun styleTabs() {
        paintNavPill(binding.tabLibrary, mode == HomeMode.LIBRARY)
        paintNavPill(binding.tabBrowse, mode == HomeMode.SERIES)
        paintNavPill(binding.btnKindMovies, mode == HomeMode.MOVIES)
        paintNavPill(binding.tabSearch, mode == HomeMode.SEARCH)
        paintNavPill(binding.navLibrary, mode == HomeMode.LIBRARY)
        paintNavPill(binding.navSeries, mode == HomeMode.SERIES)
        paintNavPill(binding.navMovies, mode == HomeMode.MOVIES)
        paintNavPill(binding.navSearch, mode == HomeMode.SEARCH)
        styleKindButtons()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun refreshChips() = Unit

    @Suppress("UNUSED_PARAMETER")
    private fun toggleChip(chip: GenreChip) = Unit

    private fun load(force: Boolean) {
        if (mode == HomeMode.SEARCH) return
        val repo = (application as VerflixedApp).container.catalog
        showSkeleton(true)
        binding.emptyText.visibility = View.GONE
        loadJob?.cancel()
        val requestedMode = mode
        loadJob = lifecycleScope.launch {
            runCatching {
                when (requestedMode) {
                    HomeMode.LIBRARY -> repo.getLibraryRows()
                    HomeMode.SERIES -> {
                        prefs.mediaKind = UserPrefs.KIND_SERIES
                        if (force) repo.resetBrowsePage()
                        repo.getBrowseRows(forceRefresh = force)
                    }
                    HomeMode.MOVIES -> {
                        prefs.mediaKind = UserPrefs.KIND_MOVIE
                        if (force) repo.resetBrowsePage()
                        repo.getBrowseRows(forceRefresh = force)
                    }
                    HomeMode.SEARCH -> emptyList()
                }
            }.onSuccess { rows ->
                if (mode != requestedMode || mode == HomeMode.SEARCH) return@onSuccess
                showSkeleton(false)
                val featured = rows.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
                if (featured != null) heroSeries = featured
                rowsAdapter.submit(rows, featured)
                if (featured != null) updateHero(featured)
                if (rows.all { it.items.isEmpty() }) {
                    binding.emptyText.text = when (requestedMode) {
                        HomeMode.LIBRARY -> getString(R.string.library_empty)
                        HomeMode.MOVIES -> "Keine Filme gefunden. [VF-102]"
                        else -> "Keine Serien gefunden. [VF-102]"
                    }
                    binding.emptyText.visibility = View.VISIBLE
                }
                updateLoadMore()
            }.onFailure {
                if (mode != requestedMode || mode == HomeMode.SEARCH) return@onFailure
                showSkeleton(false)
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
            if ((mode == HomeMode.SERIES || mode == HomeMode.MOVIES) &&
                (application as VerflixedApp).container.catalog.canLoadMoreBrowse()
            ) View.VISIBLE else View.GONE
    }

    private fun runSearch(query: String) {
        val repo = (application as VerflixedApp).container.catalog
        val q = query
        val effectivePriority = when (lastContentMode) {
            HomeMode.SERIES -> UserPrefs.KIND_SERIES
            HomeMode.MOVIES -> UserPrefs.KIND_MOVIE
            else -> null
        }
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(180)
            if (mode != HomeMode.SEARCH) return@launch
            // Skeleton only overlays browse rows; keep search panel responsive.
            showSkeleton(false)
            binding.emptyText.visibility = View.GONE
            runCatching { repo.searchGlobal(q, effectivePriority) }
                .onSuccess { rows ->
                    if (mode != HomeMode.SEARCH || q != searchQuery) return@onSuccess
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
                    if (mode != HomeMode.SEARCH || q != searchQuery) return@onFailure
                    val msg = it.toVfMessage()
                    if (msg.isNotBlank()) {
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun checkUpdate() {
        val updates = (application as VerflixedApp).container.updates
        val installer = com.streamvault.tv.data.update.ApkUpdateInstaller()
        Toast.makeText(this, "Prüfe Update…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { updates.check() }
                .onSuccess { manifest ->
                    if (manifest == null) {
                        Toast.makeText(this@HomeActivity, "Kein Update verfügbar", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    if (!installer.canInstallPackages(this@HomeActivity)) {
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.update_allow_unknown),
                            Toast.LENGTH_LONG,
                        ).show()
                        installer.openUnknownSourcesSettings(this@HomeActivity)
                        return@onSuccess
                    }
                    Toast.makeText(
                        this@HomeActivity,
                        "Update ${manifest.versionName ?: manifest.versionCode} – lade APK…",
                        Toast.LENGTH_SHORT,
                    ).show()
                    runCatching {
                        val apkUrl = manifest.apkUrl?.trim().orEmpty()
                        if (apkUrl.isBlank()) error("Keine APK-URL im Manifest")
                        installer.download(this@HomeActivity, apkUrl) { frac ->
                            // Fire TV: lightweight toast every ~25%
                            val pct = (frac * 100).toInt()
                            if (pct % 25 == 0) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@HomeActivity,
                                        getString(R.string.update_downloading, pct),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }.onSuccess { result ->
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.update_install),
                            Toast.LENGTH_SHORT,
                        ).show()
                        installer.install(this@HomeActivity, result.file)
                    }.onFailure {
                        Toast.makeText(
                            this@HomeActivity,
                            "Update-Download fehlgeschlagen: ${it.message}",
                            Toast.LENGTH_LONG,
                        ).show()
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

    /**
     * Poster/hero children eat DPAD_UP before the rows RecyclerView key listener.
     * When already at the top of the feed, route focus back to the nav chrome.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
            mode != HomeMode.SEARCH &&
            ::binding.isInitialized
        ) {
            val focused = currentFocus
            val inSide = focused != null && isUnder(binding.sideNav, focused)
            val inTop = focused != null && isUnder(binding.navBar, focused)
            if (focused != null && !inSide && !inTop && isUnder(binding.rows, focused)) {
                val lm = binding.rows.layoutManager as? LinearLayoutManager
                val first = lm?.findFirstVisibleItemPosition() ?: -1
                if (first <= 0) {
                    binding.rows.stopScroll()
                    focusActiveNav()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isUnder(parent: View, child: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
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
                                    if (now) "Favorit gespeichert – Streams werden gecacht"
                                    else "Favorit entfernt – Stream-Cache gelöscht",
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
    /** Stagger the entrance only for the first paint after a (re)load. */
    private var animateGeneration = 0
    private val animatedPositions = mutableSetOf<Int>()

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    fun submit(data: List<HomeRow>, featured: Series? = null) {
        rows.clear()
        rows.addAll(data.filter { it.items.isNotEmpty() })
        hero = featured ?: rows.firstOrNull()?.items?.firstOrNull()
        animateGeneration++
        animatedPositions.clear()
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
        if (animatedPositions.add(position) && position < 6) {
            FocusFx.enter(holder.itemView, position)
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
            FocusFx.bindScale(play, 1.04f)
            FocusFx.bindScale(info, 1.04f)
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
            list.clipChildren = false
            list.clipToPadding = false
            list.isNestedScrollingEnabled = false
            list.overScrollMode = View.OVER_SCROLL_NEVER
        }

        fun bind(row: HomeRow) {
            title.text = row.title
            posterAdapter.submit(row.items, row.title)
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
    private var rowTitle: String = ""

    init {
        setHasStableIds(true)
    }

    fun submit(data: List<Series>, title: String = "") {
        items.clear()
        items.addAll(data)
        rowTitle = title
        notifyDataSetChanged()
    }

    fun updateItem(series: Series) {
        val idx = items.indexOfFirst { it.id == series.id }
        if (idx !in items.indices) return
        items[idx] = series
        // Avoid "Cannot call this method while RecyclerView is computing layout"
        try {
            notifyItemChanged(idx)
        } catch (_: IllegalStateException) {
            // Next bind will refresh.
        }
    }

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    private fun useCards(): Boolean {
        if (browseModeProvider()) return false
        // A–Z + library shelves honor the per-profile tiles/cards preference.
        return prefsProvider().isLibraryCards
    }

    override fun getItemViewType(position: Int): Int = if (useCards()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterVH {
        val layout = if (viewType == 1) R.layout.item_poster_card else R.layout.item_poster
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return PosterVH(view)
    }

    override fun getItemCount(): Int = items.size

    private fun itemAt(pos: Int): Series? = items.getOrNull(pos)

    override fun onBindViewHolder(holder: PosterVH, position: Int) {
        val item = itemAt(position) ?: return
        holder.bind(item, browseModeProvider())
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        val open = View.OnClickListener {
            val pos = holder.bindingAdapterPosition
            val s = itemAt(pos) ?: return@OnClickListener
            onClick(s)
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
            FocusFx.animateFocus(v, hasFocus, 1.07f)
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                val s = itemAt(pos) ?: return@setOnFocusChangeListener
                val parentRv = holder.itemView.parent as? RecyclerView
                parentRv?.post {
                    if (holder.bindingAdapterPosition == pos) {
                        parentRv.smoothScrollToPosition(pos)
                    }
                }
                val row = (holder.itemView.parent as? View)?.parent as? View
                val rowsRv = row?.parent as? RecyclerView
                val rowHolder = row?.let { rowsRv?.getChildViewHolder(it) }
                rowHolder?.bindingAdapterPosition?.takeIf { it >= 0 }?.let { rp ->
                    rowsRv?.post { rowsRv.smoothScrollToPosition(rp) }
                }
                onFocused(s)
                resolveArt(s) { resolved -> updateItem(resolved) }
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
            val landscape = itemView.layoutParams?.width ?: 0 > (itemView.layoutParams?.height ?: 1)
            val art = if (landscape) {
                series.backdropUrl ?: series.posterUrl
            } else {
                series.posterUrl ?: series.backdropUrl
            }
            PosterLoader.loadSeries(poster, art, browseMode = browseMode)
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
            val bar = itemView.findViewById<View>(R.id.progressBar)
            val frac = series.progressFraction
            if (bar != null) {
                if (frac != null && frac > 0.02f) {
                    bar.visibility = View.VISIBLE
                    val lp = bar.layoutParams
                    if (lp is ConstraintLayout.LayoutParams) {
                        lp.matchConstraintPercentWidth = frac.coerceIn(0.06f, 1f)
                        bar.layoutParams = lp
                    }
                } else {
                    bar.visibility = View.GONE
                }
            }
        }
    }
}
