package com.streamvault.tv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class UserPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("verflixed_prefs", Context.MODE_PRIVATE)

    /** Series catalog base URL. Migrates from legacy [KEY_BASE_URL]. */
    var seriesBaseUrl: String
        get() {
            val series = sp.getString(KEY_SERIES_BASE, null)?.trim().orEmpty()
            if (series.isNotBlank()) return series
            return sp.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        }
        set(value) {
            val normalized = value.trim().trimEnd('/')
            sp.edit {
                putString(KEY_SERIES_BASE, normalized)
                // Keep legacy key in sync for older code / updates.
                putString(KEY_BASE_URL, normalized)
            }
        }

    var moviesBaseUrl: String
        get() = sp.getString(KEY_MOVIES_BASE, "")?.trim().orEmpty()
        set(value) = sp.edit { putString(KEY_MOVIES_BASE, value.trim().trimEnd('/')) }

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

    /** Prefer series base; fall back to movies when only movies are configured. */
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
        set(value) = sp.edit { putString(KEY_UPDATE, value.trim()) }

    var uiSoundsEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUNDS, true)
        set(value) = sp.edit { putBoolean(KEY_SOUNDS, value) }

    /** Active include genre ids for search/browse filters. */
    var includeGenres: Set<String>
        get() = sp.getStringSet(KEY_INCLUDE, emptySet())?.toSet().orEmpty()
        set(value) = sp.edit { putStringSet(KEY_INCLUDE, value) }

    /** Anti-pattern / exclude genre ids (e.g. anime, comedy). */
    var excludeGenres: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDE, emptySet())?.toSet().orEmpty()
        set(value) = sp.edit { putStringSet(KEY_EXCLUDE, value) }

    var browsePage: Int
        get() = sp.getInt(KEY_BROWSE_PAGE, 0)
        set(value) = sp.edit { putInt(KEY_BROWSE_PAGE, value.coerceAtLeast(0)) }

    var activeProfileId: String?
        get() = sp.getString(KEY_PROFILE, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = sp.edit {
            if (value.isNullOrBlank()) remove(KEY_PROFILE) else putString(KEY_PROFILE, value.trim())
        }

    val isConfigured: Boolean
        get() = seriesBaseUrl.isNotBlank() || moviesBaseUrl.isNotBlank()

    fun clear() = sp.edit { clear() }

    companion object {
        const val KIND_SERIES = "series"
        const val KIND_MOVIE = "movie"

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
        const val BROWSE_PAGE_SIZE = 24
    }
}
