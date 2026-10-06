package com.streamvault.tv.ui.util

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Fades its whole content to transparent along the bottom edge, so artwork
 * dissolves into whatever lies behind it instead of ending on a painted scrim.
 */
class FadeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    init {
        setWillNotDraw(false)
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((FADE_DP * resources.displayMetrics.density).toInt())
    }

    override fun getBottomFadingEdgeStrength(): Float = 1f
    override fun getTopFadingEdgeStrength(): Float = 0f

    private companion object {
        const val FADE_DP = 120
    }
}
