package com.streamvault.tv.ui.util

import android.app.Activity
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.streamvault.tv.R
import com.streamvault.tv.data.catalog.SiteImages

/**
 * Midnight Cinema ambient stage: a tiny, heavily dimmed copy of the focused
 * item's artwork fills the whole screen behind the feed. The 48x27 source is
 * upscaled to 1080p by the GPU - that stretch IS the blur, essentially free,
 * no RenderScript/RenderEffect needed (Fire TV sticks run API 25+).
 * The room light follows what you browse: emotion + depth on every focus move.
 */
object AmbientFx {

    /** Resolves the home ambient ImageView and updates it. */
    fun updateForActivity(host: Activity?, url: String?) {
        if (host == null) return
        update(host.findViewById(R.id.ambientImage), url)
    }

    fun update(ambient: ImageView?, url: String?) {
        if (ambient == null) return
        if (url.isNullOrBlank()) {
            fade(ambient, 0f)
            return
        }
        if (ambient.visibility != ImageView.VISIBLE) {
            ambient.visibility = ImageView.VISIBLE
            ambient.alpha = 0f
        }
        val src = SiteImages.preferJpeg(url)
        Glide.with(ambient.context)
            .load(src)
            .apply(RequestOptions().override(96, 54).centerCrop())
            .transition(DrawableTransitionOptions.withCrossFade(350))
            .into(ambient)
        // Ceiling for the room light: never brighter than a whisper so type
        // stays readable even over bright artwork.
        fade(ambient, 0.22f)
    }

    private fun fade(ambient: ImageView, target: Float) {
        ambient.animate().cancel()
        if (!FocusFx.motionEnabled(ambient)) {
            ambient.alpha = target
            return
        }
        ambient.animate()
            .alpha(target)
            .setDuration(350L)
            .start()
    }
}
