package com.streamvault.tv.ui.util

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/** Applies the user zoom before inflation so every screen respects Settings. */
open class ScaledAppCompatActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }
}
