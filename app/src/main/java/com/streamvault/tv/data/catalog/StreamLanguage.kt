package com.streamvault.tv.data.catalog

/**
 * Stream audio languages. A profile prefers one of them (default German);
 * a hoster link either carries one or is [UNKNOWN].
 */
object StreamLanguage {
    const val DE = "de"
    const val EN = "en"
    /** Original audio with German subtitles. */
    const val DE_SUB = "desub"
    /** Original audio with English subtitles. */
    const val EN_SUB = "ensub"
    const val UNKNOWN = ""

    /** Order shown to the user and cycled by the quick switch. */
    val ALL = listOf(DE, EN, DE_SUB, EN_SUB)

    private val SUB_MARKER = Regex("""sub|untertitel|\bomu\b|\but\b""")
    // Whole words only: "Deutschland" or "Engel" in a heading is not a language.
    private val ENGLISH = Regex("""\bengl?isch(e[nrs]?)?\b|\benglish\b|\beng\b|\ben\b""")
    private val GERMAN = Regex("""\bdeutsch(e[nrs]?)?\b|\bgerman\b|\bger\b|\bdeu\b|\bde\b""")

    /**
     * What a site label, id or flag name says about a link. Returns [UNKNOWN]
     * when it says nothing we recognise, so a missing label is never reported
     * as German.
     */
    fun classify(raw: String?): String {
        val l = raw?.trim()?.lowercase().orEmpty()
        if (l.isBlank()) return UNKNOWN
        when (l) {
            DE, EN, DE_SUB, EN_SUB -> return l
            // SerienStream / AniWorld language ids.
            "1" -> return DE
            "2" -> return EN
            "3" -> return DE_SUB
        }
        val english = ENGLISH.containsMatchIn(l)
        val german = GERMAN.containsMatchIn(l)
        if (SUB_MARKER.containsMatchIn(l)) {
            // "Englisch mit deutschen Untertiteln", "ger-sub", "japanese-german".
            return when {
                german -> DE_SUB
                english -> EN_SUB
                else -> UNKNOWN
            }
        }
        return when {
            // Two languages in one flag name means audio + subtitle language.
            german && english -> DE_SUB
            l.contains("japan") && german -> DE_SUB
            l.contains("japan") && english -> EN_SUB
            german -> DE
            english -> EN
            else -> UNKNOWN
        }
    }

    /** A stored preference: always one of [ALL], German when missing or unreadable. */
    fun normalize(raw: String?): String = classify(raw).ifBlank { DE }

    fun isGerman(raw: String?): Boolean = classify(raw) == DE
    fun isEnglish(raw: String?): Boolean = classify(raw) == EN

    fun label(code: String?): String = when (classify(code)) {
        EN -> "Englisch"
        DE_SUB -> "Original mit deutschen Untertiteln"
        EN_SUB -> "Original mit englischen Untertiteln"
        DE -> "Deutsch"
        else -> "Unbekannt"
    }

    fun shortLabel(code: String?): String = when (classify(code)) {
        EN -> "EN"
        DE_SUB -> "OmU DE"
        EN_SUB -> "OmU EN"
        DE -> "DE"
        else -> "?"
    }

    /** Next language after [current] among [available]; [current] when there is no other. */
    fun next(current: String?, available: List<String>): String {
        val options = ALL.filter { it in available }
        if (options.isEmpty()) return normalize(current)
        val at = options.indexOf(classify(current))
        return options[(at + 1) % options.size]
    }

    /** Detect movie/page language from Filmpalast release titles, slug, ENGLISH badges, etc. */
    fun detectFromText(vararg texts: String?): String? {
        val blob = texts.filterNotNull().joinToString(" ").lowercase()
        if (blob.isBlank()) return null
        val hasEn = blob.contains("*english*") ||
            blob.contains(" english") ||
            blob.contains("english*") ||
            blob.contains("englisch") ||
            blob.contains("-english") ||
            blob.contains("/english") ||
            Regex("""\benglish\b""").containsMatchIn(blob) ||
            Regex("""\beng\b""").containsMatchIn(blob) ||
            blob.contains(".eng.")
        val hasDe = blob.contains("german") || blob.contains("deutsch") ||
            Regex("""\bger\b""").containsMatchIn(blob) ||
            blob.contains("german.dubbed") || blob.contains(".ger.") ||
            blob.contains("german.dl")
        return when {
            hasEn && !hasDe -> EN
            hasDe && !hasEn -> DE
            hasEn && hasDe -> {
                // Slug/title ENGLISH wins over incidental GERMAN in description
                if (blob.contains("*english*") || blob.contains("-english")) EN else DE
            }
            hasDe -> DE
            hasEn -> EN
            else -> null
        }
    }

    fun matchesPreferred(candidateLang: String?, preferred: String): Boolean {
        val cand = classify(candidateLang)
        return cand != UNKNOWN && cand == normalize(preferred)
    }

    /** Strip language markers from a movie title for sibling search. */
    fun cleanTitleForSearch(title: String): String =
        title.replace(Regex("""\*+\s*ENGLISH\s*\*+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\*+\s*GERMAN\s*\*+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bENGLISH\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bGERMAN\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
