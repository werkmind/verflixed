package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.Series
import org.jsoup.Jsoup

/**
 * The series source's complete A to Z index (`/serien`): every title on the
 * site in one page, each with the site's own search text, which carries
 * alternative and original titles. Searching it locally finds titles that the
 * site's suggest endpoint (five hits) and the home page never mention.
 */
object SeriesIndex {

    /** [key] is the normalised haystack: title plus the site's alternative names. */
    data class Entry(val slug: String, val title: String, val key: String)

    fun parse(html: String): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>(12_000)
        Jsoup.parse(html).select("li.series-item").forEach { li ->
            val a = li.selectFirst("a[href*=/serie/]") ?: return@forEach
            val slug = a.attr("href").substringAfter("/serie/").substringBefore('/').substringBefore('?').trim()
            val title = a.text().trim()
            if (slug.isBlank() || title.isBlank() || !seen.add(slug)) return@forEach
            out += Entry(slug, title, normalize(title + " " + li.attr("data-search")))
        }
        return out
    }

    /**
     * Lower case, umlauts and accents folded to plain letters, everything that
     * is not a letter or digit turned into a single space. The TV keyboard has
     * no umlauts or punctuation, so "greys anatomy" has to find "Grey's
     * Anatomy" and "uber" has to find "Über".
     */
    fun normalize(raw: String): String {
        val folded = java.text.Normalizer.normalize(raw.lowercase().replace("ß", "ss"), java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(folded.length)
        var space = true
        for (c in folded) {
            when {
                c.isLetterOrDigit() -> { sb.append(c); space = false }
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                // An apostrophe joins ("grey's" = "greys"); other punctuation separates.
                c == '\'' || c == '\u2019' || c == '`' -> Unit
                !space -> { sb.append(' '); space = true }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Entries matching every word of [query], best first: whole title equal,
     * title starting with the query, a word starting with it, then anywhere.
     * Typed "ue/oe/ae" also matches a folded umlaut.
     */
    fun search(entries: List<Entry>, query: String, limit: Int): List<Entry> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val variants = linkedSetOf(q, q.replace("ue", "u").replace("oe", "o").replace("ae", "a"))
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in entries) {
            var best = Int.MAX_VALUE
            for (v in variants) {
                val words = v.split(' ')
                if (!words.all { e.key.contains(it) }) continue
                val title = normalize(e.title)
                val score = when {
                    title == v -> 0
                    title.startsWith(v) -> 1
                    e.key.contains(" $v") || e.key.startsWith(v) -> 2
                    title.contains(v) -> 3
                    e.key.contains(v) -> 4
                    else -> 5
                }
                if (score < best) best = score
            }
            if (best != Int.MAX_VALUE) scored += best to e
        }
        return scored
            .sortedWith(compareBy<Pair<Int, Entry>> { it.first }.thenBy { it.second.title.length })
            .take(limit)
            .map { it.second }
    }

    fun toSeries(entry: Entry, baseUrl: String): Series = Series(
        id = entry.slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        title = entry.title,
        detailPath = "${baseUrl.trimEnd('/')}/serie/${entry.slug}",
        mediaKind = "series",
    )
}
