package com.streamvault.tv.ui.settings

import android.content.Intent
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
import com.streamvault.tv.data.prefs.UserPrefs
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
                "Profil-Ton: ${com.streamvault.tv.data.catalog.StreamLanguage.label(prefs.streamLanguage(prefs.activeProfileId))}"
        binding.inputUpdateUrl.setText(prefs.updateManifestUrl)

        binding.btnSaveUpdateUrl.setOnClickListener {
            prefs.updateManifestUrl = binding.inputUpdateUrl.text?.toString().orEmpty()
            Toast.makeText(this, "Update-URL gespeichert", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            val installer = com.streamvault.tv.data.update.ApkUpdateInstaller()
            lifecycleScope.launch {
                runCatching { app.container.updates.check() }
                    .onSuccess { m ->
                        if (m == null) {
                            Toast.makeText(this@SettingsActivity, "Kein Update", Toast.LENGTH_SHORT).show()
                            return@onSuccess
                        }
                        if (!installer.canInstallPackages(this@SettingsActivity)) {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.update_allow_unknown),
                                Toast.LENGTH_LONG,
                            ).show()
                            installer.openUnknownSourcesSettings(this@SettingsActivity)
                            return@onSuccess
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            "Update ${m.versionName} – lade APK…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        runCatching {
                            val apkUrl = m.apkUrl?.trim().orEmpty()
                            if (apkUrl.isBlank()) error("Keine APK-URL im Manifest")
                            installer.download(this@SettingsActivity, apkUrl)
                        }.onSuccess { result ->
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.update_install),
                                Toast.LENGTH_SHORT,
                            ).show()
                            installer.install(this@SettingsActivity, result.file)
                        }.onFailure {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Download fehlgeschlagen: ${it.message}",
                                Toast.LENGTH_LONG,
                            ).show()
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
                "Profil-Ton: ${com.streamvault.tv.data.catalog.StreamLanguage.label(code)}"
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
                "Profil-Ton: ${com.streamvault.tv.data.catalog.StreamLanguage.label(next)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun paintNav() {
            binding.btnToggleNav.text = if (prefs.isSidebarNav) {
                "Layout: Sidebar"
            } else {
                "Layout: Topbar"
            }
        }
        paintNav()
        binding.btnToggleNav.setOnClickListener {
            val next = if (prefs.isSidebarNav) UserPrefs.NAV_TOPBAR else UserPrefs.NAV_SIDEBAR
            prefs.setNavLayout(prefs.activeProfileId, next)
            paintNav()
            Toast.makeText(
                this,
                if (next == UserPrefs.NAV_SIDEBAR) "Sidebar aktiv (Profil)" else "Topbar aktiv (Profil)",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun paintLibraryView() {
            binding.btnToggleLibraryView.text = if (prefs.isLibraryCards) {
                "Bibliothek: Cards"
            } else {
                "Bibliothek: Kacheln"
            }
        }
        paintLibraryView()
        binding.btnToggleLibraryView.setOnClickListener {
            val next = if (prefs.isLibraryCards) UserPrefs.LIB_TILES else UserPrefs.LIB_CARDS
            prefs.setLibraryView(prefs.activeProfileId, next)
            paintLibraryView()
            Toast.makeText(
                this,
                if (next == UserPrefs.LIB_CARDS) "Cards-Ansicht (Profil)" else "Kachel-Ansicht (Profil)",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnProfiles.setOnClickListener {
            startActivity(Intent(this, com.streamvault.tv.ui.profile.ProfilesActivity::class.java))
        }
    }
}
