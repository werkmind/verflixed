package com.streamvault.tv.ui.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamvault.tv.data.catalog.SiteImages

/**
 * The room light behind the feed: the focused title's artwork, shrunk to a
 * few dozen pixels and stretched across the screen. The stretch is the blur,
 * so it is one small texture and costs nothing per frame on a Fire TV stick.
 * It sits behind every other layer and only ever supplies colour.
 */
object AmbientFx {
    private const val LIGHT = 0.62f

    fun update(ambient: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            ambient.animate().alpha(0f).setDuration(300L).start()
            return
        }
        if (ambient.getTag(com.streamvault.tv.R.id.tag_ambient_blur) == url) return
        ambient.setTag(com.streamvault.tv.R.id.tag_ambient_blur, url)
        Glide.with(ambient)
            .load(SiteImages.preferJpeg(url))
            .override(40, 22)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(450))
            .into(ambient)
        if (FocusFx.motionEnabled(ambient)) {
            ambient.animate().alpha(LIGHT).setDuration(500L).start()
        } else {
            ambient.alpha = LIGHT
        }
    }
}
