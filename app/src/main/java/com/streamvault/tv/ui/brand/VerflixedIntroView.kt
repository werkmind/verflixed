package com.streamvault.tv.ui.brand

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Cinematic brand sting: stylized "3D" Verflixed V (extruded side faces + specular
 * sweep) resolving into the wordmark — Prime-Video-like choreography, drawn fully
 * on canvas so it stays crisp on any TV resolution.
 *
 * Phases (normalized progress):
 *  0.00–0.42  V flies in from depth, rotating slightly, extrusion collapses
 *  0.34–0.62  light sweep across the faces (the "reveal" beat)
 *  0.46–0.80  wordmark letters rise letter-by-letter
 *  0.80–1.00  settle + soft bloom hold
 */
class VerflixedIntroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val vPath = Path()
    private val sidePath = Path()
    private val clipRect = RectF()

    private val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val easeInOut = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private var animator: ValueAnimator? = null
    private var progress = 0f

    /** Compact mode omits the tagline (used as player pre-roll). */
    var compact: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var wordmark: String = "VERFLIXED"
    var tagline: String = "Serien & Filme"

    private var onDone: (() -> Unit)? = null

    fun play(durationMs: Long = DEFAULT_DURATION_MS, onFinished: (() -> Unit)? = null) {
        onDone = onFinished
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                progress = it.animatedFraction
                invalidate()
            }
            addListener(
                onEnd = {
                    progress = 1f
                    invalidate()
                    onDone?.invoke()
                },
            )
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        onDone = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val markSize = min(w, h) * if (compact) 0.30f else 0.34f
        val cx = w / 2f
        // Leave room under the mark for the wordmark.
        val cy = h / 2f - markSize * if (compact) 0.16f else 0.24f

        val flyIn = easeOut.getInterpolation(seg(0f, 0.42f))
        val settle = easeOut.getInterpolation(seg(0.78f, 1f))
        val depth = 1f - flyIn
        // Overshoot then settle for a springy, premium landing.
        val scale = (0.62f + 0.44f * flyIn) - 0.06f * settle * sin(settle * Math.PI.toFloat())
        val rotY = -26f * depth
        val markAlpha = min(1f, seg(0.02f, 0.30f) * 1.2f)

        drawBackglow(canvas, cx, cy, markSize, flyIn)

        canvas.withSave {
            translate(cx, cy)
            // Fake perspective: horizontal squash + vertical shear as it turns in.
            scale(scale * (1f - abs(rotY) / 150f), scale)
            buildV(markSize)

            // Extruded side faces: offset copies fading with depth.
            val extrude = markSize * (0.16f * depth + 0.045f)
            val steps = 7
            for (i in steps downTo 1) {
                val k = i / steps.toFloat()
                sidePath.reset()
                sidePath.addPath(vPath, -extrude * k * 1.15f, extrude * k * 0.55f)
                sideFill.shader = LinearGradient(
                    -markSize, -markSize, markSize, markSize,
                    SIDE_DARK, SIDE_LIGHT, Shader.TileMode.CLAMP,
                )
                sideFill.alpha = (markAlpha * (52 + 90 * (1f - k))).toInt().coerceIn(0, 255)
                drawPath(sidePath, sideFill)
            }

            faceFill.shader = LinearGradient(
                -markSize * 0.7f, -markSize, markSize * 0.7f, markSize,
                intArrayOf(FACE_TOP, FACE_MID, FACE_BOTTOM),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            faceFill.alpha = (markAlpha * 255).toInt().coerceIn(0, 255)
            drawPath(vPath, faceFill)

            drawSpecularSweep(this, markSize)
        }

        drawWordmark(canvas, cx, cy, markSize, h)
    }

    private fun drawBackglow(canvas: Canvas, cx: Float, cy: Float, markSize: Float, flyIn: Float) {
        val glowAlpha = (110 * flyIn * (0.55f + 0.45f * easeInOut.getInterpolation(seg(0.30f, 0.7f)))).toInt()
        if (glowAlpha <= 0) return
        val r = markSize * (2.5f + 0.5f * flyIn)
        bloomPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(GLOW_INNER, GLOW_MID, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = glowAlpha.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, bloomPaint)
    }

    /** Chevron "V" with a thick stroke look, built as an outline for extrusion. */
    private fun buildV(size: Float) {
        val halfW = size * 0.62f
        val top = -size * 0.60f
        val bottom = size * 0.62f
        val thick = size * 0.30f

        vPath.reset()
        vPath.moveTo(-halfW, top)
        vPath.lineTo(-halfW + thick, top)
        vPath.lineTo(0f, bottom - thick * 0.55f)
        vPath.lineTo(halfW - thick, top)
        vPath.lineTo(halfW, top)
        vPath.lineTo(thick * 0.30f, bottom)
        vPath.lineTo(-thick * 0.30f, bottom)
        vPath.close()
    }

    private fun drawSpecularSweep(canvas: Canvas, size: Float) {
        val k = seg(0.32f, 0.66f)
        if (k <= 0f || k >= 1f) return
        val eased = easeInOut.getInterpolation(k)
        val bandW = size * 0.85f
        val x = -size * 1.7f + eased * size * 3.4f
        canvas.withSave {
            clipPath(vPath)
            clipRect.set(x - bandW, -size * 1.6f, x + bandW, size * 1.6f)
            sweepPaint.shader = LinearGradient(
                x - bandW, 0f, x + bandW, 0f,
                intArrayOf(Color.TRANSPARENT, SWEEP_HOT, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            val fade = sin(k * Math.PI.toFloat())
            sweepPaint.alpha = (190 * fade).toInt().coerceIn(0, 255)
            drawRect(clipRect, sweepPaint)
        }
    }

    private fun drawWordmark(canvas: Canvas, cx: Float, cy: Float, markSize: Float, h: Float) {
        val letters = wordmark
        if (letters.isEmpty()) return
        val textSize = markSize * if (compact) 0.34f else 0.40f
        wordPaint.textSize = textSize
        val tracking = textSize * 0.26f
        val widths = FloatArray(letters.length) { wordPaint.measureText(letters, it, it + 1) }
        val total = widths.sum() + tracking * (letters.length - 1)
        var x = cx - total / 2f
        val baseline = cy + markSize * 1.16f

        for (i in letters.indices) {
            val start = 0.44f + (i / letters.length.toFloat()) * 0.30f
            val k = easeOut.getInterpolation(seg(start, start + 0.20f))
            if (k > 0f) {
                wordPaint.color = Color.WHITE
                wordPaint.alpha = (255 * min(1f, k * 1.25f)).toInt().coerceIn(0, 255)
                val rise = (1f - k) * textSize * 0.55f
                canvas.withSave {
                    translate(0f, rise)
                    scale(1f, 0.86f + 0.14f * k, x + widths[i] / 2f, baseline)
                    drawText(letters, i, i + 1, x, baseline, wordPaint)
                }
            }
            x += widths[i] + tracking
        }

        if (compact) return
        val tk = easeOut.getInterpolation(seg(0.70f, 0.95f))
        if (tk <= 0f) return
        taglinePaint.textSize = textSize * 0.46f
        taglinePaint.color = ACCENT_SOFT
        taglinePaint.alpha = (200 * tk).toInt().coerceIn(0, 255)
        canvas.drawText(
            tagline,
            cx,
            baseline + textSize * (1.15f - 0.25f * tk),
            taglinePaint,
        )
    }

    private fun seg(from: Float, to: Float): Float {
        if (to <= from) return if (progress >= to) 1f else 0f
        return ((progress - from) / (to - from)).coerceIn(0f, 1f)
    }

    private fun ValueAnimator.addListener(onEnd: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
        })
    }

    companion object {
        const val DEFAULT_DURATION_MS = 2450L

        private const val FACE_TOP = 0xFF8FC4FF.toInt()
        private const val FACE_MID = 0xFF2F80FF.toInt()
        private const val FACE_BOTTOM = 0xFF1348A8.toInt()
        private const val SIDE_DARK = 0xFF0A1E44.toInt()
        private const val SIDE_LIGHT = 0xFF1B5FC4.toInt()
        private const val SWEEP_HOT = 0xFFE8F3FF.toInt()
        private const val GLOW_INNER = 0x662F80FF
        private const val GLOW_MID = 0x222F80FF
        private const val ACCENT_SOFT = 0xFF8FB6E8.toInt()

        /** Keep the mark visible a beat after the sting resolves. */
        const val HOLD_AFTER_MS = 260L
    }
}
