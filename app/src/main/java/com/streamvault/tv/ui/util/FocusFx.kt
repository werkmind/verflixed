package com.streamvault.tv.ui.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
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
    /** Subtle liquid overshoot for gaining focus (never for losing it). */
    private val springy = PathInterpolator(0.3f, 1.38f, 0.5f, 1f)

    // TV equivalent of prefers-reduced-motion: Fire OS "Reduce motion" and the
    // developer animator-scale both drive ANIMATOR_DURATION_SCALE to 0. When it
    // is 0, decorative motion is skipped and state changes snap instantly —
    // the static focus cues (ring, fill) always stay.
    private var motionCacheUntil = 0L
    private var motionEnabledCache = true

    fun motionEnabled(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now >= motionCacheUntil) {
            motionEnabledCache = runCatching {
                Settings.Global.getFloat(
                    view.context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                ) > 0f
            }.getOrDefault(true)
            motionCacheUntil = now + 5_000L
        }
        return motionEnabledCache
    }

    /** Shelf padding is sized for this much growth; larger requests are capped. */
    private const val MAX_FOCUS_SCALE = 1.06f

    fun bindScale(view: View, focusedScale: Float = 1.06f, prefs: UserPrefs? = null) {
        allowFocusScale(view)
        view.setTag(R.id.tag_focus_scale, focusedScale)
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animateFocus(v, hasFocus, focusedScale)
        }
    }

    /** The shelf's padding reserves room for focus; never unclip screen ancestors. */
    fun allowFocusScale(view: View) {
        val host = view.parent as? androidx.recyclerview.widget.RecyclerView ?: return
        // The halo paints outside the tile; the shelf's own row clips it.
        host.clipChildren = false
        host.clipToPadding = false
    }

    /** Rounds the artwork frame of a tile to its background's corners. */
    fun clipMediaTile(view: View) {
        view.clipToOutline = true
    }

    /**
     * Focus casts light: a focused tile gets a soft accent halo around its
     * artwork frame. Controls do not glow; their lit fill is the signal.
     */
    private fun glow(v: View, hasFocus: Boolean) {
        val frame = v.findViewById<View>(R.id.posterFrame)
            ?: v.findViewById<View>(R.id.episodeStillWrap)
            ?: return
        if (frame === v) return
        val halo = v.background as? HaloDrawable ?: run {
            if (!hasFocus) return
            val density = v.resources.displayMetrics.density
            HaloDrawable(
                frame = frame,
                color = ContextCompat.getColor(v.context, R.color.sv_glow),
                cornerPx = v.resources.getDimension(R.dimen.vf_radius_image),
                spreadPx = 14f * density,
            ).also { v.background = it }
        }
        halo.setLit(hasFocus, animate = motionEnabled(v))
    }

    /** Reusable so adapters can drive focus motion without extra listeners. */
    fun animateFocus(
        v: View,
        hasFocus: Boolean,
        focusedScale: Float = 1.06f,
        liquid: Boolean = false,
    ) {
        allowFocusScale(v)
        val scale = if (hasFocus) focusedScale.coerceAtMost(MAX_FOCUS_SCALE) else 1f
        // Lifts the focused view above its neighbours for draw order only.
        val elevation = if (hasFocus) 2f * v.resources.displayMetrics.density else 0f
        glow(v, hasFocus)
        v.animate().cancel()
        if (!motionEnabled(v)) {
            v.scaleX = scale
            v.scaleY = scale
            v.translationZ = elevation
            return
        }
        // Scale and translationZ are render-node properties: animating them
        // redraws nothing, so no offscreen layer is requested (a layer would
        // cost a texture per move and clip the halo to the tile's bounds).
        // Focus moves are the highest-frequency interaction on TV — anything
        // slower than ~160ms reads as input lag when scrubbing along a row.
        // `liquid` adds a slight overshoot on focus gain for hero CTAs and nav.
        v.animate()
            .setStartDelay(0)
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(if (hasFocus) 150 else 160)
            .setInterpolator(if (hasFocus && liquid) springy else glide)
            .start()
    }

    /** Liquid focus for CTAs and navigation chrome. */
    fun bindLiquid(view: View, focusedScale: Float = 1.06f, prefs: UserPrefs? = null) {
        allowFocusScale(view)
        view.setTag(R.id.tag_focus_scale, focusedScale)
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animateFocus(v, hasFocus, focusedScale, liquid = true)
        }
    }

    /** Press: scale(0.96) in 100ms, then settle. TV OK / click only. */
    fun bindPress(view: View) {
        view.setOnKeyListener { v, keyCode, event ->
            val press = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!press) return@setOnKeyListener false
            if (!motionEnabled(v)) return@setOnKeyListener false
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
                val scale = if (v.isFocused) focusedScale.coerceAtMost(MAX_FOCUS_SCALE) else 1f
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
        if (!motionEnabled(view)) return
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
        if (!motionEnabled(view)) {
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            return
        }
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
        if (!motionEnabled(view)) {
            apply()
            view.alpha = 1f
            return
        }
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
