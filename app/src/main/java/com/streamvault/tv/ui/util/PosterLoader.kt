package com.streamvault.tv.ui.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.streamvault.tv.R
import com.streamvault.tv.data.catalog.SiteImages

/**
 * Smart image binding for Fire TV:
 * - Lazy via Glide on RecyclerView bind
 * - Prefer high-res JPEG site URLs (2x-desktop)
 * - Low-res thumbnail first (25%), then full res: covers appear immediately
 *   instead of popping in late while D-pad scrolling
 * - Short crossfade so swaps never flash white
 * - Disk + memory cache so D-pad scrolling stays smooth on Fire TV
 *
 * Corner rounding always happens inside the bitmap transform so cards never need
 * padding or a light-coloured container to fake the radius.
 */
object PosterLoader {
    /** Matches the radius of bg_poster_card / bg_poster_surface. */
    private const val POSTER_RADIUS_DP = 8

    /** Crossfade with placeholder kept underneath — no white flash on swap. */
    private val crossFade = DrawableTransitionOptions.withCrossFade(
        com.bumptech.glide.request.transition.DrawableCrossFadeFactory.Builder(160)
            .setCrossFadeEnabled(false)
            .build()
    )

    fun loadSeries(view: ImageView, url: String?, browseMode: Boolean = false, roundedDp: Int = POSTER_RADIUS_DP) {
        val src = SiteImages.preferJpeg(url)
        val req = Glide.with(view)
            .load(src)
            .apply(browseOptions(browseMode))
            .thumbnail(0.25f)
            .transition(crossFade)
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
        val radiusPx = view.dp(roundedDp)
        if (radiusPx > 0) {
            req.transform(CenterCrop(), RoundedCorners(radiusPx)).into(view)
        } else {
            req.transform(CenterCrop()).into(view)
        }
    }

    fun loadHero(view: ImageView, url: String?, browseMode: Boolean = false) {
        val src = SiteImages.preferJpeg(url)
        Glide.with(view)
            .load(src)
            .apply(browseOptions(browseMode))
            .thumbnail(0.25f)
            .transition(crossFade)
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .centerCrop()
            .into(view)
    }

    fun loadEpisodeStill(view: ImageView, stillUrl: String?, seriesFallback: String?) {
        val src = SiteImages.preferJpeg(stillUrl) ?: SiteImages.preferJpeg(seriesFallback)
        Glide.with(view)
            .load(src)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .thumbnail(0.25f)
            .transition(crossFade)
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .transform(CenterCrop())
            .into(view)
    }

    /** Warm the disk/memory cache for covers about to scroll into view. */
    fun prefetch(view: ImageView, url: String?) {
        val src = SiteImages.preferJpeg(url) ?: return
        Glide.with(view).load(src).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).preload()
    }

    private fun ImageView.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun browseOptions(browseMode: Boolean): RequestOptions {
        return RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .skipMemoryCache(false)
    }
}
