package com.streamvault.tv.ui.splash

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.ui.brand.BrandSting
import com.streamvault.tv.ui.brand.VerflixedIntroView
import com.streamvault.tv.ui.home.HomeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branded splash: animated 3D-style V mark + startup sting, then Home.
 * The catalog warms up during the sting so Home lands on content, not a spinner.
 */
class SplashActivity : AppCompatActivity() {
    private val sting by lazy { BrandSting(this) }
    private var navigated = false

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

        val intro = findViewById<VerflixedIntroView>(R.id.splashIntro)
        val progress = findViewById<View>(R.id.splashProgress)

        sting.play()
        intro.play(VerflixedIntroView.DEFAULT_DURATION_MS)

        progress.animate()
            .alpha(1f)
            .setStartDelay(1150)
            .setDuration(420)
            .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
            .start()

        warmCatalog()

        lifecycleScope.launch {
            delay(VerflixedIntroView.DEFAULT_DURATION_MS + VerflixedIntroView.HOLD_AFTER_MS)
            goNext(prefs)
        }
    }

    /** Prefetch rows while the sting plays so Home feels instant. */
    private fun warmCatalog() {
        val app = application as VerflixedApp
        app.appScope.launch {
            runCatching { app.container.catalog.getLibraryRows() }
        }
    }

    private fun goNext(prefs: UserPrefs) {
        if (navigated) return
        navigated = true
        if (!prefs.setupDone) {
            prefs.seriesBaseUrl = prefs.seriesBaseUrl.ifBlank { UserPrefs.DEFAULT_SERIES_BASE }
            prefs.moviesBaseUrl = prefs.moviesBaseUrl.ifBlank { UserPrefs.DEFAULT_MOVIES_BASE }
            prefs.markSetupDone()
        }
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(R.anim.vf_fade_in, R.anim.vf_fade_out)
        finish()
    }

    override fun onDestroy() {
        sting.stop()
        super.onDestroy()
    }
}
