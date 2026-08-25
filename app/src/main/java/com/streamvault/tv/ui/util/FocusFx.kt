package com.streamvault.tv.ui.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.streamvault.tv.R
import com.streamvault.tv.data.prefs.UserPrefs

/**
 * Apple-TV-like focus motion: one smooth ease, no bounce, GPU-layer backed.
 * Focus grows gently and lifts with a soft shadow; unfocus glides back a touch
 * slower so movement reads calm instead of twitchy.
 */
object FocusFx {
    /** tvOS-style standard curve — fast start, long soft landing. */
    private val glide = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val easeIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun bindScale(view: View, focusedScale: Float = 1.06f, prefs: UserPrefs? = null) {
        allowFocusScale(view)
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animateFocus(v, hasFocus, focusedScale)
        }
    }

    /**
     * Scale may paint into a host's padding, never out of the section.
     * Intermediate button/chip wrappers are unclipped; RecyclerViews, scroll
     * ports and screen roots stay clipped so posters/episodes cannot bleed.
     */
    fun allowFocusScale(view: View) {
        var host = view.parent as? ViewGroup ?: return
        while (true) {
            host.clipToPadding = false
            if (isSectionClipHost(host)) {
                host.clipChildren = true
                return
            }
            host.clipChildren = false
            host = host.parent as? ViewGroup ?: return
        }
    }

    /** Keep artwork/text inside the rounded tile while the tile itself scales. */
    fun clipMediaTile(view: View) {
        view.clipToOutline = true
        (view as? ViewGroup)?.let {
            it.clipChildren = true
            it.clipToPadding = true
        }
    }

    private fun isSectionClipHost(host: ViewGroup): Boolean {
        if (host is RecyclerView ||
            host is HorizontalScrollView ||
            host is ScrollView ||
            host is NestedScrollView
        ) {
            return true
        }
        return when (host.id) {
            R.id.episodeList,
            R.id.rowList,
            R.id.rows,
            R.id.heroContainer,
            R.id.searchResults,
            R.id.searchKeyboard,
            R.id.seasonTabs,
            R.id.profileList,
            R.id.homeRoot,
            R.id.detailRoot,
            R.id.playerRoot,
            R.id.playerChrome,
            -> true
            else -> false
        }
    }

    /** Reusable so adapters can drive focus motion without extra listeners. */
    fun animateFocus(v: View, hasFocus: Boolean, focusedScale: Float = 1.06f) {
        allowFocusScale(v)
        val scale = if (hasFocus) focusedScale else 1f
        val elevation = if (hasFocus) 18f else 0f
        v.animate().cancel()
        // Focus moves are the highest-frequency interaction on TV — anything
        // slower than ~160ms reads as input lag when scrubbing along a row.
        v.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(if (hasFocus) 140 else 180)
            .setInterpolator(glide)
            .withLayer()
            .start()
    }

    fun pulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(120)
            .setInterpolator(glide)
            .withLayer()
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(glide)
                    .withLayer()
                    .start()
            }
            .start()
    }

    /** Staggered entrance for freshly bound rows/cards. */
    fun enter(view: View, index: Int, distanceDp: Float = 12f) {
        val d = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = distanceDp * d
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceIn(0, 8) * 28).toLong())
            .setDuration(360)
            .setInterpolator(glide)
            .withLayer()
            .start()
    }

    /** Crossfade an image/backdrop swap without a hard cut. */
    fun crossfade(view: View, apply: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .alpha(0.42f)
            .setDuration(110)
            .setInterpolator(easeIn)
            .withEndAction {
                apply()
                view.animate()
                    .alpha(1f)
                    .setDuration(280)
                    .setInterpolator(glide)
                    .start()
            }
            .start()
    }
}

/** No-op: Fire TV already has navigation click sounds. */
object UiSound {
    fun click(context: Context, prefs: UserPrefs? = null) = Unit
    fun success(context: Context, prefs: UserPrefs? = null) = Unit
    fun release() = Unit
}
