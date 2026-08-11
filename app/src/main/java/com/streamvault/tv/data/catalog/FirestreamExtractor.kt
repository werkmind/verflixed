package com.streamvault.tv.data.catalog

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI

/**
 * Firestream (firestream.to) Filmpalast hoster.
 *
 * Flow (same as their Plyr page, without WebView / ads / captcha scam):
 * 1) GET /e/{slug} → `#token-blob` + `#video-data`
 * 2) POST /api/videos/{slug}/resolve { blob } → signedVideoUrl (mp4 / m3u8)
 *
 * Token is IP-bound: fetch + resolve must use the same client/IP (device network).
 * Never load the embed page in a WebView — it injects ad/captcha popups.
 */
class FirestreamExtractor(
    private val http: OkHttpClient,
) {
    fun isFirestreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("firestream.to") ||
            lower.contains("firestream.") ||
            (lower.contains("firestream") && (lower.contains("/e/") || lower.contains("/v/")))
    }

    fun extractDirect(embedUrl: String, referer: String? = null): String? {
        if (embedUrl.isBlank() || !isFirestreamUrl(embedUrl)) return null
        val slug = slugOf(embedUrl) ?: return null
        val pageUrl = normalizeEmbed(embedUrl, slug)

        val html = getHtml(pageUrl, referer) ?: return null
        // Adblock/VPN overlays are HTML-only; token-blob still present — ignore overlays.
        if (!html.contains("token-blob", ignoreCase = true)) return null

        val blob = TOKEN_BLOB.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (blob.isBlank()) return null

        // Prefer already-signed URL in video-data if present (rare SSR)
        VIDEO_DATA.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                val video = JSONObject(raw).optJSONObject("video") ?: return@runCatching null
                val signed = video.optString("signedVideoUrl").ifBlank {
                    video.optString("signedVideoSdUrl")
                }
                signed.takeIf { it.isNotBlank() && StreamKind.isDirectMediaUrl(it) }
            }.getOrNull()?.let { return it }
        }

        val payload = JSONObject().put("blob", blob).toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val origin = runCatching {
            val u = URI(pageUrl)
            "${u.scheme}://${u.authority}"
        }.getOrDefault("https://firestream.to")

        val req = Request.Builder()
            .url("$origin/api/videos/${slug}/resolve")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("Origin", origin)
            .header("Referer", pageUrl)
            .post(body)
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use null
                val data = JSONObject(text)
                val hls = data.optString("signedVideoUrl")
                    .ifBlank { data.optString("signedVideoSdUrl") }
                    .ifBlank { data.optString("streaming_url") }
                when {
                    hls.isBlank() -> null
                    StreamKind.isDirectMediaUrl(hls) -> hls
                    hls.startsWith("http", true) && (
                        hls.contains(".mp4", true) ||
                            hls.contains(".m3u8", true) ||
                            hls.contains("X-Amz-", true) ||
                            hls.contains("Signature=", true) ||
                            hls.contains("x-amz-", true)
                        ) -> hls
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun getHtml(url: String, referer: String?): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            .apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
            }
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun slugOf(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val m = Regex("""/(?:e|v|embed)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(path)
        return m?.groupValues?.get(1)
            ?: path.trim('/').substringAfterLast('/').takeIf { it.length in 4..64 }
    }

    private fun normalizeEmbed(url: String, slug: String): String {
        val origin = runCatching {
            val u = URI(url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault("https://firestream.to")
        return "$origin/e/$slug"
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val TOKEN_BLOB = Regex(
            """id=["']token-blob["'][^>]*>([^<]+)</script>""",
            RegexOption.IGNORE_CASE,
        )
        private val VIDEO_DATA = Regex(
            """id=["']video-data["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
    }
}
