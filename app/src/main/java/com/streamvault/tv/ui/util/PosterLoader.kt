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
 * - Browse/Search: **no Glide disk cache** (and no Room image/meta cache upstream)
 * - Library/Detail: normal disk cache OK (favorites persist metadata)
 */
object PosterLoader {
    fun loadSeries(view: ImageView, url: String?, browseMode: Boolean = false, rounded: Int = 16) {
        val src = SiteImages.preferJpeg(url)
        val opts = browseOptions(browseMode).centerCrop()
        val req = Glide.with(view)
            .load(src)
            .apply(opts)
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
        if (rounded > 0) {
            req.transform(CenterCrop(), RoundedCorners(rounded)).into(view)
        } else {
            req.into(view)
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
            .transform(CenterCrop(), RoundedCorners(14))
            .into(view)
    }

    private fun browseOptions(browseMode: Boolean): RequestOptions {
        return if (browseMode) {
            // No disk cache in Browse — always fetch current site art (JPEG/high-res).
            // Memory cache stays on so D-pad scrolling does not flicker.
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(false)
        } else {
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .skipMemoryCache(false)
        }
    }
}
