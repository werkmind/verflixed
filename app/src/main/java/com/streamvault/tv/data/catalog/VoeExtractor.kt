package com.streamvault.tv.data.catalog

import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves VOE embed pages to a direct HLS (.m3u8) playlist URL.
 *
 * Strategy (same idea as Cloudstream / AniWorld / xstream-style clients):
 * 1) Fetch the VOE (or mirror) embed HTML
 * 2) Follow in-page `window.location` / `/e/` redirects
 * 3) Decode the obfuscated `application/json` payload → `source` (m3u8)
 * 4) Fallbacks: `var a168c=…`, plain `'hls': '…'`, raw `.m3u8` in HTML
 *
 * Does NOT break DRM or proprietary crypto beyond reading the public page payload
 * that the official VOE player itself consumes.
 */
class VoeExtractor(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    /**
     * @return HLS playlist URL, or null. Throws [VoeGeoBlockedException] when the
     * host explicitly geo-blocks (so callers can fall back to Vidara).
     */
    fun extractHls(embedUrl: String, referer: String? = null): String? {
        if (embedUrl.isBlank()) return null
        var url = embedUrl.trim()
        var pageReferer = referer ?: "https://voe.sx/"
        val visited = linkedSetOf<String>()
        var lastGeoBlocked = false
        for (hop in 0 until 6) {
            if (!visited.add(url)) break
            val html = getHtml(url, pageReferer) ?: break
            if (isGeoBlocked(html)) {
                lastGeoBlocked = true
            }
            extractSourceFromHtml(html)?.let { return it }

            val next = findRedirect(html)
            if (!next.isNullOrBlank() && next !in visited) {
                pageReferer = url
                url = absUrl(url, next)
                continue
            }
            // bare /{id} → try /e/{id} on same host (Filmpalast soft-redirect mirrors)
            val eUrl = runCatching {
                val u = Uri.parse(url)
                val path = u.path.orEmpty()
                if (!path.contains("/e/", true) &&
                    Regex("""^/[A-Za-z0-9_-]+/?$""").matches(path)
                ) {
                    val id = path.trim('/')
                    "${u.scheme}://${u.host}/e/$id"
                } else null
            }.getOrNull()
            if (!eUrl.isNullOrBlank() && eUrl !in visited) {
                pageReferer = url
                url = eUrl
                continue
            }
            break
        }
        if (lastGeoBlocked) {
            throw VoeGeoBlockedException("VOE geo-blocked (Dateizugriff verweigert)")
        }
        return null
    }

    fun isGeoBlocked(html: String): Boolean =
        html.contains("Dateizugriff verweigert", ignoreCase = true) ||
            html.contains("Zugang zu Ihrem Land eingeschränkt", ignoreCase = true) ||
            html.contains("access to your country", ignoreCase = true)

    /**
     * Try to turn a SerienStream `/r?t=` play-blob into a VOE embed URL using
     * the current WebView cookie jar (after the episode page / captcha session).
     * Without a solved session this usually returns null (iframe-only bridge).
     */
    fun resolvePlayBlobToVoe(blobUrl: String, episodePage: String?): String? {
        if (!StreamKind.isPlayBlobUrl(blobUrl)) return null
        val abs = absUrl(episodePage ?: blobUrl, blobUrl)
        val cookies = cookieHeader(abs)
        // 1) GET blob with cookies – some sessions 302/refresh to VOE
        getHtml(abs, episodePage, cookies)?.let { html ->
            findVoeUrl(html)?.let { return it }
            if (StreamKind.isVoePlayerUrl(html.trim()) || StreamKind.isVoePlayerUrl(abs)) {
                // unlikely
            }
        }
        // Check final URL via HEAD/GET without consuming as HTML-only
        peekFinalUrl(abs, episodePage, cookies)?.let { final ->
            if (StreamKind.isVoePlayerUrl(final)) return final
            findVoeUrl(final)?.let { return it }
        }

        // 2) POST /r with token + CSRF from episode page cookies
        val page = episodePage?.takeIf { it.isNotBlank() } ?: return null
        val pageHtml = getHtml(page, page, cookies) ?: return null
        val csrf = CSRF.find(pageHtml)?.groupValues?.get(1)
            ?: CSRF_META.find(pageHtml)?.groupValues?.get(1)
            ?: return null
        val token = Uri.parse(abs).getQueryParameter("t") ?: return null
        val postUrl = absUrl(page, "/r")
        val body = FormBody.Builder()
            .add("_token", csrf)
            .add("t", token)
            .build()
        val req = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", page)
            .header("Origin", originOf(page) ?: "https://serienstream.to")
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
            .header("X-CSRF-TOKEN", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val final = resp.request.url.toString()
            if (StreamKind.isVoePlayerUrl(final)) return final
            val html = resp.body?.string().orEmpty()
            findVoeUrl(html)?.let { return it }
            // meta refresh / js location
            findRedirect(html)?.takeIf { StreamKind.isVoePlayerUrl(it) }?.let { return it }
        }
        return null
    }

    fun extractSourceFromHtml(html: String): String? {
        // Variant: <script type="application/json">["….encoded…."]</script> or "…"
        SCRIPT_JSON.findAll(html).forEach { m ->
            val raw = m.groupValues[1].trim()
            decodeCandidates(raw).forEach { decoded ->
                pickSource(decoded)?.let { return it }
            }
        }
        // Variant: var a168c='…'
        A168C.find(html)?.groupValues?.get(1)?.let { enc ->
            runCatching { decodeVoeString(enc) }.getOrNull()?.let { pickSource(it) }?.let { return it }
        }
        // Variant: 'hls': 'https://…m3u8'
        HLS_PLAIN.find(html)?.groupValues?.get(1)?.let { u ->
            val url = u.replace("\\/", "/")
            if (StreamKind.isDirectMediaUrl(url)) return url
        }
        // Last resort: any m3u8 URL in page
        M3U8.find(html)?.value?.let { return it.replace("\\/", "/") }
        return null
    }

    private fun decodeCandidates(raw: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val trimmed = raw.trim()
        val payloads = mutableListOf<String>()
        when {
            trimmed.startsWith("[") && trimmed.contains("\"") -> {
                // ["encoded"] or [ "encoded" ]
                val inner = trimmed.removePrefix("[").removeSuffix("]").trim()
                    .removePrefix("\"").removeSuffix("\"")
                payloads += inner
            }
            trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
                payloads += trimmed.removePrefix("\"").removeSuffix("\"")
            else -> payloads += trimmed
        }
        for (p in payloads) {
            val unescaped = runCatching {
                // Handle \\u / \\/ style escapes lightly
                JSONObject.quote(p).let {
                    // already a string content; try unicode unescape via JSON
                    JSONObject("{\"x\":\"${p.replace("\"", "\\\"")}\"}").optString("x", p)
                }
            }.getOrDefault(p)
            runCatching { decodeVoeString(unescaped) }.getOrNull()?.let { out += it }
            if (unescaped != p) {
                runCatching { decodeVoeString(p) }.getOrNull()?.let { out += it }
            }
        }
        return out
    }

    private fun decodeVoeString(encoded: String): JSONObject {
        var s = rot13(encoded)
        for (junk in JUNK) s = s.replace(junk, "_")
        s = s.replace("_", "")
        s = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        s = s.map { (it.code - 3).toChar() }.joinToString("")
        s = String(Base64.decode(s.reversed(), Base64.DEFAULT), Charsets.UTF_8)
        return JSONObject(s)
    }

    private fun pickSource(obj: JSONObject): String? {
        val source = obj.optString("source").takeIf { it.isNotBlank() }
            ?: obj.optString("hls").takeIf { it.isNotBlank() }
        if (!source.isNullOrBlank() && StreamKind.isDirectMediaUrl(source)) return source
        val direct = obj.optString("direct_access_url").takeIf { it.isNotBlank() }
        if (!direct.isNullOrBlank() && StreamKind.isDirectMediaUrl(direct)) return direct
        return source?.takeIf { StreamKind.isDirectMediaUrl(it) }
    }

    private fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                in 'A'..'Z' -> append(((((c - 'A') + 13) % 26) + 'A'.code).toChar())
                in 'a'..'z' -> append(((((c - 'a') + 13) % 26) + 'a'.code).toChar())
                else -> append(c)
            }
        }
    }

    private fun findRedirect(html: String): String? {
        // Soft redirect (Redirecting… pages) — accept any absolute https location,
        // including bare mirror /{id} share links used by Filmpalast → VOE.
        LOCATION.find(html)?.groupValues?.get(1)?.trim()?.let { loc ->
            if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
        }
        LOCATION_REPLACE.find(html)?.groupValues?.get(1)?.trim()?.let { loc ->
            if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
        }
        META_REFRESH.find(html)?.groupValues?.get(1)?.trim()?.let { loc ->
            if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
        }
        EMBED_E.find(html)?.groupValues?.get(1)?.let { return it.trim() }
        findVoeUrl(html)?.let { return it }
        return null
    }

    private fun findVoeUrl(text: String): String? {
        VOE_URL.findAll(text).map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
            .firstOrNull { StreamKind.isVoePlayerUrl(it) }
            ?.let { return it }
        return null
    }

    private fun getHtml(url: String, referer: String?, cookies: String? = cookieHeader(url)): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
                if (!cookies.isNullOrBlank()) header("Cookie", cookies)
            }
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in 300..399) {
                    // still try body on 403 challenge etc.
                }
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun peekFinalUrl(url: String, referer: String?, cookies: String?): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
                if (!cookies.isNullOrBlank()) header("Cookie", cookies)
            }
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { it.request.url.toString() }
        }.getOrNull()
    }

    private fun cookieHeader(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    private fun absUrl(base: String, maybe: String): String {
        if (maybe.startsWith("http://") || maybe.startsWith("https://")) return maybe
        return runCatching { java.net.URI(base).resolve(maybe).toString() }.getOrElse {
            val u = Uri.parse(base)
            if (maybe.startsWith("/")) "${u.scheme}://${u.host}$maybe" else maybe
        }
    }

    private fun originOf(url: String): String? = runCatching {
        val u = Uri.parse(url)
        "${u.scheme}://${u.host}"
    }.getOrNull()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private val JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        private val SCRIPT_JSON = Regex(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val A168C = Regex("""var\s+a168c\s*=\s*'([^']+)'""")
        private val HLS_PLAIN = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""")
        private val M3U8 = Regex("""https?://[^\s'"<>]+?\.m3u8[^\s'"<>]*""", RegexOption.IGNORE_CASE)
        private val LOCATION = Regex(
            """(?:location\.href|window\.location(?:\.href)?)\s*=\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE,
        )
        private val LOCATION_REPLACE = Regex(
            """location\.replace\(\s*['"]([^'"]+)['"]\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        private val META_REFRESH = Regex(
            """content=['"]\d+;\s*url=([^'"]+)['"]""",
            RegexOption.IGNORE_CASE,
        )
        private val EMBED_E = Regex("""['"](\s*https?://[^'"<>\s]+/e/[^'"<>\s]+)['"]""")
        private val VOE_URL = Regex(
            """https?://[^\s"'<>]*(?:voe|donaldlineelse|charlestoughrace|tubelessceliolymph|simpulumlamerop|urochsunloath|nathanfromsubject|yip\.su|metagnathtuggers|reedunpack|nicolehappyoutside)[^\s"'<>]*""",
            RegexOption.IGNORE_CASE,
        )
        private val CSRF = Regex("""name=["']_token["']\s+value=["']([^"']+)["']""")
        private val CSRF_META = Regex("""name=["']csrf-token["']\s+content=["']([^"']+)["']""")
    }
}

class VoeGeoBlockedException(message: String) : Exception(message)
