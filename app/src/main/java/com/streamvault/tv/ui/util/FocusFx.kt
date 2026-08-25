package com.streamvault.tv.ui.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
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
        view.setTag(R.id.tag_focus_scale, focusedScale)
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animateFocus(v, hasFocus, focusedScale)
        }
    }

    /**
     * Scale may overflow a shelf (Apple TV). Only the vertical home list and
     * screen roots clip, so a focused card cannot cover the sidebar/hero after
     * you scroll Favoriten, but it can grow over its neighbours in the row.
     */
    fun allowFocusScale(view: View) {
        if (view.getTag(R.id.tag_focus_scale_done) == true) return
        view.setTag(R.id.tag_focus_scale_done, true)
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
        view.clipToOutline = false
        (view as? ViewGroup)?.let {
            it.clipChildren = true
            it.clipToPadding = false
        }
    }

    fun clipStillTop(view: View, radiusDp: Float = 8f) {
        val r = radiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                if (v.width == 0 || v.height == 0) return
                outline.setRoundRect(0, 0, v.width, (v.height + r).toInt(), r)
            }
        }
        view.clipToOutline = true
    }

    private fun isSectionClipHost(host: ViewGroup): Boolean {
        return when (host.id) {
            R.id.rows,
            R.id.homeRoot,
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
        val elevation = if (hasFocus) 22f else 0f
        v.animate().cancel()
        // Focus moves are the highest-frequency interaction on TV — anything
        // slower than ~160ms reads as input lag when scrubbing along a row.
        v.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(if (hasFocus) 140 else 160)
            .setInterpolator(glide)
            .withLayer()
            .start()
    }

    /** Press: scale(0.96) in 100ms, then settle. TV OK / click only. */
    fun bindPress(view: View) {
        view.setOnKeyListener { v, keyCode, event ->
            val press = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!press) return@setOnKeyListener false
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                v.animate().cancel()
                v.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(100)
                    .setInterpolator(glide)
                    .withLayer()
                    .start()
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                v.animate().cancel()
                val focusedScale = (v.getTag(R.id.tag_focus_scale) as? Float) ?: 1.06f
                val scale = if (v.isFocused) focusedScale else 1f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(160)
                    .setInterpolator(glide)
                    .withLayer()
                    .start()
            }
            false
        }
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
            .setStartDelay((index.coerceIn(0, 5) * 24).toLong())
            .setDuration(280)
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
