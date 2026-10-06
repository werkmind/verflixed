package com.streamvault.tv.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Fills the target like a centre crop, but keeps the upper part of the image
 * when height has to go. Backdrops carry faces near the top; a centre crop in
 * a wide, short banner cuts them off.
 */
class TopCrop : BitmapTransformation() {
    override fun transform(pool: BitmapPool, source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (source.width == outWidth && source.height == outHeight) return source
        val scale = maxOf(outWidth / source.width.toFloat(), outHeight / source.height.toFloat())
        val dx = (outWidth - source.width * scale) / 2f
        val dy = (outHeight - source.height * scale) * VERTICAL_BIAS
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val result = pool.get(outWidth, outHeight, source.config ?: Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean = other is TopCrop
    override fun hashCode(): Int = ID.hashCode()

    private companion object {
        const val ID = "com.streamvault.tv.ui.util.TopCrop.v1"
        /** 0 = top edge kept, 0.5 = centre crop. */
        const val VERTICAL_BIAS = 0.15f
    }
}
