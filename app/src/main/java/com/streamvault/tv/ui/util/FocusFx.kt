package com.streamvault.tv.ui.util

import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import com.streamvault.tv.data.prefs.UserPrefs

/**
 * Apple-TV-like focus motion: soft spring scale + lift, no custom beeps
 * (Fire TV already plays system nav sounds).
 */
object FocusFx {
    private val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val easeIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun bindScale(view: View, focusedScale: Float = 1.08f, prefs: UserPrefs? = null) {
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            val scale = if (hasFocus) focusedScale else 1f
            val elevation = if (hasFocus) 18f else 0f
            v.animate().cancel()
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(if (hasFocus) 240 else 160)
                .setInterpolator(if (hasFocus) easeOut else easeIn)
                .start()
            v.elevation = elevation
            // No UiSound — Fire OS handles focus audio.
        }
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
}

/** No-op: Fire TV already has navigation click sounds. */
object UiSound {
    fun click(context: Context, prefs: UserPrefs? = null) = Unit
    fun success(context: Context, prefs: UserPrefs? = null) = Unit
    fun release() = Unit
}
