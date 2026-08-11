package com.streamvault.tv.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.databinding.ActivitySetupBinding
import com.streamvault.tv.ui.home.HomeActivity
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = (application as VerflixedApp).container.prefs
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        if (prefs.isConfigured && !force) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (prefs.seriesBaseUrl.isNotBlank()) {
            binding.inputBaseUrl.setText(prefs.seriesBaseUrl)
        }
        if (prefs.moviesBaseUrl.isNotBlank()) {
            binding.inputMoviesBaseUrl.setText(prefs.moviesBaseUrl)
        }
        if (prefs.tmdbApiKey.isNotBlank()) {
            binding.inputTmdbKey.setText(prefs.tmdbApiKey)
        }

        binding.btnContinue.setOnClickListener { submit() }
        binding.inputBaseUrl.requestFocus()
    }

    private fun submit() {
        val seriesUrl = binding.inputBaseUrl.text?.toString().orEmpty().trim()
        val moviesUrl = binding.inputMoviesBaseUrl.text?.toString().orEmpty().trim()
        if (seriesUrl.isBlank() && moviesUrl.isBlank()) {
            showError(getString(com.streamvault.tv.R.string.setup_error_empty))
            return
        }
        val app = application as VerflixedApp
        binding.progress.visibility = View.VISIBLE
        binding.btnContinue.isEnabled = false
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch {
            var lastError: Throwable? = null
            var ok = false

            if (seriesUrl.isNotBlank()) {
                val result = app.container.catalog.validateBaseUrl(seriesUrl)
                result.onSuccess {
                    app.container.prefs.seriesBaseUrl = seriesUrl
                    ok = true
                }.onFailure { lastError = it }
            }
            if (moviesUrl.isNotBlank()) {
                val result = app.container.catalog.validateBaseUrl(moviesUrl)
                result.onSuccess {
                    app.container.prefs.moviesBaseUrl = moviesUrl
                    ok = true
                }.onFailure { lastError = it }
            }

            binding.progress.visibility = View.GONE
            binding.btnContinue.isEnabled = true

            if (ok) {
                // Prefer series when both set; otherwise use whatever is available.
                app.container.prefs.mediaKind = when {
                    seriesUrl.isNotBlank() -> UserPrefs.KIND_SERIES
                    else -> UserPrefs.KIND_MOVIE
                }
                app.container.prefs.tmdbApiKey = binding.inputTmdbKey.text?.toString().orEmpty()
                startActivity(Intent(this@SetupActivity, HomeActivity::class.java))
                finish()
            } else {
                val msg = lastError?.message
                    ?: getString(com.streamvault.tv.R.string.setup_error_unreachable)
                showError(msg)
                Toast.makeText(this@SetupActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showError(msg: String) {
        binding.errorText.text = msg
        binding.errorText.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_FORCE = "force_setup"
    }
}
