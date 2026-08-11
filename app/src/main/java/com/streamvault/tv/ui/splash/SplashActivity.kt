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
 * Netflix/Prime-style branded splash: black screen, animated Verflixed mark + soft tudum.
 * Seeds default catalog URLs so the app loads without forcing a TMDb/API key.
 */
class SplashActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val prefs = (application as VerflixedApp).container.prefs
        // Always ensure defaults are present — never block on API keys.
        if (prefs.seriesBaseUrl.isBlank()) {
            prefs.seriesBaseUrl = UserPrefs.DEFAULT_SERIES_BASE
        }
        if (prefs.moviesBaseUrl.isBlank()) {
            prefs.moviesBaseUrl = UserPrefs.DEFAULT_MOVIES_BASE
        }

        val logo = findViewById<View>(R.id.splashLogo)
        val title = findViewById<View>(R.id.splashTitle)
        val progress = findViewById<View>(R.id.splashProgress)

        logo.alpha = 0f
        logo.scaleX = 0.72f
        logo.scaleY = 0.72f
        title.alpha = 0f
        title.translationY = 18f
        progress.alpha = 0f

        playSplashSound()

        val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).setDuration(700)
        val logoScaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.72f, 1.06f, 1f).setDuration(1100)
        val logoScaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.72f, 1.06f, 1f).setDuration(1100)
        val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f).setDuration(650)
        val titleRise = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 18f, 0f).setDuration(650)
        val barFade = ObjectAnimator.ofFloat(progress, View.ALPHA, 0f, 1f).setDuration(400)
        titleFade.startDelay = 280
        titleRise.startDelay = 280
        barFade.startDelay = 520
        listOf(logoFade, logoScaleX, logoScaleY, titleFade, titleRise, barFade).forEach {
            it.interpolator = ease
        }
        AnimatorSet().apply {
            playTogether(logoFade, logoScaleX, logoScaleY, titleFade, titleRise, barFade)
            start()
        }

        lifecycleScope.launch {
            delay(1750)
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
                mp.setVolume(0.85f, 0.85f)
                mp.start()
            }
        }
    }

    private fun goNext(prefs: UserPrefs) {
        // First launch: apply defaults and skip the setup form unless user opens Settings later.
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
