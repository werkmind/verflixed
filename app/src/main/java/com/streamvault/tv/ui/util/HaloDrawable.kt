package com.streamvault.tv.ui.util

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The light a focused tile throws: a soft ring of the accent colour around
 * the tile's artwork frame. Set as the tile root's background, it paints
 * outside [frame] on every side, which an elevation shadow cannot do.
 * Built from stacked translucent outlines so it needs no blur support and
 * looks the same on every API level.
 */
class HaloDrawable(
    private val frame: View,
    private val color: Int,
    private val cornerPx: Float,
    private val spreadPx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var level = 0f
    private var animator: ValueAnimator? = null

    fun setLit(lit: Boolean, animate: Boolean) {
        val target = if (lit) 1f else 0f
        animator?.cancel()
        if (!animate) {
            level = target
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(level, target).apply {
            duration = if (lit) 180L else 140L
            addUpdateListener {
                level = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (level <= 0f || frame.width == 0) return
        val step = spreadPx / RINGS
        paint.strokeWidth = step + 1f
        paint.color = color
        for (i in 0 until RINGS) {
            // Quadratic falloff: dense at the frame, gone at the outer edge.
            val t = 1f - i / RINGS.toFloat()
            paint.alpha = (PEAK_ALPHA * t * t * level).toInt()
            val grow = step * (i + 0.5f)
            rect.set(
                frame.left - grow,
                frame.top - grow,
                frame.right + grow,
                frame.bottom + grow,
            )
            canvas.drawRoundRect(rect, cornerPx + grow, cornerPx + grow, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val RINGS = 14
        const val PEAK_ALPHA = 150
    }
}
