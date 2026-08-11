package com.streamvault.tv.data.catalog

/**
 * SerienStream-compatible image helpers.
 * Covers live under `/media/images/channel/...` and `/media/images/backdrop/...`.
 * Prefer JPEG + desktop/2x-desktop for Fire TV (AVIF/WebP and mobile thumbs look pixelated).
 */
object SiteImages {
    fun preferJpeg(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        u = upgradeResolution(u)
        if (u.contains("/media/images/", ignoreCase = true)) {
            u = u.replace(Regex("""([?&])format=(avif|webp)""", RegexOption.IGNORE_CASE), "$1format=jpg")
            if (!u.contains("format=", ignoreCase = true)) {
                u += if (u.contains("?")) "&format=jpg" else "?format=jpg"
            }
        }
        return u
    }

    /** Prefer 2x-desktop > desktop over mobile/tablet thumbs. */
    fun upgradeResolution(url: String): String {
        var u = url
        val kinds = listOf("channel", "backdrop")
        for (kind in kinds) {
            u = u
                .replace("/media/images/$kind/mobile/", "/media/images/$kind/2x-desktop/", ignoreCase = true)
                .replace("/media/images/$kind/tablet/", "/media/images/$kind/2x-desktop/", ignoreCase = true)
                .replace("/media/images/$kind/desktop/", "/media/images/$kind/2x-desktop/", ignoreCase = true)
        }
        return u
    }

    fun channelCover(baseUrl: String, posterSlug: String): String {
        val base = baseUrl.trimEnd('/')
        val slug = posterSlug.trim().trim('/')
        return preferJpeg("$base/media/images/channel/2x-desktop/$slug")!!
    }

    fun isChannel(url: String?): Boolean =
        url?.contains("/media/images/channel/", ignoreCase = true) == true

    fun isBackdrop(url: String?): Boolean =
        url?.contains("/media/images/backdrop/", ignoreCase = true) == true

    fun resolutionScore(url: String?): Int {
        if (url.isNullOrBlank()) return -1
        val u = url.lowercase()
        return when {
            u.contains("/2x-desktop/") -> 40
            u.contains("/desktop/") -> 30
            u.contains("/tablet/") -> 20
            u.contains("/mobile/") -> 10
            u.contains("/orig/") -> 35
            else -> 5
        }
    }

    fun bestUrl(candidates: Collection<String?>): String? =
        candidates
            .mapNotNull { preferJpeg(it) }
            .maxByOrNull { resolutionScore(it) }

    fun pickPoster(vararg candidates: String?): String? {
        val list = candidates.filterNotNull()
        return preferJpeg(list.firstOrNull { isChannel(it) })
            ?: bestUrl(list)
            ?: preferJpeg(list.firstOrNull { it.isNotBlank() })
    }

    fun pickBackdrop(vararg candidates: String?): String? {
        val list = candidates.filterNotNull()
        return preferJpeg(list.firstOrNull { isBackdrop(it) })
            ?: bestUrl(list)
            ?: preferJpeg(list.firstOrNull { it.isNotBlank() })
    }

    /** From a srcset attribute, pick the highest-res candidate. */
    fun fromSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        val urls = srcset.split(',')
            .mapNotNull { part ->
                part.trim().substringBefore(' ').trim().takeIf { it.isNotBlank() && !it.startsWith("data:") }
            }
        return bestUrl(urls)
    }
}
