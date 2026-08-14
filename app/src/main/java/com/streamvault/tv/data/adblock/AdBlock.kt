package com.streamvault.tv.data.adblock

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-based blocker compiled from EasyList, EasyPrivacy and uBlock Origin
 * network filters. Captcha / stream hosts stay allow-listed.
 */
object AdBlock {
    private val hosts = AtomicReference<Set<String>?>(null)
    private val empty = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    fun warm(context: Context) {
        if (hosts.get() != null) return
        runCatching {
            context.assets.open("adblock/hosts.txt").bufferedReader().useLines { lines ->
                hosts.set(
                    lines
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet(),
                )
            }
        }
    }

    fun shouldBlock(url: String): Boolean {
        val set = hosts.get() ?: return false
        val host = hostOf(url) ?: return false
        if (allow(host)) return false
        var h = host
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot <= 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    fun intercept(url: String): WebResourceResponse? =
        if (shouldBlock(url)) empty else null

    private fun allow(host: String): Boolean {
        val allow = arrayOf(
            "cloudflare.com", "challenges.cloudflare.com", "cloudflareinsights.com",
            "recaptcha.net", "google.com", "gstatic.com", "hcaptcha.com",
            "serienstream.cx", "serienstream.to", "s.to", "aniworld.to",
            "voe.sx", "filmpalast.to", "themoviedb.org", "tmdb.org",
            "wikidata.org", "wikipedia.org", "tvmaze.com",
        )
        return allow.any { host == it || host.endsWith(".$it") }
    }

    private fun hostOf(url: String): String? {
        val start = url.indexOf("://")
        if (start < 0) return null
        val rest = url.substring(start + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPort = if (end < 0) rest else rest.substring(0, end)
        return hostPort.substringBefore(':').lowercase().trim('.')
    }
}
