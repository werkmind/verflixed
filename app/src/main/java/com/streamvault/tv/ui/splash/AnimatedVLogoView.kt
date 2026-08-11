package com.streamvault.tv.ui.splash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.streamvault.tv.R

/**
 * Animated Verflixed "V" mark — stroke draws in, then fills with a soft pulse (Netflix-style).
 */
class AnimatedVLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.sv_accent)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.sv_accent)
    }

    private val vPath = Path()
    private val drawPath = Path()
    private val measure = PathMeasure()
    private var progress = 0f // 0..1 stroke
    private var fill = 0f // 0..1 fill alpha
    private var pulse = 0f
    private var animator: ValueAnimator? = null

    fun playIntro(durationMs: Long = 1400L) {
        animator?.cancel()
        progress = 0f
        fill = 0f
        pulse = 0f
        val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = ease
            addUpdateListener {
                val t = it.animatedValue as Float
                progress = (t / 0.62f).coerceIn(0f, 1f)
                fill = ((t - 0.48f) / 0.35f).coerceIn(0f, 1f)
                pulse = if (t > 0.7f) ((t - 0.7f) / 0.3f).coerceIn(0f, 1f) else 0f
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath(w, h)
        val stroke = (minOf(w, h) * 0.085f).coerceAtLeast(6f)
        strokePaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke * 1.8f
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                ContextCompat.getColor(context, R.color.sv_accent),
                0xFF7C5CFF.toInt(),
                ContextCompat.getColor(context, R.color.sv_accent),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun rebuildPath(w: Int, h: Int) {
        vPath.reset()
        val pad = minOf(w, h) * 0.12f
        val left = pad
        val right = w - pad
        val top = pad * 0.9f
        val bottom = h - pad
        val mid = w / 2f
        // Sharp cinematic V
        vPath.moveTo(left, top)
        vPath.lineTo(mid, bottom)
        vPath.lineTo(right, top)
        measure.setPath(vPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (measure.length <= 0f) return
        drawPath.reset()
        measure.getSegment(0f, measure.length * progress, drawPath, true)

        if (pulse > 0f) {
            glowPaint.alpha = (90 * (1f - pulse * 0.35f)).toInt()
            canvas.drawPath(drawPath, glowPaint)
        }
        if (fill > 0f) {
            // Soft fill under the V using a closed triangle clipped by progress
            fillPaint.alpha = (255 * fill).toInt()
            val closed = Path(vPath)
            closed.close()
            canvas.drawPath(closed, fillPaint)
        }
        strokePaint.alpha = 255
        canvas.drawPath(drawPath, strokePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
