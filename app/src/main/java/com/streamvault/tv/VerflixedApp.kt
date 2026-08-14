package com.streamvault.tv

import android.app.Application
import com.streamvault.tv.data.AppContainer
import com.streamvault.tv.data.adblock.AdBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VerflixedApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Survives Activity destroy — favorite link caching, background enrich. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            runCatching { AdBlock.warm(this@VerflixedApp) }
            runCatching { container.profiles.ensureDefaultProfile() }
        }
    }
}
