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
import org.json.JSONObject

/**
 * Update checker.
 *
 * Primary source: GitHub Releases for werkmind/verflixed (same idea as LLX/WP
 * GitHub updaters — always-latest download URLs, no catbox expiry).
 *
 * Fallback: prefs / BuildConfig shortlink / site-local verflixed-update.json.
 */
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
            add(GITHUB_LATEST_MANIFEST)
            add(GITHUB_LATEST_API)
            prefs.baseUrl.takeIf { it.isNotBlank() }?.let { add("$it/verflixed-update.json") }
        }.distinct()

        var last: Throwable? = null
        for (url in candidates) {
            try {
                val manifest = if (url.contains("api.github.com")) {
                    parseGithubApi(url)
                } else {
                    val body = get(url) ?: continue
                    adapter.fromJson(body)
                } ?: continue
                if (manifest.versionCode > BuildConfig.VERSION_CODE &&
                    !manifest.apkUrl.isNullOrBlank()
                ) {
                    return@withContext manifest.withGithubFallbacks()
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

    private fun parseGithubApi(apiUrl: String): UpdateManifest? {
        val body = get(apiUrl) ?: return null
        val root = JSONObject(body)
        val assets = root.optJSONArray("assets") ?: return null
        var manifestUrl: String? = null
        var apkUrl: String? = null
        var webappUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val dl = a.optString("browser_download_url").ifBlank { null } ?: continue
            when {
                name.equals("verflixed-update.json", true) -> manifestUrl = dl
                name.equals("Verflixed-FireTV.apk", true) || name.endsWith(".apk", true) ->
                    if (apkUrl == null || name.equals("Verflixed-FireTV.apk", true)) apkUrl = dl
                name.equals("Verflixed-Webapp.zip", true) ||
                    (name.contains("Webapp", true) && name.endsWith(".zip", true)) ->
                    webappUrl = dl
            }
        }
        if (!manifestUrl.isNullOrBlank()) {
            val raw = get(manifestUrl) ?: return null
            val parsed = adapter.fromJson(raw) ?: return null
            return parsed.copy(
                apkUrl = parsed.apkUrl?.takeIf { it.isNotBlank() } ?: apkUrl,
                webappUrl = parsed.webappUrl?.takeIf { it.isNotBlank() } ?: webappUrl,
            )
        }
        val tag = root.optString("tag_name").ifBlank { root.optString("name") }
        val code = parseVersionCode(tag)
        if (code <= 0 || apkUrl.isNullOrBlank()) return null
        return UpdateManifest(
            versionCode = code,
            versionName = tag.removePrefix("v"),
            apkUrl = apkUrl,
            webappUrl = webappUrl,
            changelog = root.optString("body").takeIf { it.isNotBlank() },
        )
    }

    private fun parseVersionCode(name: String?): Int {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(name.orEmpty()) ?: return 0
        val (a, b, c) = m.destructured
        return a.toInt() * 10_000 + b.toInt() * 100 + c.toInt()
    }

    private fun UpdateManifest.withGithubFallbacks(): UpdateManifest = copy(
        apkUrl = apkUrl?.takeIf { it.isNotBlank() } ?: GITHUB_LATEST_APK,
        webappUrl = webappUrl?.takeIf { it.isNotBlank() } ?: GITHUB_LATEST_WEBAPP,
    )

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Verflixed-TV/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    companion object {
        const val GITHUB_REPO = "werkmind/verflixed"
        const val GITHUB_LATEST_MANIFEST =
            "https://github.com/$GITHUB_REPO/releases/latest/download/verflixed-update.json"
        const val GITHUB_LATEST_APK =
            "https://github.com/$GITHUB_REPO/releases/latest/download/Verflixed-FireTV.apk"
        const val GITHUB_LATEST_WEBAPP =
            "https://github.com/$GITHUB_REPO/releases/latest/download/Verflixed-Webapp.zip"
        const val GITHUB_LATEST_API =
            "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }
}

@JsonClass(generateAdapter = true)
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String? = null,
    val apkUrl: String? = null,
    val webappUrl: String? = null,
    val changelog: String? = null,
)
