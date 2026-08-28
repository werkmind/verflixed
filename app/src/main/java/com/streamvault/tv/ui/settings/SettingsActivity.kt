package com.streamvault.tv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.streamvault.tv.ui.util.ScaledAppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.BuildConfig
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.databinding.ActivitySettingsBinding
import com.streamvault.tv.ui.setup.SetupActivity
import com.streamvault.tv.util.toVfMessage
import com.streamvault.tv.data.prefs.UserPrefs
import kotlinx.coroutines.launch

class SettingsActivity : ScaledAppCompatActivity() {
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
            val codes = arrayOf(
                com.streamvault.tv.data.catalog.StreamLanguage.DE,
                com.streamvault.tv.data.catalog.StreamLanguage.EN,
            )
            val labels = codes.map {
                com.streamvault.tv.data.catalog.StreamLanguage.label(it)
            }.toTypedArray()
            val current = codes.indexOf(
                com.streamvault.tv.data.catalog.StreamLanguage.normalize(
                    prefs.streamLanguage(prefs.activeProfileId)
                )
            ).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Verflixed_Dialog)
                .setTitle("Bevorzugter Ton für dieses Profil")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    val next = codes[which]
                    prefs.setStreamLanguage(prefs.activeProfileId, next)
                    paintLanguage()
                    lifecycleScope.launch {
                        // Clear stream cache so the language switch takes effect.
                        runCatching { app.container.catalog.clearCache() }
                    }
                    Toast.makeText(
                        this,
                        "Profil-Ton: ${labels[which]}",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
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

        bindScalePicker(prefs)

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

        binding.btnProfileSync.setOnClickListener { showProfileSyncDialog() }
    }

    private fun bindScalePicker(prefs: UserPrefs) {
        val labels = mapOf(
            75 to R.string.settings_scale_75,
            85 to R.string.settings_scale_85,
            100 to R.string.settings_scale_100,
            115 to R.string.settings_scale_115,
            130 to R.string.settings_scale_130,
        )
        val current = prefs.uiScalePercent
        val gap = (10 * resources.displayMetrics.density).toInt()
        UserPrefs.SCALE_STEPS.forEachIndexed { index, percent ->
            val chip = android.widget.Button(this, null, 0, R.style.SvButton_Ghost).apply {
                text = getString(labels.getValue(percent))
                isAllCaps = false
                minWidth = (148 * resources.displayMetrics.density).toInt()
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isSelected = percent == current
                setOnClickListener {
                    if (prefs.uiScalePercent == percent) return@setOnClickListener
                    prefs.uiScalePercent = percent
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_scale_applied, percent),
                        Toast.LENGTH_SHORT,
                    ).show()
                    recreate()
                }
            }
            com.streamvault.tv.ui.util.FocusFx.bindScale(chip, 1.04f)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (index > 0) lp.marginStart = gap
            binding.scaleRow.addView(chip, lp)
        }
    }

    private var syncServer: com.streamvault.tv.data.sync.ProfileSyncServer? = null

    /**
     * "Cloud"-Profile ohne Cloud: startet einen lokalen HTTP-Server und zeigt
     * dessen Adresse als QR-Code. Handy scannt, lädt das Profil als JSON oder
     * spielt ein Backup zurück. Server lebt nur solange der Dialog offen ist.
     */
    private fun showProfileSyncDialog() {
        val app = application as VerflixedApp
        val server = com.streamvault.tv.data.sync.ProfileSyncServer(
            app.container.db, app.container.prefs, app.container.moshi,
        )
        val url = server.start()
        if (url == null) {
            Toast.makeText(this, getString(R.string.settings_profile_sync_error), Toast.LENGTH_LONG).show()
            return
        }
        syncServer = server

        val density = resources.displayMetrics.density
        val sizePx = (280 * density).toInt()
        val qr = renderQr(url, sizePx)

        val pad = (24 * density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(android.widget.ImageView(this).apply {
            setImageBitmap(qr)
            contentDescription = url
        })
        layout.addView(android.widget.TextView(this).apply {
            text = url
            textSize = 18f
            setTextColor(0xFFE8EDF7.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        })
        layout.addView(android.widget.TextView(this).apply {
            text = getString(R.string.settings_profile_sync_hint)
            textSize = 13f
            setTextColor(0xFF9FB0CC.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        })

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Verflixed_Dialog)
            .setTitle(getString(R.string.settings_profile_sync))
            .setView(layout)
            .setPositiveButton("Fertig", null)
            .setOnDismissListener {
                syncServer?.stop()
                syncServer = null
            }
            .show()
    }

    private fun renderQr(content: String, sizePx: Int): android.graphics.Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            content,
            com.google.zxing.BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] =
                    if (matrix.get(x, y)) 0xFF0B1220.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return android.graphics.Bitmap.createBitmap(pixels, sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncServer?.stop()
        syncServer = null
    }
}
