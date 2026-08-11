package com.streamvault.tv.data.catalog

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Vidara / Vidnest / similar hosts:
 * POST /api/stream { filecode, device } → streaming_url (HLS).
 */
class VidaraExtractor(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    fun extractHls(embedUrl: String, referer: String? = null): String? {
        if (embedUrl.isBlank()) return null
        val filecode = runCatching {
            URI(embedUrl).path.split('/').filter { it.isNotBlank() }.lastOrNull()
        }.getOrNull().orEmpty().ifBlank {
            embedUrl.trimEnd('/').substringAfterLast('/')
        }
        if (filecode.isBlank()) return null

        val origin = runCatching {
            val u = URI(embedUrl)
            "${u.scheme}://${u.authority}"
        }.getOrDefault("https://vidaraa.cc")

        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "desktop")
            .toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("$origin/api/stream")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", origin)
            .header("Referer", embedUrl)
            .apply {
                if (!referer.isNullOrBlank()) header("X-Film-Referer", referer)
            }
            .post(body)
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                val data = JSONObject(text)
                val hls = data.optString("streaming_url")
                    .ifBlank { data.optString("source") }
                hls.takeIf { it.isNotBlank() && StreamKind.isDirectMediaUrl(it) }
            }
        }.getOrNull()
    }

    fun isVidaraUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("vidara") || lower.contains("vidnest")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
