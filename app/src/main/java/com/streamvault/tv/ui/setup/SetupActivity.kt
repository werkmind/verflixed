package com.streamvault.tv.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.streamvault.tv.ui.util.ScaledAppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.databinding.ActivitySetupBinding
import com.streamvault.tv.ui.home.HomeActivity
import kotlinx.coroutines.launch

class SetupActivity : ScaledAppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = (application as VerflixedApp).container.prefs
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        if (prefs.setupDone && !force) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputBaseUrl.setText(prefs.seriesBaseUrl.ifBlank { UserPrefs.DEFAULT_SERIES_BASE })
        binding.inputMoviesBaseUrl.setText(prefs.moviesBaseUrl.ifBlank { UserPrefs.DEFAULT_MOVIES_BASE })
        binding.btnContinue.setOnClickListener { submit() }
        binding.inputBaseUrl.requestFocus()
    }

    private fun submit() {
        val seriesUrl = UserPrefs.normalizeUrl(
            binding.inputBaseUrl.text?.toString().orEmpty()
                .ifBlank { UserPrefs.DEFAULT_SERIES_BASE },
        )
        val moviesUrl = UserPrefs.normalizeUrl(
            binding.inputMoviesBaseUrl.text?.toString().orEmpty()
                .ifBlank { UserPrefs.DEFAULT_MOVIES_BASE },
        )
        val app = application as VerflixedApp
        binding.progress.visibility = View.VISIBLE
        binding.btnContinue.isEnabled = false
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch {
            // Persist BOTH urls always (do not drop movies when validate fails).
            app.container.prefs.seriesBaseUrl = seriesUrl
            app.container.prefs.moviesBaseUrl = moviesUrl
            app.container.prefs.mediaKind = UserPrefs.KIND_SERIES
            app.container.prefs.markSetupDone()

            val seriesOk = runCatching { app.container.catalog.validateBaseUrl(seriesUrl).getOrThrow() }.isSuccess
            val moviesOk = runCatching { app.container.catalog.validateBaseUrl(moviesUrl).getOrThrow() }.isSuccess

            binding.progress.visibility = View.GONE
            binding.btnContinue.isEnabled = true

            if (!seriesOk && !moviesOk) {
                val msg = getString(com.streamvault.tv.R.string.setup_error_unreachable)
                showError(msg)
                Toast.makeText(this@SetupActivity, msg, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!moviesOk) {
                Toast.makeText(
                    this@SetupActivity,
                    "Filme-URL gespeichert (Check warnte) – App startet trotzdem.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            startActivity(Intent(this@SetupActivity, HomeActivity::class.java))
            finish()
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
