package com.streamvault.tv.ui.splash

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.ui.home.HomeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Netflix-style branded splash: black screen, animated V mark + deep cinematic boom.
 */
class SplashActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val prefs = (application as VerflixedApp).container.prefs
        if (prefs.seriesBaseUrl.isBlank()) {
            prefs.seriesBaseUrl = UserPrefs.DEFAULT_SERIES_BASE
        }
        if (prefs.moviesBaseUrl.isBlank()) {
            prefs.moviesBaseUrl = UserPrefs.DEFAULT_MOVIES_BASE
        }

        val logo = findViewById<AnimatedVLogoView>(R.id.splashLogo)
        val title = findViewById<View>(R.id.splashTitle)
        val progress = findViewById<View>(R.id.splashProgress)

        title.alpha = 0f
        title.translationY = 22f
        progress.alpha = 0f

        playSplashSound()
        logo.playIntro(1450L)

        val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f).setDuration(700)
        val titleRise = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 22f, 0f).setDuration(700)
        val barFade = ObjectAnimator.ofFloat(progress, View.ALPHA, 0f, 1f).setDuration(420)
        titleFade.startDelay = 520
        titleRise.startDelay = 520
        barFade.startDelay = 900
        listOf(titleFade, titleRise, barFade).forEach { it.interpolator = ease }
        AnimatorSet().apply {
            playTogether(titleFade, titleRise, barFade)
            start()
        }

        lifecycleScope.launch {
            delay(1950)
            goNext(prefs)
        }
    }

    private fun playSplashSound() {
        runCatching {
            player = MediaPlayer.create(this, R.raw.splash_tudum)?.also { mp ->
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // Louder, bass-forward cinematic hit
                mp.setVolume(1f, 1f)
                mp.start()
            }
        }
    }

    private fun goNext(prefs: UserPrefs) {
        if (!prefs.setupDone) {
            prefs.seriesBaseUrl = prefs.seriesBaseUrl.ifBlank { UserPrefs.DEFAULT_SERIES_BASE }
            prefs.moviesBaseUrl = prefs.moviesBaseUrl.ifBlank { UserPrefs.DEFAULT_MOVIES_BASE }
            prefs.markSetupDone()
        }
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        super.onDestroy()
    }
}
