package com.streamvault.tv.data.catalog

import android.net.Uri

object StreamKind {
    fun isM3u8(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true) ||
            url.contains("/hls/", ignoreCase = true) && url.contains("playlist", ignoreCase = true)

    fun isMp4(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url)
        return path.contains(".mp4", ignoreCase = true)
    }

    fun isDash(url: String): Boolean =
        url.contains(".mpd", ignoreCase = true)

    /** Direct media playable by ExoPlayer (HLS / progressive / dash). */
    fun isDirectMediaUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("blob:", true) || url.startsWith("data:", true)) return false
        if (isM3u8(url) || isMp4(url) || isDash(url)) return true
        // VOE / common CDN playlist endpoints without .m3u8 extension
        val lower = url.lowercase()
        if (lower.contains("master.txt") && lower.contains("voe")) return true
        if (lower.contains("/hls/") && (lower.contains("index") || lower.contains("master"))) return true
        return false
    }

    /**
     * Site play "blob" / redirect token links (e.g. /r?t=…).
     * These are iframe-only gates — never load them as top-level WebView URLs.
     */
    fun isPlayBlobUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.contains("/r?t=", ignoreCase = true)) return true
        if (u.contains("/r?t%", ignoreCase = true)) return true
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        val q = uri.query.orEmpty()
        if (path == "/r" && q.startsWith("t=")) return true
        if (path.endsWith("/r") && q.contains("t=")) return true
        return false
    }

    /**
     * VOE embed/watch pages – played via embedded WebView (VOE's own player),
     * then HLS is handed off to ExoPlayer when the official player requests it.
     */
    fun isVoePlayerUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isBlank()) return false
        if (host == "voe.sx" || host.endsWith(".voe.sx")) return true
        if (host.contains("voe")) return true
        // Known VOE mirror / rotate domains (Cloudstream extractors + live mirrors)
        val mirrors = listOf(
            "donaldlineelse.com",
            "charlestoughrace.com",
            "tubelessceliolymph.com",
            "simpulumlamerop.com",
            "urochsunloath.com",
            "nathanfromsubject.com",
            "yip.su",
            "metagnathtuggers.com",
            "reedunpack.com",
            "nicolehappyoutside.com",
        )
        if (mirrors.any { host == it || host.endsWith(".$it") }) return true
        // Rotating CDN hosts still use /e/{id} embeds
        if (isVoeEmbedPath(url)) return true
        return false
    }

    /** VOE-style embed path even on rotated hosts. */
    fun isVoeEmbedPath(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        if (path.contains("/e/", ignoreCase = true) && path.length > 4) return true
        // Filmpalast-style share links land on bare /{id} mirrors (no /e/)
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.contains("filmpalast")) return false
        return Regex("""^/[A-Za-z0-9_-]{6,}/?$""").matches(path)
    }

    fun usesWebPlayer(url: String): Boolean =
        !isDirectMediaUrl(url) && (
            isPlayBlobUrl(url) ||
                isVoePlayerUrl(url) ||
                isEpisodeWatchPage(url) ||
                isMovieWatchPage(url)
            )

    /** Series episode watch pages that host the real player iframe/UI. */
    fun isEpisodeWatchPage(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        // Filmpalast-style movie detail/watch pages: /stream/{slug}
        if (isMovieWatchPage(url)) return true
        if (!path.contains("/serie/", true) && !path.contains("/series/", true)) return false
        // Season index pages are NOT watch pages — require episode/folge.
        return path.contains("/episode", true) || path.contains("/folge", true)
    }

    /** Filmpalast-like movie watch/detail page: /stream/{slug} (not series episode). */
    fun isMovieWatchPage(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        if (path.contains("/serie/", true) || path.contains("/series/", true)) return false
        if (path.contains("/episode", true) || path.contains("/folge", true)) return false
        if (path.contains("/staffel", true) || path.contains("/season", true)) return false
        return Regex("""/stream/[^/]+/?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    /**
     * /r?t= blobs are iframe-only on some hosts: top-level load redirects away.
     * Detect that HTML and fall back to episode page playback.
     */
    fun isIframeOnlyPlayBlobHtml(body: String): Boolean =
        body.contains("frameBridge") && body.contains("window.top === window.self")

    fun streamKindLabel(url: String): String = when {
        isM3u8(url) -> "m3u8"
        isMp4(url) -> "mp4"
        isDash(url) -> "dash"
        isPlayBlobUrl(url) -> "play-blob"
        isVoePlayerUrl(url) -> "voe"
        isMovieWatchPage(url) -> "movie-page"
        isEpisodeWatchPage(url) -> "episode-page"
        else -> "other"
    }
}
