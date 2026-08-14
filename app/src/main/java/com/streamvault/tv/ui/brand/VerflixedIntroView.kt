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

        drawAtmosphere(canvas, w, h)

        val markSize = min(w, h) * if (compact) 0.30f else 0.34f
        val cx = w / 2f
        // Leave room under the mark for the wordmark.
        val cy = h / 2f - markSize * if (compact) 0.16f else 0.24f

        val flyIn = easeOut.getInterpolation(seg(0f, 0.42f))
        val settle = easeOut.getInterpolation(seg(0.78f, 1f))
        val depth = 1f - flyIn
        // Overshoot then settle for a springy, premium landing.
        val scale = (0.62f + 0.44f * flyIn) - 0.06f * settle * sin(settle * Math.PI.toFloat())
        val rotY = -30f * depth
        val markAlpha = min(1f, seg(0.02f, 0.30f) * 1.2f)

        drawBackglow(canvas, cx, cy, markSize, flyIn)

        canvas.withSave {
            translate(cx, cy)
            // Camera: horizontal squash while turning in, plus a slight downward
            // tilt (skew) that keeps a hint of perspective even after settling.
            scale(scale * (1f - abs(rotY) / 150f), scale)
            skew(0f, -0.045f - 0.10f * depth)
            buildV(markSize)

            // Extruded body: layered slices swept down-right like a lit solid.
            // Depth stays visible after the landing (never collapses fully flat).
            val extrude = markSize * (0.22f * depth + 0.085f)
            val steps = 12
            for (i in steps downTo 1) {
                val k = i / steps.toFloat()
                sidePath.reset()
                sidePath.addPath(vPath, extrude * k * 0.95f, extrude * k * 0.75f)
                // Back slices darken toward the vanishing side; front slices catch light.
                sideFill.shader = LinearGradient(
                    -markSize, -markSize, markSize, markSize,
                    lerpColor(SIDE_DARK, SIDE_DEEP, k),
                    lerpColor(SIDE_LIGHT, SIDE_DARK, k),
                    Shader.TileMode.CLAMP,
                )
                sideFill.alpha = (markAlpha * (70 + 110 * (1f - k))).toInt().coerceIn(0, 255)
                drawPath(sidePath, sideFill)
            }

            // Bottom contact shadow so the mark sits IN the scene, not on it.
            sidePath.reset()
            sidePath.addPath(vPath, extrude * 0.5f, extrude * 1.35f)
            sideFill.shader = null
            sideFill.color = Color.BLACK
            sideFill.alpha = (markAlpha * 90).toInt()
            drawPath(sidePath, sideFill)

            faceFill.shader = LinearGradient(
                -markSize * 0.7f, -markSize, markSize * 0.7f, markSize,
                intArrayOf(FACE_TOP, FACE_MID, FACE_BOTTOM),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            faceFill.alpha = (markAlpha * 255).toInt().coerceIn(0, 255)
            drawPath(vPath, faceFill)

            // Top bevel: thin bright edge along the upper rim (Prime-like rim light).
            sidePath.reset()
            sidePath.addPath(vPath, -extrude * 0.05f, -extrude * 0.10f)
            sweepPaint.shader = LinearGradient(
                0f, -markSize, 0f, 0f,
                RIM_LIGHT, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            sweepPaint.alpha = (markAlpha * 120).toInt()
            withSave {
                clipPath(vPath)
                drawPath(sidePath, sweepPaint)
            }

            drawSpecularSweep(this, markSize)
        }

        drawWordmark(canvas, cx, cy, markSize, h)
    }

    /** Prime-style stage: deep navy-to-black radial wash + soft horizon band. */
    private fun drawAtmosphere(canvas: Canvas, w: Float, h: Float) {
        val k = easeOut.getInterpolation(seg(0f, 0.5f))
        if (k <= 0f) return
        bloomPaint.shader = RadialGradient(
            w / 2f, h * 0.42f, h * 1.05f,
            intArrayOf(ATMO_CORE, ATMO_MID, ATMO_EDGE),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = (200 * k).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, bloomPaint)
        // Horizon glow under the mark — the "floor" the logo lands on.
        bloomPaint.shader = LinearGradient(
            0f, h * 0.62f, 0f, h * 0.92f,
            intArrayOf(Color.TRANSPARENT, HORIZON, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = (90 * k).toInt()
        canvas.drawRect(0f, h * 0.55f, w, h, bloomPaint)
    }

    private fun drawBackglow(canvas: Canvas, cx: Float, cy: Float, markSize: Float, flyIn: Float) {
        val glowAlpha = (120 * flyIn * (0.55f + 0.45f * easeInOut.getInterpolation(seg(0.30f, 0.7f)))).toInt()
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

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + ((b - a) * k)).toInt().coerceIn(0, 255)
        return Color.argb(
            ch(Color.alpha(from), Color.alpha(to)),
            ch(Color.red(from), Color.red(to)),
            ch(Color.green(from), Color.green(to)),
            ch(Color.blue(from), Color.blue(to)),
        )
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

        private const val FACE_TOP = 0xFF9FD0FF.toInt()
        private const val FACE_MID = 0xFF2F80FF.toInt()
        private const val FACE_BOTTOM = 0xFF10408F.toInt()
        private const val SIDE_DARK = 0xFF0A1E44.toInt()
        private const val SIDE_DEEP = 0xFF040B1C.toInt()
        private const val SIDE_LIGHT = 0xFF1B5FC4.toInt()
        private const val RIM_LIGHT = 0xFFDCEBFF.toInt()
        private const val SWEEP_HOT = 0xFFE8F3FF.toInt()
        private const val GLOW_INNER = 0x662F80FF
        private const val GLOW_MID = 0x222F80FF
        private const val ACCENT_SOFT = 0xFF8FB6E8.toInt()
        private const val ATMO_CORE = 0xFF0B1730.toInt()
        private const val ATMO_MID = 0xFF060C1C.toInt()
        private const val ATMO_EDGE = 0xFF020409.toInt()
        private const val HORIZON = 0x2E2F80FF

        /** Keep the mark visible a beat after the sting resolves. */
        const val HOLD_AFTER_MS = 260L
    }
}
