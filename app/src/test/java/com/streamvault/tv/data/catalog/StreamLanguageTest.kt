package com.streamvault.tv.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLanguageTest {
    @Test
    fun siteLabelsAndIds() {
        assertEquals(StreamLanguage.DE, StreamLanguage.classify("Deutsch"))
        assertEquals(StreamLanguage.EN, StreamLanguage.classify("Englisch"))
        assertEquals(StreamLanguage.DE, StreamLanguage.classify("1"))
        assertEquals(StreamLanguage.EN, StreamLanguage.classify("2"))
        assertEquals(StreamLanguage.DE_SUB, StreamLanguage.classify("3"))
        assertEquals(StreamLanguage.DE, StreamLanguage.classify("#icon-flag-german"))
        assertEquals(StreamLanguage.EN, StreamLanguage.classify("#icon-flag-english"))
    }

    @Test
    fun subtitlesAreNotDubs() {
        assertEquals(StreamLanguage.DE_SUB, StreamLanguage.classify("Englisch mit deutschen Untertiteln"))
        assertEquals(StreamLanguage.DE_SUB, StreamLanguage.classify("Ger-Sub"))
        assertEquals(StreamLanguage.DE_SUB, StreamLanguage.classify("#icon-flag-japanese-german"))
        assertEquals(StreamLanguage.EN_SUB, StreamLanguage.classify("Eng Sub"))
        assertEquals(StreamLanguage.EN_SUB, StreamLanguage.classify("japanese-english"))
    }

    @Test
    fun unknownStaysUnknown() {
        assertEquals(StreamLanguage.UNKNOWN, StreamLanguage.classify(""))
        assertEquals(StreamLanguage.UNKNOWN, StreamLanguage.classify("Japanisch"))
        assertEquals(StreamLanguage.UNKNOWN, StreamLanguage.classify("7"))
        assertEquals(StreamLanguage.UNKNOWN, StreamLanguage.classify("Deutschland sucht"))
        assertFalse(StreamLanguage.matchesPreferred("", StreamLanguage.DE))
        assertFalse(StreamLanguage.matchesPreferred("Japanisch", StreamLanguage.DE))
    }

    @Test
    fun preferenceDefaultsToGerman() {
        assertEquals(StreamLanguage.DE, StreamLanguage.normalize(null))
        assertEquals(StreamLanguage.DE, StreamLanguage.normalize("quatsch"))
        assertEquals(StreamLanguage.DE_SUB, StreamLanguage.normalize("desub"))
        assertTrue(StreamLanguage.matchesPreferred("Englisch", "en"))
    }

    @Test
    fun quickSwitchCyclesOnlyWhatExists() {
        val available = listOf(StreamLanguage.EN, StreamLanguage.DE)
        assertEquals(StreamLanguage.EN, StreamLanguage.next(StreamLanguage.DE, available))
        assertEquals(StreamLanguage.DE, StreamLanguage.next(StreamLanguage.EN, available))
        assertEquals(StreamLanguage.DE, StreamLanguage.next(StreamLanguage.DE, listOf(StreamLanguage.DE)))
        assertEquals(StreamLanguage.DE, StreamLanguage.next(StreamLanguage.DE_SUB, available))
    }
}
