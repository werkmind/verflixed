package com.streamvault.tv.ui.brand

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.streamvault.tv.R

/**
 * Plays the Verflixed startup sting. Kept separate from the drawing so splash and
 * player pre-roll stay in sync with the same audio beats.
 */
class BrandSting(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(volume: Float = 1f) {
        stop()
        runCatching {
            player = MediaPlayer.create(context, R.raw.splash_tudum)?.also { mp ->
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener { stop() }
                mp.start()
            }
        }
    }

    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }
}
