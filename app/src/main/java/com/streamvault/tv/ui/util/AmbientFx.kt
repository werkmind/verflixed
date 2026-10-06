package com.streamvault.tv.ui.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamvault.tv.data.catalog.SiteImages

/**
 * The room light behind the feed: the focused title's artwork, shrunk to a
 * few dozen pixels and stretched across the screen. The stretch is the blur,
 * so it costs nothing on old Fire TV sticks; newer devices smooth it further.
 * It sits behind every other layer and only ever supplies colour.
 */
object AmbientFx {
    private const val LIGHT = 0.62f

    fun update(ambient: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            ambient.animate().alpha(0f).setDuration(300L).start()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ambient.getTag(com.streamvault.tv.R.id.tag_ambient_blur) == null) {
            ambient.setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP))
            ambient.setTag(com.streamvault.tv.R.id.tag_ambient_blur, true)
        }
        Glide.with(ambient)
            .load(SiteImages.preferJpeg(url))
            .override(40, 22)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(700))
            .into(ambient)
        if (FocusFx.motionEnabled(ambient)) {
            ambient.animate().alpha(LIGHT).setDuration(500L).start()
        } else {
            ambient.alpha = LIGHT
        }
    }
}
