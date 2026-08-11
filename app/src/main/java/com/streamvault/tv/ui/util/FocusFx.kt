package com.streamvault.tv.ui.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.streamvault.tv.data.prefs.UserPrefs

object FocusFx {
    private val ease = DecelerateInterpolator()
    private val overshoot = OvershootInterpolator(1.4f)
    private val main = Handler(Looper.getMainLooper())

    fun bindScale(view: View, focusedScale: Float = 1.12f, prefs: UserPrefs? = null) {
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            val scale = if (hasFocus) focusedScale else 1f
            val elevation = if (hasFocus) 22f else 0f
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(if (hasFocus) 200 else 130)
                .setInterpolator(if (hasFocus) overshoot else ease)
                .start()
            v.elevation = elevation
            if (hasFocus) UiSound.click(v.context, prefs)
        }
    }

    fun pulse(view: View) {
        view.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(90)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }
}

object UiSound {
    @Volatile private var tone: ToneGenerator? = null

    private fun tg(): ToneGenerator {
        return tone ?: synchronized(this) {
            tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 35).also { tone = it }
        }
    }

    fun click(context: Context, prefs: UserPrefs? = null) {
        val enabled = prefs?.uiSoundsEnabled
            ?: context.getSharedPreferences("verflixed_prefs", Context.MODE_PRIVATE)
                .getBoolean("ui_sounds", true)
        if (!enabled) return
        runCatching { tg().startTone(ToneGenerator.TONE_PROP_BEEP, 28) }
    }

    fun success(context: Context, prefs: UserPrefs? = null) {
        val enabled = prefs?.uiSoundsEnabled
            ?: context.getSharedPreferences("verflixed_prefs", Context.MODE_PRIVATE)
                .getBoolean("ui_sounds", true)
        if (!enabled) return
        runCatching { tg().startTone(ToneGenerator.TONE_PROP_ACK, 60) }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
