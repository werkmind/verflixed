package com.streamvault.tv.data.catalog

import android.net.Uri

object StreamKind {
    fun isM3u8(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true) ||
            url.contains("/hls/", ignoreCase = true) && url.contains("playlist", ignoreCase = true)

    fun isMp4(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".mp4")) return true
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
        // Signed CDN progressive / HLS (Firestream, S3, CloudFront…)
        if (
            (lower.contains("x-amz-") || lower.contains("signature=") || lower.contains("x-goog-")) &&
            (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains("/video.") ||
                lower.contains("firestream") || lower.contains("cloudfront") || lower.contains("amazonaws"))
        ) {
            return true
        }
        if (lower.contains("firestream") && (lower.contains("video") || lower.contains("media"))) {
            if (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains("x-amz") || lower.contains("signature")) {
                return true
            }
        }
        // Signed cloud URLs ending with video.mp4 / video.m3u8 before query
        if (Regex("""/video\.(mp4|m3u8)(?:\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return true
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
        // Primary: any host with VOE-style /e/{id} or bare /{id} share path (proxies rotate constantly)
        if (isVoeEmbedPath(url)) return true
        if (host == "voe.sx" || host.endsWith(".voe.sx")) return true
        if (host.contains("voe")) return true
        // Known mirrors (hint only — new proxies still match via isVoeEmbedPath)
        if (KNOWN_VOE_MIRRORS.any { host == it || host.endsWith(".$it") }) return true
        return false
    }

    val KNOWN_VOE_MIRRORS: List<String> = listOf(
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
        "jilliandescribecompany.com",
        "justinfinishedshooting.com",
        "shannonpersonalgrade.com",
        "brucevotewathen.com",
    )

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
