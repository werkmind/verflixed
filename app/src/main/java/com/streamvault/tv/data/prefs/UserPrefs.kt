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

    fun markSetupDone() {
        setupDone = true
    }

    val isConfigured: Boolean
        get() = setupDone || seriesBaseUrl.isNotBlank() || moviesBaseUrl.isNotBlank()

    fun clear() = sp.edit { clear() }

    companion object {
        const val KIND_SERIES = "series"
        const val KIND_MOVIE = "movie"

        const val DEFAULT_SERIES_BASE = "https://serienstream.cx"
        const val DEFAULT_MOVIES_BASE = "https://filmpalast.to"
        /** Short update manifest URL. */
        const val DEFAULT_UPDATE_MANIFEST: String = "https://clck.ru/3VBqgo"

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
        const val BROWSE_PAGE_SIZE = 24

        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isBlank()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            return u.trimEnd('/')
        }
    }
}
