package com.streamvault.tv.ui.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Skeleton container that draws a soft highlight band sweeping over its
 * children — the modern "shimmer" loading look. Runs only while attached and
 * only when the system allows animation (FocusFx.motionEnabled); otherwise it
 * is a static placeholder host.
 */
class ShimmerHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var bandWidth = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bandWidth = resources.displayMetrics.density * 180f
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    /** Starts/stops with effective visibility (covers GONE parents too). */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow && FocusFx.motionEnabled(this)) {
            startShimmer()
        } else {
            stopShimmer()
        }
        invalidate()
    }

    private fun startShimmer() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun stopShimmer() {
        animator?.cancel()
        animator = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val anim = animator ?: return
        if (width <= 0 || height <= 0) return
        val phase = anim.animatedValue as Float
        val cycle = width + bandWidth
        val x = phase % cycle - bandWidth
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = LinearGradient(
            x, 0f, x + bandWidth, height * 0.6f,
            intArrayOf(0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(x, 0f, x + bandWidth, height.toFloat(), paint)
        canvas.restoreToCount(save)
    }
}
