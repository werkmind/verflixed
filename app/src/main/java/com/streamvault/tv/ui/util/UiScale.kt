package com.streamvault.tv.ui.util

import android.content.Context
import android.content.res.Configuration
import com.streamvault.tv.data.prefs.UserPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TV zoom: scales densityDpi so the whole chrome (posters, type, paddings)
 * grows or shrinks together. Font-scale is left alone so text stays proportional.
 */
object UiScale {
    fun wrap(base: Context): Context {
        val percent = readPercent(base)
        if (abs(percent - 100) < 1) return base
        val factor = (percent / 100f).coerceIn(0.70f, 1.45f)
        val src = base.resources.configuration
        val config = Configuration(src)
        val baseDpi = if (src.densityDpi > 0) {
            src.densityDpi
        } else {
            base.resources.displayMetrics.densityDpi
        }
        config.densityDpi = (baseDpi * factor).roundToInt().coerceIn(120, 640)
        return base.createConfigurationContext(config)
    }

    private fun readPercent(context: Context): Int {
        val raw = context.applicationContext
            .getSharedPreferences("verflixed_prefs", Context.MODE_PRIVATE)
            .getInt("ui_scale_percent", UserPrefs.SCALE_DEFAULT)
        return UserPrefs.normalizeScale(raw)
    }
}
