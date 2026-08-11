package com.streamvault.tv.data.catalog

/**
 * Stream audio language preference helpers (Deutsch / Englisch).
 * Default is always German ("de").
 */
object StreamLanguage {
    const val DE = "de"
    const val EN = "en"

    fun normalize(raw: String?): String {
        val l = raw?.trim()?.lowercase().orEmpty()
        if (l.isBlank()) return DE
        return when {
            l == DE || l == "ger" || l == "deu" || l == "german" || l == "deutsch" ||
                l.startsWith("de") && !l.contains("desub") -> DE
            l == EN || l == "eng" || l == "english" || l == "englisch" ||
                l.startsWith("en") -> EN
            l.contains("deutsch") || l.contains("german") || l.contains("ger dub") ||
                l.contains("german.dubbed") || Regex("""\bgerman\b""").containsMatchIn(l) -> DE
            l.contains("englisch") || l.contains("english") || l.contains("eng dub") -> EN
            // SerienStream language ids: 1 = Deutsch, 2 = Englisch
            l == "1" -> DE
            l == "2" -> EN
            else -> DE
        }
    }

    fun isGerman(raw: String?): Boolean = normalize(raw) == DE
    fun isEnglish(raw: String?): Boolean = normalize(raw) == EN

    fun label(code: String?): String = when (normalize(code)) {
        EN -> "Englisch"
        else -> "Deutsch"
    }

    fun shortLabel(code: String?): String = when (normalize(code)) {
        EN -> "EN"
        else -> "DE"
    }

    fun toggle(code: String?): String = if (normalize(code) == DE) EN else DE

    /** Detect movie/page language from Filmpalast release titles etc. */
    fun detectFromText(vararg texts: String?): String? {
        val blob = texts.filterNotNull().joinToString(" ").lowercase()
        if (blob.isBlank()) return null
        val hasDe = blob.contains("german") || blob.contains("deutsch") ||
            Regex("""\bger\b""").containsMatchIn(blob) ||
            blob.contains("german.dubbed") || blob.contains(".ger.")
        val hasEn = blob.contains("english") || blob.contains("englisch") ||
            Regex("""\beng\b""").containsMatchIn(blob) ||
            blob.contains(".eng.")
        return when {
            hasDe && !hasEn -> DE
            hasEn && !hasDe -> EN
            hasDe -> DE
            hasEn -> EN
            else -> null
        }
    }

    fun matchesPreferred(candidateLang: String?, preferred: String): Boolean {
        val pref = normalize(preferred)
        val cand = candidateLang?.trim().orEmpty()
        if (cand.isBlank()) return false
        return normalize(cand) == pref
    }
}
