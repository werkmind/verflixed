package com.streamvault.tv.data.update

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.streamvault.tv.BuildConfig
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.util.VfCodes
import com.streamvault.tv.util.VfException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class UpdateChecker(
    private val http: OkHttpClient,
    moshi: Moshi,
    private val prefs: UserPrefs
) {
    private val adapter = moshi.adapter(UpdateManifest::class.java)

    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            prefs.updateManifestUrl.takeIf { it.isNotBlank() }?.let { add(it) }
            BuildConfig.UPDATE_MANIFEST_URL.takeIf { it.isNotBlank() }?.let { add(it) }
            prefs.baseUrl.takeIf { it.isNotBlank() }?.let { add("$it/verflixed-update.json") }
        }.distinct()

        var last: Throwable? = null
        for (url in candidates) {
            try {
                val body = get(url) ?: continue
                val manifest = adapter.fromJson(body) ?: continue
                if (manifest.versionCode > BuildConfig.VERSION_CODE && !manifest.apkUrl.isNullOrBlank()) {
                    return@withContext manifest
                }
            } catch (t: Throwable) {
                last = t
            }
        }
        if (last != null) {
            throw VfException.of(VfCodes.UPDATE_CHECK, "Update-Check fehlgeschlagen", last)
        }
        null
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }
}

@JsonClass(generateAdapter = true)
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String? = null,
    val apkUrl: String? = null,
    val changelog: String? = null
)
