package com.streamvault.tv.ui.brand

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cinematic 3D brand sting: perspective-projected extruded V, floor reflection,
 * camera dolly — original Verflixed mark, Prime-like *pacing* only.
 *
 * Phases (normalized progress):
 *  0.00–0.50  camera flies in from depth, logo rotates into place
 *  0.48–0.66  specular sweep (TA)
 *  0.62–0.92  wordmark rises after the DUMM
 *  0.86–1.00  settle + bloom hold
 */
class VerflixedIntroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val quad = Path()
    private val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val easeInOut = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private var animator: ValueAnimator? = null
    private var progress = 0f

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
        val cy = h / 2f - markSize * if (compact) 0.14f else 0.20f

        val flyIn = easeOut.getInterpolation(seg(0f, 0.50f))
        val settle = easeOut.getInterpolation(seg(0.82f, 1f))
        val depth = 1f - flyIn
        val rotY = 0.98f * depth + 0.20f
        val rotX = 0.42f * depth + 0.11f
        val camZ = 3.55f - 1.35f * flyIn
        val focal = markSize * 1.22f
        val scale = 0.78f + 0.26f * flyIn - 0.03f * settle * sin(settle * Math.PI.toFloat())
        val markAlpha = min(1f, seg(0.02f, 0.28f) * 1.25f)

        drawFloor(canvas, cx, cy, markSize, flyIn, rotY)
        drawDust(canvas, cx, cy, markSize, flyIn, rotY, rotX, camZ, focal)
        drawBackglow(canvas, cx, cy, markSize, flyIn)

        canvas.withSave {
            translate(cx, cy)
            scale(scale, scale)
            drawSolidV(this, markSize, rotX, rotY, camZ, focal, markAlpha, reflect = false)
            drawSpecularSweep(this, markSize, rotX, rotY, camZ, focal)
        }

        drawWordmark(canvas, cx, cy, markSize)
    }

    private fun drawAtmosphere(canvas: Canvas, w: Float, h: Float) {
        val k = easeOut.getInterpolation(seg(0f, 0.48f))
        if (k <= 0f) return
        bloomPaint.shader = RadialGradient(
            w / 2f, h * 0.40f, h * 1.15f,
            intArrayOf(ATMO_CORE, ATMO_MID, ATMO_EDGE),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = (220 * k).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, bloomPaint)

        // Volumetric shafts — subtle, behind the mark.
        val shaft = easeInOut.getInterpolation(seg(0.08f, 0.55f))
        if (shaft > 0f) {
            bloomPaint.shader = LinearGradient(
                w * 0.28f, 0f, w * 0.72f, h,
                intArrayOf(Color.TRANSPARENT, SHAFT, Color.TRANSPARENT),
                floatArrayOf(0.2f, 0.5f, 0.8f),
                Shader.TileMode.CLAMP,
            )
            bloomPaint.alpha = (38 * shaft).toInt()
            canvas.drawRect(w * 0.22f, 0f, w * 0.78f, h * 0.72f, bloomPaint)
        }

        bloomPaint.shader = LinearGradient(
            0f, h * 0.58f, 0f, h * 0.96f,
            intArrayOf(Color.TRANSPARENT, HORIZON, Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = (110 * k).toInt()
        canvas.drawRect(0f, h * 0.52f, w, h, bloomPaint)
    }

    private fun drawFloor(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        markSize: Float,
        flyIn: Float,
        rotY: Float,
    ) {
        val a = (70 * flyIn).toInt()
        if (a <= 0) return
        val rw = markSize * (1.85f + 0.25f * sin(rotY))
        val rh = markSize * 0.28f
        bloomPaint.shader = RadialGradient(
            cx, cy + markSize * 0.92f, rw,
            intArrayOf(FLOOR, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = a
        canvas.drawOval(
            cx - rw,
            cy + markSize * 0.78f - rh,
            cx + rw,
            cy + markSize * 0.78f + rh,
            bloomPaint,
        )
    }

    private fun drawDust(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        markSize: Float,
        flyIn: Float,
        rotX: Float,
        rotY: Float,
        camZ: Float,
        focal: Float,
    ) {
        val a = (90 * flyIn * (0.4f + 0.6f * seg(0.15f, 0.7f))).toInt()
        if (a <= 4) return
        dustPaint.shader = null
        dustPaint.color = Color.WHITE
        for (i in DUST.indices step 3) {
            val p = project(
                rotate(V3(DUST[i] * markSize, DUST[i + 1] * markSize, DUST[i + 2] * markSize), rotX, rotY),
                focal,
                camZ,
            )
            val twinkle = 0.45f + 0.55f * abs(sin(progress * 6f + i))
            dustPaint.alpha = (a * twinkle).toInt().coerceIn(0, 160)
            canvas.drawCircle(cx + p.x, cy + p.y, 1.4f + 1.1f * twinkle, dustPaint)
        }
    }

    private fun drawBackglow(canvas: Canvas, cx: Float, cy: Float, markSize: Float, flyIn: Float) {
        val glowAlpha = (130 * flyIn * (0.5f + 0.5f * easeInOut.getInterpolation(seg(0.28f, 0.68f)))).toInt()
        if (glowAlpha <= 0) return
        val r = markSize * (2.7f + 0.4f * flyIn)
        bloomPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(GLOW_INNER, GLOW_MID, Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomPaint.alpha = glowAlpha.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, bloomPaint)
    }

    private fun drawSolidV(
        canvas: Canvas,
        size: Float,
        rotX: Float,
        rotY: Float,
        camZ: Float,
        focal: Float,
        alpha: Float,
        reflect: Boolean,
    ) {
        val half = size * 0.34f
        val faces = buildFaces(size, half)
        val lit = ArrayList<LitFace>(faces.size)
        for (face in faces) {
            val r0 = rotate(face.a, rotX, rotY)
            val r1 = rotate(face.b, rotX, rotY)
            val r2 = rotate(face.c, rotX, rotY)
            val r3 = rotate(face.d, rotX, rotY)
            val n = normal(r0, r1, r2)
            if (n.z <= 0.02f && !reflect) continue
            val shade = (0.18f + 0.82f * max0(dot(n, LIGHT))).coerceIn(0f, 1f)
            val z = (r0.z + r1.z + r2.z + r3.z) * 0.25f
            lit.add(
                LitFace(
                    project(r0, focal, camZ),
                    project(r1, focal, camZ),
                    project(r2, focal, camZ),
                    project(r3, focal, camZ),
                    z,
                    shade,
                    face.kind,
                ),
            )
        }
        lit.sortBy { it.z }
        val fade = if (reflect) 0.22f else 1f
        for (f in lit) {
            val c = shadeColor(f.kind, f.shade)
            faceFill.shader = null
            faceFill.color = c
            faceFill.alpha = (Color.alpha(c) * alpha * fade).toInt().coerceIn(0, 255)
            quad.reset()
            val ySign = if (reflect) -1f else 1f
            val yOff = if (reflect) size * 1.55f else 0f
            quad.moveTo(f.p0.x, yOff + f.p0.y * ySign)
            quad.lineTo(f.p1.x, yOff + f.p1.y * ySign)
            quad.lineTo(f.p2.x, yOff + f.p2.y * ySign)
            quad.lineTo(f.p3.x, yOff + f.p3.y * ySign)
            quad.close()
            canvas.drawPath(quad, faceFill)
        }
        if (!reflect && alpha > 0.4f) {
            canvas.withSave {
                translate(0f, size * 0.02f)
                drawSolidV(this, size, rotX, rotY, camZ + 0.15f, focal, alpha * 0.18f, reflect = true)
            }
        }
    }

    private fun drawSpecularSweep(
        canvas: Canvas,
        size: Float,
        rotX: Float,
        rotY: Float,
        camZ: Float,
        focal: Float,
    ) {
        val k = seg(0.46f, 0.68f)
        if (k <= 0f || k >= 1f) return
        val eased = easeInOut.getInterpolation(k)
        val ring = buildOutline(size)
        val zFront = size * 0.34f
        quad.reset()
        var first = true
        for (p in ring) {
            val pr = project(rotate(V3(p.x, p.y, zFront), rotX, rotY), focal, camZ)
            if (first) {
                quad.moveTo(pr.x, pr.y)
                first = false
            } else {
                quad.lineTo(pr.x, pr.y)
            }
        }
        quad.close()
        val bandW = size * 0.9f
        val x = -size * 1.8f + eased * size * 3.6f
        canvas.withSave {
            clipPath(quad)
            sweepPaint.shader = LinearGradient(
                x - bandW, 0f, x + bandW, 0f,
                intArrayOf(Color.TRANSPARENT, SWEEP_HOT, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            sweepPaint.alpha = (200 * sin(k * Math.PI.toFloat())).toInt().coerceIn(0, 255)
            canvas.drawRect(x - bandW, -size * 1.8f, x + bandW, size * 1.8f, sweepPaint)
        }
    }

    private fun drawWordmark(canvas: Canvas, cx: Float, cy: Float, markSize: Float) {
        val letters = wordmark
        if (letters.isEmpty()) return
        val textSize = markSize * if (compact) 0.32f else 0.38f
        wordPaint.textSize = textSize
        val tracking = textSize * 0.26f
        val widths = FloatArray(letters.length) { wordPaint.measureText(letters, it, it + 1) }
        val total = widths.sum() + tracking * (letters.length - 1)
        var x = cx - total / 2f
        val baseline = cy + markSize * 1.22f

        for (i in letters.indices) {
            val start = 0.60f + (i / letters.length.toFloat()) * 0.26f
            val k = easeOut.getInterpolation(seg(start, start + 0.18f))
            if (k > 0f) {
                wordPaint.color = Color.WHITE
                wordPaint.alpha = (255 * min(1f, k * 1.25f)).toInt().coerceIn(0, 255)
                val rise = (1f - k) * textSize * 0.45f
                canvas.withSave {
                    translate(0f, rise)
                    scale(1f, 0.88f + 0.12f * k, x + widths[i] / 2f, baseline)
                    drawText(letters, i, i + 1, x, baseline, wordPaint)
                }
            }
            x += widths[i] + tracking
        }

        if (compact) return
        val tk = easeOut.getInterpolation(seg(0.78f, 0.96f))
        if (tk <= 0f) return
        taglinePaint.textSize = textSize * 0.46f
        taglinePaint.color = ACCENT_SOFT
        taglinePaint.alpha = (200 * tk).toInt().coerceIn(0, 255)
        canvas.drawText(tagline, cx, baseline + textSize * (1.12f - 0.22f * tk), taglinePaint)
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

    private data class V3(val x: Float, val y: Float, val z: Float)
    private data class V2(val x: Float, val y: Float)
    private data class Face(val a: V3, val b: V3, val c: V3, val d: V3, val kind: Int)
    private data class LitFace(
        val p0: V2,
        val p1: V2,
        val p2: V2,
        val p3: V2,
        val z: Float,
        val shade: Float,
        val kind: Int,
    )

    private fun rotate(p: V3, rotX: Float, rotY: Float): V3 {
        val cy = cos(rotY)
        val sy = sin(rotY)
        val x1 = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        val cx = cos(rotX)
        val sx = sin(rotX)
        return V3(x1, p.y * cx - z1 * sx, p.y * sx + z1 * cx)
    }

    private fun project(p: V3, focal: Float, camZ: Float): V2 {
        val d = focal / (camZ + p.z).coerceAtLeast(0.35f)
        return V2(p.x * d, p.y * d)
    }

    private fun normal(a: V3, b: V3, c: V3): V3 {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val uz = b.z - a.z
        val vx = c.x - a.x
        val vy = c.y - a.y
        val vz = c.z - a.z
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        return V3(nx / len, ny / len, nz / len)
    }

    private fun dot(a: V3, b: V3) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun max0(v: Float) = if (v > 0f) v else 0f

    private fun shadeColor(kind: Int, shade: Float): Int {
        val base = when (kind) {
            KIND_FRONT -> FACE_MID
            KIND_BACK -> SIDE_DEEP
            else -> SIDE_DARK
        }
        val hi = when (kind) {
            KIND_FRONT -> FACE_TOP
            KIND_BACK -> SIDE_DARK
            else -> SIDE_LIGHT
        }
        return lerpColor(base, hi, shade)
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

    private fun buildOutline(size: Float): Array<V3> {
        val halfW = size * 0.62f
        val top = -size * 0.60f
        val bottom = size * 0.62f
        val thick = size * 0.30f
        return arrayOf(
            V3(-halfW, top, 0f),
            V3(-halfW + thick, top, 0f),
            V3(0f, bottom - thick * 0.55f, 0f),
            V3(halfW - thick, top, 0f),
            V3(halfW, top, 0f),
            V3(thick * 0.30f, bottom, 0f),
            V3(-thick * 0.30f, bottom, 0f),
        )
    }

    private fun buildFaces(size: Float, half: Float): List<Face> {
        val ring = buildOutline(size)
        val frontZ = half
        val backZ = -half
        val faces = ArrayList<Face>(ring.size + 2)
        val n = ring.size
        fun at(i: Int, z: Float) = V3(ring[i].x, ring[i].y, z)
        faces.add(
            Face(at(0, frontZ), at(1, frontZ), at(2, frontZ), at(2, frontZ), KIND_FRONT),
        )
        // Front as two fans so the chevron stays solid.
        faces.add(Face(at(0, frontZ), at(2, frontZ), at(6, frontZ), at(6, frontZ), KIND_FRONT))
        faces.add(Face(at(2, frontZ), at(3, frontZ), at(4, frontZ), at(5, frontZ), KIND_FRONT))
        faces.add(Face(at(2, frontZ), at(5, frontZ), at(6, frontZ), at(6, frontZ), KIND_FRONT))
        faces.add(Face(at(0, backZ), at(6, backZ), at(2, backZ), at(1, backZ), KIND_BACK))
        faces.add(Face(at(2, backZ), at(6, backZ), at(5, backZ), at(4, backZ), KIND_BACK))
        faces.add(Face(at(2, backZ), at(4, backZ), at(3, backZ), at(3, backZ), KIND_BACK))
        for (i in 0 until n) {
            val j = (i + 1) % n
            faces.add(
                Face(
                    at(i, frontZ),
                    at(j, frontZ),
                    at(j, backZ),
                    at(i, backZ),
                    KIND_SIDE,
                ),
            )
        }
        return faces
    }

    companion object {
        const val DEFAULT_DURATION_MS = 3200L
        const val HOLD_AFTER_MS = 220L

        private const val KIND_FRONT = 0
        private const val KIND_SIDE = 1
        private const val KIND_BACK = 2

        private val LIGHT = V3(0.32f, -0.72f, 0.62f).let {
            val len = sqrt(it.x * it.x + it.y * it.y + it.z * it.z)
            V3(it.x / len, it.y / len, it.z / len)
        }

        private val DUST = floatArrayOf(
            -1.4f, -0.8f, 0.6f, 1.2f, -0.4f, -0.5f, -0.9f, 0.7f, 0.9f,
            1.5f, 0.3f, 0.2f, -1.1f, 0.1f, -0.8f, 0.4f, -1.1f, 0.7f,
            0.8f, 0.9f, -0.3f, -0.3f, -0.6f, 1.1f, 1.6f, -0.9f, 0.4f,
            -1.6f, 0.5f, 0.1f, 0.2f, 1.0f, -0.9f, -0.6f, -1.2f, -0.2f,
        )

        private const val FACE_TOP = 0xFFB7DBFF.toInt()
        private const val FACE_MID = 0xFF2F80FF.toInt()
        private const val SIDE_DARK = 0xFF081A40.toInt()
        private const val SIDE_DEEP = 0xFF030814.toInt()
        private const val SIDE_LIGHT = 0xFF1C62C8.toInt()
        private const val SWEEP_HOT = 0xFFF2F8FF.toInt()
        private const val GLOW_INNER = 0x772F80FF
        private const val GLOW_MID = 0x222F80FF
        private const val ACCENT_SOFT = 0xFF8FB6E8.toInt()
        private const val ATMO_CORE = 0xFF0C1834.toInt()
        private const val ATMO_MID = 0xFF060C1C.toInt()
        private const val ATMO_EDGE = 0xFF010308.toInt()
        private const val HORIZON = 0x332F80FF
        private const val FLOOR = 0x443A8CFF
        private const val SHAFT = 0x222F80FF
    }
}
