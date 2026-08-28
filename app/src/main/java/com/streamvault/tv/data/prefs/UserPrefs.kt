package com.streamvault.tv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class UserPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("verflixed_prefs", Context.MODE_PRIVATE)

    init {
        // Seed defaults for first run / empty prefs (testing + Fire TV setup).
        // Use SharedPreferences directly — property setters are not yet initialized.
        sp.edit {
            if (sp.getString(KEY_SERIES_BASE, null).isNullOrBlank() &&
                sp.getString(KEY_BASE_URL, null).isNullOrBlank()
            ) {
                val normalized = normalizeUrl(DEFAULT_SERIES_BASE)
                putString(KEY_SERIES_BASE, normalized)
                putString(KEY_BASE_URL, normalized)
            }
            if (sp.getString(KEY_MOVIES_BASE, null).isNullOrBlank()) {
                putString(KEY_MOVIES_BASE, normalizeUrl(DEFAULT_MOVIES_BASE))
            }
            // Always prefer system Fire TV nav sounds over in-app beeps.
            if (!sp.contains(KEY_SOUNDS)) {
                putBoolean(KEY_SOUNDS, false)
            }
            // Clear legacy antifilter selections.
            if (sp.contains(KEY_INCLUDE) || sp.contains(KEY_EXCLUDE)) {
                remove(KEY_INCLUDE)
                remove(KEY_EXCLUDE)
            }
        }
    }

    /** Series catalog base URL. Migrates from legacy [KEY_BASE_URL]. */
    var seriesBaseUrl: String
        get() {
            val series = sp.getString(KEY_SERIES_BASE, null)?.trim().orEmpty()
            if (series.isNotBlank()) return series
            val legacy = sp.getString(KEY_BASE_URL, "")?.trim().orEmpty()
            return legacy.ifBlank { DEFAULT_SERIES_BASE }
        }
        set(value) {
            val normalized = normalizeUrl(value).ifBlank { DEFAULT_SERIES_BASE }
            sp.edit {
                putString(KEY_SERIES_BASE, normalized)
                putString(KEY_BASE_URL, normalized)
            }
        }

    var moviesBaseUrl: String
        get() = sp.getString(KEY_MOVIES_BASE, "")?.trim().orEmpty()
            .ifBlank { DEFAULT_MOVIES_BASE }
        set(value) {
            val normalized = normalizeUrl(value).ifBlank { DEFAULT_MOVIES_BASE }
            sp.edit { putString(KEY_MOVIES_BASE, normalized) }
        }

    /** Active media kind: "series" or "movie". */
    var mediaKind: String
        get() = sp.getString(KEY_MEDIA_KIND, KIND_SERIES)?.trim().orEmpty()
            .ifBlank { KIND_SERIES }
            .let { if (it == KIND_MOVIE) KIND_MOVIE else KIND_SERIES }
        set(value) = sp.edit {
            putString(KEY_MEDIA_KIND, if (value == KIND_MOVIE) KIND_MOVIE else KIND_SERIES)
        }

    val isMovies: Boolean get() = mediaKind == KIND_MOVIE

    /** Alias for [seriesBaseUrl] (migration / older call sites). */
    var baseUrl: String
        get() = seriesBaseUrl
        set(value) {
            seriesBaseUrl = value
        }

    fun activeBaseUrl(): String =
        if (isMovies) {
            moviesBaseUrl.ifBlank { seriesBaseUrl }
        } else {
            seriesBaseUrl.ifBlank { moviesBaseUrl }
        }

    var tmdbApiKey: String
        get() = sp.getString(KEY_TMDB, "")?.trim().orEmpty()
        set(value) = sp.edit { putString(KEY_TMDB, value.trim()) }

    var updateManifestUrl: String
        get() = sp.getString(KEY_UPDATE, "")?.trim().orEmpty()
            .ifBlank { DEFAULT_UPDATE_MANIFEST }
        set(value) = sp.edit { putString(KEY_UPDATE, value.trim()) }

    var uiSoundsEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUNDS, false)
        set(value) = sp.edit { putBoolean(KEY_SOUNDS, value) }

    /** Kept for ABI; antifilter UI removed — always empty. */
    var includeGenres: Set<String>
        get() = emptySet()
        set(_) = sp.edit { remove(KEY_INCLUDE) }

    var excludeGenres: Set<String>
        get() = emptySet()
        set(_) = sp.edit { remove(KEY_EXCLUDE) }

    var browsePage: Int
        get() = sp.getInt(KEY_BROWSE_PAGE, 0)
        set(value) = sp.edit { putInt(KEY_BROWSE_PAGE, value.coerceAtLeast(0)) }

    var activeProfileId: String?
        get() = sp.getString(KEY_PROFILE, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = sp.edit {
            if (value.isNullOrBlank()) remove(KEY_PROFILE) else putString(KEY_PROFILE, value.trim())
        }

    var setupDone: Boolean
        get() = sp.getBoolean(KEY_SETUP_DONE, false)
        set(value) = sp.edit { putBoolean(KEY_SETUP_DONE, value) }

    /**
     * Preferred stream audio language for a profile: "de" (default) or "en".
     * Profile-scoped so each Fire TV profile can differ.
     */
    fun streamLanguage(profileId: String?): String {
        val id = profileId?.trim().orEmpty()
        if (id.isNotBlank()) {
            val keyed = sp.getString("$KEY_STREAM_LANG_PREFIX$id", null)?.trim().orEmpty()
            if (keyed.isNotBlank()) return StreamLanguageCompat.normalize(keyed)
        }
        val legacy = sp.getString(KEY_STREAM_LANG, null)?.trim().orEmpty()
        return StreamLanguageCompat.normalize(legacy.ifBlank { LANG_DE })
    }

    fun setStreamLanguage(profileId: String?, code: String) {
        val normalized = StreamLanguageCompat.normalize(code)
        sp.edit {
            val id = profileId?.trim().orEmpty()
            if (id.isNotBlank()) putString("$KEY_STREAM_LANG_PREFIX$id", normalized)
            // Keep a global fallback in sync with the active profile.
            putString(KEY_STREAM_LANG, normalized)
        }
    }

    /** Navigation chrome: topbar (default) or sidebar — per profile. */
    fun navLayout(profileId: String?): String {
        val id = profileId?.trim().orEmpty()
        if (id.isNotBlank()) {
            val keyed = sp.getString("$KEY_NAV_LAYOUT_PREFIX$id", null)?.trim().orEmpty()
            if (keyed.isNotBlank()) return normalizeNavLayout(keyed)
        }
        return normalizeNavLayout(sp.getString(KEY_NAV_LAYOUT, null))
    }

    fun setNavLayout(profileId: String?, layout: String) {
        val normalized = normalizeNavLayout(layout)
        sp.edit {
            val id = profileId?.trim().orEmpty()
            if (id.isNotBlank()) putString("$KEY_NAV_LAYOUT_PREFIX$id", normalized)
            putString(KEY_NAV_LAYOUT, normalized)
        }
    }

    val isSidebarNav: Boolean get() = navLayout(activeProfileId) == NAV_SIDEBAR

    /** Library presentation: poster tiles (default) or denser cards — per profile. */
    fun libraryView(profileId: String?): String {
        val id = profileId?.trim().orEmpty()
        if (id.isNotBlank()) {
            val keyed = sp.getString("$KEY_LIB_VIEW_PREFIX$id", null)?.trim().orEmpty()
            if (keyed.isNotBlank()) return normalizeLibraryView(keyed)
        }
        return normalizeLibraryView(sp.getString(KEY_LIB_VIEW, null))
    }

    fun setLibraryView(profileId: String?, view: String) {
        val normalized = normalizeLibraryView(view)
        sp.edit {
            val id = profileId?.trim().orEmpty()
            if (id.isNotBlank()) putString("$KEY_LIB_VIEW_PREFIX$id", normalized)
            putString(KEY_LIB_VIEW, normalized)
        }
    }

    val isLibraryCards: Boolean get() = libraryView(activeProfileId) == LIB_CARDS

    /** UI zoom percent. Device-wide, applied via densityDpi. */
    var uiScalePercent: Int
        get() = normalizeScale(sp.getInt(KEY_UI_SCALE, SCALE_DEFAULT))
        set(value) = sp.edit { putInt(KEY_UI_SCALE, normalizeScale(value)) }

    /**
     * Categories that must never be loaded (browse shelves, catalog, search).
     * Default: Horror + Anime. Missing key = defaults, empty stored set = allow all.
     */
    fun blockedGenres(profileId: String?): Set<String> {
        val id = profileId?.trim().orEmpty()
        val keyed = if (id.isNotBlank()) "$KEY_BLOCKED_PREFIX$id" else null
        val raw = when {
            keyed != null && sp.contains(keyed) -> sp.getString(keyed, "")
            sp.contains(KEY_BLOCKED) -> sp.getString(KEY_BLOCKED, "")
            else -> return DEFAULT_BLOCKED_GENRES
        }
        return parseCsvSet(raw)
    }

    fun setBlockedGenres(profileId: String?, ids: Set<String>) {
        val normalized = ids.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSortedSet()
        val value = normalized.joinToString(",")
        sp.edit {
            val id = profileId?.trim().orEmpty()
            if (id.isNotBlank()) putString("$KEY_BLOCKED_PREFIX$id", value)
            putString(KEY_BLOCKED, value)
        }
    }

    fun toggleBlockedGenre(profileId: String?, genreId: String): Set<String> {
        val id = genreId.trim().lowercase()
        val next = blockedGenres(profileId).toMutableSet()
        if (id in next) next.remove(id) else next.add(id)
        setBlockedGenres(profileId, next)
        return next
    }

    fun markSetupDone() {
        setupDone = true
    }

    val isConfigured: Boolean
        get() = setupDone || seriesBaseUrl.isNotBlank() || moviesBaseUrl.isNotBlank()

    fun clear() = sp.edit { clear() }

    companion object {
        const val KIND_SERIES = "series"
        const val KIND_MOVIE = "movie"
        const val LANG_DE = "de"
        const val LANG_EN = "en"
        const val NAV_SIDEBAR = "sidebar"
        const val NAV_TOPBAR = "topbar"
        const val LIB_TILES = "tiles"
        const val LIB_CARDS = "cards"
        /** Default is slightly zoomed out so more shelves fit a 1080p TV. */
        const val SCALE_DEFAULT = 85
        val SCALE_STEPS = listOf(75, 85, 100, 115, 130)
        val DEFAULT_BLOCKED_GENRES = setOf("horror", "anime")

        const val DEFAULT_SERIES_BASE = "https://serienstream.cx"
        const val DEFAULT_MOVIES_BASE = "https://filmpalast.to"
        /** Persistent GitHub latest-download URL (no catbox expiry). */
        const val DEFAULT_UPDATE_MANIFEST: String =
            "https://github.com/werkmind/verflixed/releases/latest/download/verflixed-update.json"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SERIES_BASE = "series_base_url"
        private const val KEY_MOVIES_BASE = "movies_base_url"
        private const val KEY_MEDIA_KIND = "media_kind"
        private const val KEY_TMDB = "tmdb_api_key"
        private const val KEY_UPDATE = "update_manifest_url"
        private const val KEY_SOUNDS = "ui_sounds"
        private const val KEY_INCLUDE = "include_genres"
        private const val KEY_EXCLUDE = "exclude_genres"
        private const val KEY_BROWSE_PAGE = "browse_page"
        private const val KEY_PROFILE = "active_profile_id"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_STREAM_LANG = "stream_language"
        private const val KEY_STREAM_LANG_PREFIX = "stream_language_"
        private const val KEY_NAV_LAYOUT = "nav_layout"
        private const val KEY_NAV_LAYOUT_PREFIX = "nav_layout_"
        private const val KEY_LIB_VIEW = "library_view"
        private const val KEY_LIB_VIEW_PREFIX = "library_view_"
        private const val KEY_UI_SCALE = "ui_scale_percent"
        private const val KEY_BLOCKED = "blocked_genres"
        private const val KEY_BLOCKED_PREFIX = "blocked_genres_"
        const val BROWSE_PAGE_SIZE = 24

        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isBlank()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            return u.trimEnd('/')
        }

        fun normalizeNavLayout(raw: String?): String {
            val l = raw?.trim()?.lowercase().orEmpty()
            // Unset = the modern default: top bar (Netflix-style nav).
            if (l.isEmpty()) return NAV_TOPBAR
            return if (l == NAV_TOPBAR || l == "top" || l == "bar") NAV_TOPBAR else NAV_SIDEBAR
        }

        fun normalizeLibraryView(raw: String?): String {
            val l = raw?.trim()?.lowercase().orEmpty()
            return if (l == LIB_CARDS || l == "card" || l == "list") LIB_CARDS else LIB_TILES
        }

        fun normalizeScale(raw: Int): Int =
            SCALE_STEPS.minByOrNull { kotlin.math.abs(it - raw) } ?: SCALE_DEFAULT

        private fun parseCsvSet(raw: String?): Set<String> =
            raw.orEmpty()
                .split(',', ';')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
    }
}

/** Tiny local normalizer so UserPrefs does not create a circular import at class-init time. */
private object StreamLanguageCompat {
    fun normalize(raw: String?): String {
        val l = raw?.trim()?.lowercase().orEmpty()
        return when {
            l.isBlank() -> "de"
            l == "en" || l.startsWith("en") || l.contains("englisch") || l.contains("english") -> "en"
            else -> "de"
        }
    }
}
