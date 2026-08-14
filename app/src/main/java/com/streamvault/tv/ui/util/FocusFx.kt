package com.streamvault.tv.ui.util

import android.content.Context
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.streamvault.tv.data.prefs.UserPrefs

/**
 * TV focus motion: soft spring scale + lift. Tuned short (≤220ms) so D-pad
 * scrolling never feels laggy, with a slight overshoot so focus reads instantly
 * on large screens.
 */
object FocusFx {
    private val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val easeIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val spring = OvershootInterpolator(1.35f)

    fun bindScale(view: View, focusedScale: Float = 1.06f, prefs: UserPrefs? = null) {
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animateFocus(v, hasFocus, focusedScale)
        }
    }

    /** Reusable so adapters can drive focus motion without extra listeners. */
    fun animateFocus(v: View, hasFocus: Boolean, focusedScale: Float = 1.06f) {
        val scale = if (hasFocus) focusedScale else 1f
        val elevation = if (hasFocus) 14f else 0f
        v.animate().cancel()
        v.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(if (hasFocus) 190 else 130)
            .setInterpolator(if (hasFocus) spring else easeIn)
            .start()
        v.elevation = elevation
    }

    fun pulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(110)
            .setInterpolator(easeOut)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(easeIn)
                    .start()
            }
            .start()
    }

    /** Staggered entrance for freshly bound rows/cards. */
    fun enter(view: View, index: Int, distanceDp: Float = 14f) {
        val d = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = distanceDp * d
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceIn(0, 8) * 34).toLong())
            .setDuration(300)
            .setInterpolator(easeOut)
            .start()
    }

    /** Crossfade an image/backdrop swap without a hard cut. */
    fun crossfade(view: View, apply: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .alpha(0.35f)
            .setDuration(120)
            .setInterpolator(easeIn)
            .withEndAction {
                apply()
                view.animate()
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(easeOut)
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
