package com.streamvault.tv.data.catalog

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Firestream (firestream.to) Filmpalast hoster.
 *
 * Prefer the same flow as their Plyr page (no WebView / ads / captcha):
 * 1) GET /e/{slug} → `#token-blob`
 * 2) POST /api/videos/{slug}/resolve { blob } → signedVideoUrl
 *
 * That resolve token is IP-bound and often fails on VPN/datacenter IPs
 * ("Token bound to different IP"). Fallback that works from any IP:
 * 3) GET /d/{slug} (download page) → `fr-cdn-*.firestream.to/.../video.mp4?...`
 *    Progressive MP4 with Range support — playable by ExoPlayer.
 */
class FirestreamExtractor(
    sharedHttp: OkHttpClient? = null,
) {
    /** Dedicated client: cookies + no zstd (CF may otherwise return undecodable bodies). */
    private val http: OkHttpClient = (sharedHttp?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isFirestreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("firestream.to") ||
            lower.contains("firestream.") ||
            (lower.contains("firestream") && (lower.contains("/e/") || lower.contains("/v/") || lower.contains("/d/")))
    }

    fun extractDirect(embedUrl: String, referer: String? = null): String? {
        if (embedUrl.isBlank() || !isFirestreamUrl(embedUrl)) return null
        val slug = slugOf(embedUrl) ?: return null
        val origin = originOf(embedUrl)

        // 1) Official resolve (best quality / streaming URL) — same client/IP for GET+POST.
        resolveViaToken(origin, slug, referer)?.let { return it }

        // 2) Download page CDN link — works even when resolve is IP-blocked / VPN.
        resolveViaDownloadPage(origin, slug, referer)?.let { return it }

        return null
    }

    private fun resolveViaToken(origin: String, slug: String, referer: String?): String? {
        val pageUrl = "$origin/e/$slug"
        val html = getHtml(pageUrl, referer) ?: return null
        if (!html.contains("token-blob", ignoreCase = true)) return null

        val blob = TOKEN_BLOB.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (blob.isBlank()) return null

        VIDEO_DATA.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                val video = JSONObject(raw).optJSONObject("video") ?: return@runCatching null
                pickMediaUrl(
                    video.optString("signedVideoUrl"),
                    video.optString("signedVideoSdUrl"),
                )
            }.getOrNull()?.let { return it }
        }

        val payload = JSONObject().put("blob", blob).toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("$origin/api/videos/${slug}/resolve")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Encoding", "identity")
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
                pickMediaUrl(
                    data.optString("signedVideoUrl"),
                    data.optString("signedVideoSdUrl"),
                    data.optString("streaming_url"),
                )
            }
        }.getOrNull()
    }

    private fun resolveViaDownloadPage(origin: String, slug: String, referer: String?): String? {
        val pageUrl = "$origin/d/$slug"
        val html = getHtml(pageUrl, referer ?: "$origin/e/$slug") ?: return null
        val matches = CDN_MP4.findAll(html).map { it.value }.distinct().toList()
        // Prefer non-download query (same file; cleaner for Exo progressive)
        val preferred = matches.firstOrNull { !it.contains("download", ignoreCase = true) }
            ?: matches.firstOrNull()?.let { stripDownloadParam(it) }
            ?: matches.firstOrNull()
        return preferred?.takeIf { looksLikeMedia(it) }
    }

    private fun pickMediaUrl(vararg candidates: String?): String? {
        for (raw in candidates) {
            val url = raw?.trim().orEmpty()
            if (url.isBlank()) continue
            if (StreamKind.isDirectMediaUrl(url) || looksLikeMedia(url)) return url
        }
        return null
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains("x-amz-") ||
            lower.contains("signature=") ||
            lower.contains("md5=") && lower.contains("expires=") ||
            (lower.contains("firestream") && (lower.contains("/encodings/") || lower.contains("/video")))
    }

    private fun stripDownloadParam(url: String): String {
        return url
            .replace(Regex("""([?&])download=?[^&]*"""), "$1")
            .replace("?&", "?")
            .trimEnd('?', '&')
    }

    private fun getHtml(url: String, referer: String?): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            // Avoid zstd/br — OkHttp may not decode CF zstd bodies on all devices.
            .header("Accept-Encoding", "identity")
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
        val m = Regex("""/(?:e|v|d|embed)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(path)
        return m?.groupValues?.get(1)
            ?: path.trim('/').substringAfterLast('/').takeIf { it.length in 4..64 }
    }

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault("https://firestream.to")

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
        private val CDN_MP4 = Regex(
            """https://[a-z0-9.-]*firestream\.to/[^"'\\\s<>]+video\.(?:mp4|m3u8)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE,
        )
    }
}
