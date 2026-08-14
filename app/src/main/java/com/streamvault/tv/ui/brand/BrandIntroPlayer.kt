package com.streamvault.tv.ui.brand

import android.net.Uri
import android.view.View
import android.widget.VideoView
import com.streamvault.tv.R

/**
 * Plays the rendered 3D brand opener (res/raw/brand_intro.mp4, audio baked in).
 * If the device cannot decode it, callers fall back to the canvas intro plus
 * BrandSting so the splash never turns into a black screen.
 */
class BrandIntroPlayer(private val view: VideoView) {

    private var started = false

    /**
     * @param onFallback invoked when the video cannot play — draw the canvas intro instead
     * @param onFinished invoked once playback completes
     */
    fun play(onFallback: () -> Unit, onFinished: (() -> Unit)? = null) {
        val uri = Uri.parse("android.resource://${view.context.packageName}/${R.raw.brand_intro}")
        view.setOnErrorListener { _, _, _ ->
            view.visibility = View.GONE
            if (!started) onFallback()
            true
        }
        view.setOnPreparedListener { mp ->
            started = true
            mp.setVolume(1f, 1f)
            view.visibility = View.VISIBLE
        }
        view.setOnCompletionListener { onFinished?.invoke() }
        runCatching {
            view.setVideoURI(uri)
            view.start()
        }.onFailure {
            view.visibility = View.GONE
            onFallback()
        }
    }

    fun stop() {
        runCatching {
            view.stopPlayback()
        }
        view.visibility = View.GONE
    }
}
