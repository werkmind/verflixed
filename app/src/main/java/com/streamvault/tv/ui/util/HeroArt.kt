package com.streamvault.tv.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamvault.tv.data.catalog.SiteImages
import java.security.MessageDigest

/**
 * The billboard behind the home feed: one large image that stays put while
 * the shelves move over it.
 *
 * Its dissolve into the room light is baked into the bitmap once, off the main
 * thread, when the image loads. Drawing it afterwards is a single textured
 * quad, so neither scrolling nor focus moves ever redraw or re-mask it.
 */
object HeroArt {

    fun show(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(view).clear(view)
            return
        }
        if (view.getTag(view.id) == url) return
        view.setTag(view.id, url)
        Glide.with(view)
            .load(SiteImages.preferJpeg(url))
            .transform(Dissolve())
            .transition(DrawableTransitionOptions.withCrossFade(if (FocusFx.motionEnabled(view)) 380 else 0))
            .into(view)
    }

    /** Keeps the faces (top-weighted crop) and fades the lower part to transparent. */
    private class Dissolve : BitmapTransformation() {
        override fun transform(pool: BitmapPool, source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
            // Two thirds of the view size is plenty at ten feet and a third the memory.
            val w = (outWidth * 2 / 3).coerceAtLeast(1)
            val h = (outHeight * 2 / 3).coerceAtLeast(1)
            val scale = maxOf(w / source.width.toFloat(), h / source.height.toFloat())
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - source.width * scale) / 2f, (h - source.height * scale) * VERTICAL_BIAS)
            }
            val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
            result.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(result)
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val mask = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                shader = LinearGradient(
                    0f, h * FADE_FROM, 0f, h.toFloat(),
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
            return result
        }

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update(ID.toByteArray(Charsets.UTF_8))
        }

        override fun equals(other: Any?): Boolean = other is Dissolve
        override fun hashCode(): Int = ID.hashCode()
    }

    private const val ID = "com.streamvault.tv.ui.util.HeroArt.Dissolve.v1"
    private const val VERTICAL_BIAS = 0.12f
    /** The image is solid above this share of its height and gone at the bottom. */
    private const val FADE_FROM = 0.52f
}
