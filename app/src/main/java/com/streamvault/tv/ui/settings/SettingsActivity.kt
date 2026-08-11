package com.streamvault.tv.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.BuildConfig
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.databinding.ActivitySettingsBinding
import com.streamvault.tv.ui.setup.SetupActivity
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as VerflixedApp
        val prefs = app.container.prefs
        binding.currentUrl.text =
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                "Serien-URL:\n${prefs.seriesBaseUrl.ifBlank { "—" }}\n\n" +
                "Filme-URL:\n${prefs.moviesBaseUrl.ifBlank { "—" }}\n\n" +
                "Aktiv: ${if (prefs.isMovies) "Filme" else "Serien"}\n\n" +
                "Ton: ${com.streamvault.tv.data.catalog.StreamLanguage.label(prefs.streamLanguage(prefs.activeProfileId))}"
        binding.inputUpdateUrl.setText(prefs.updateManifestUrl)

        binding.btnSaveUpdateUrl.setOnClickListener {
            prefs.updateManifestUrl = binding.inputUpdateUrl.text?.toString().orEmpty()
            Toast.makeText(this, "Update-URL gespeichert", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            lifecycleScope.launch {
                runCatching { app.container.updates.check() }
                    .onSuccess { m ->
                        if (m == null) {
                            Toast.makeText(this@SettingsActivity, "Kein Update", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Update ${m.versionName} gefunden",
                                Toast.LENGTH_LONG
                            ).show()
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.apkUrl)))
                        }
                    }
                    .onFailure {
                        Toast.makeText(this@SettingsActivity, it.toVfMessage(), Toast.LENGTH_LONG).show()
                    }
            }
        }

        binding.btnChangeUrl.setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_FORCE, true)
            )
            finish()
        }

        binding.btnClearCache.setOnClickListener {
            lifecycleScope.launch {
                app.container.catalog.clearCache()
                Toast.makeText(this@SettingsActivity, "Cache geleert", Toast.LENGTH_SHORT).show()
            }
        }

        fun paintSounds() {
            binding.btnToggleSounds.text = if (prefs.uiSoundsEnabled) {
                getString(R.string.settings_sounds_on)
            } else getString(R.string.settings_sounds_off)
        }
        paintSounds()
        binding.btnToggleSounds.setOnClickListener {
            prefs.uiSoundsEnabled = !prefs.uiSoundsEnabled
            paintSounds()
            Toast.makeText(
                this,
                if (prefs.uiSoundsEnabled) "UI-Sounds an" else "UI-Sounds aus",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun paintLanguage() {
            val code = prefs.streamLanguage(prefs.activeProfileId)
            binding.btnToggleLanguage.text =
                "Sprache: ${com.streamvault.tv.data.catalog.StreamLanguage.label(code)}"
        }
        paintLanguage()
        binding.btnToggleLanguage.setOnClickListener {
            val next = com.streamvault.tv.data.catalog.StreamLanguage.toggle(
                prefs.streamLanguage(prefs.activeProfileId)
            )
            prefs.setStreamLanguage(prefs.activeProfileId, next)
            paintLanguage()
            lifecycleScope.launch {
                // Clear stream cache for active profile so language switch takes effect.
                runCatching { app.container.catalog.clearCache() }
            }
            Toast.makeText(
                this,
                "Standard-Ton: ${com.streamvault.tv.data.catalog.StreamLanguage.label(next)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnProfiles.setOnClickListener {
            startActivity(Intent(this, com.streamvault.tv.ui.profile.ProfilesActivity::class.java))
        }
    }
}
