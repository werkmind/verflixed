package com.streamvault.tv.ui.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.streamvault.tv.R
import com.streamvault.tv.data.catalog.SiteImages

/**
 * Smart image binding for Fire TV:
 * - Lazy via Glide on RecyclerView bind
 * - Prefer high-res JPEG site URLs (2x-desktop)
 * - Disk + memory cache so D-pad scrolling stays smooth on Fire TV
 *
 * Corner rounding always happens inside the bitmap transform so cards never need
 * padding or a light-coloured container to fake the radius.
 */
object PosterLoader {
    /** Matches the radius of bg_poster_card / bg_poster_surface. */
    private const val POSTER_RADIUS_DP = 10

    fun loadSeries(view: ImageView, url: String?, browseMode: Boolean = false, roundedDp: Int = POSTER_RADIUS_DP) {
        val src = SiteImages.preferJpeg(url)
        val req = Glide.with(view)
            .load(src)
            .apply(browseOptions(browseMode))
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
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .transform(CenterCrop(), RoundedCorners(view.dp(POSTER_RADIUS_DP)))
            .into(view)
    }

    private fun ImageView.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun browseOptions(browseMode: Boolean): RequestOptions {
        return RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .skipMemoryCache(false)
    }
}
