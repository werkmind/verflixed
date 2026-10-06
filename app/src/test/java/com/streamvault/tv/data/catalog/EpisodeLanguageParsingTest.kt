package com.streamvault.tv.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hoster markup as served by the series source, reduced to what carries language. */
class EpisodeLanguageParsingTest {
    private val page = "https://example.test/serie/x/staffel-1/episode-1"

    private fun button(provider: String, attrs: String, flag: String = "") = """
        <div class="col-12"><div class="link-wrapper">
          <button type="button" class="link-box" data-play-url="/r?t=$provider${attrs.hashCode()}"
                  data-provider-name="$provider" $attrs>
            <span>$provider</span>$flag
          </button>
        </div></div>
    """

    private fun flag(name: String) = """<svg class="watch-language"><use href="#icon-flag-$name"></use></svg>"""

    private val parser = CatalogParser(com.squareup.moshi.Moshi.Builder().build())

    @Test
    fun readsLabelIdAndFlag() {
        val html = """
            <div class="row">
              <div class="col-12"><h5>Deutsch</h5></div>
              ${button("VOE", """data-language-label="Deutsch" data-language-id="1"""")}
              <div class="col-12"><h5>Englisch</h5></div>
              ${button("Vidoza", """data-language-id="2"""")}
              ${button("Doodstream", "", flag("japanese-german"))}
            </div>
        """
        val byProvider = parser.extractPlayBlobCandidates(html, page).associate { it.provider to it.language }
        assertEquals(StreamLanguage.DE, byProvider["VOE"])
        assertEquals(StreamLanguage.EN, byProvider["Vidoza"])
        assertEquals(StreamLanguage.DE_SUB, byProvider["Doodstream"])
        assertEquals(
            listOf(StreamLanguage.DE, StreamLanguage.EN, StreamLanguage.DE_SUB),
            parser.extractAvailableLanguages(html, page),
        )
    }

    @Test
    fun unlabelledLinkTakesTheHeadingBeforeItNotTheFirstOnThePage() {
        val html = """
            <div class="row">
              <div class="col-12"><h5>Deutsch</h5></div>
              ${button("VOE", "")}
              <div class="col-12"><h5>Englisch</h5></div>
              ${button("Vidoza", "")}
            </div>
        """
        val byProvider = parser.extractPlayBlobCandidates(html, page).associate { it.provider to it.language }
        assertEquals(StreamLanguage.DE, byProvider["VOE"])
        assertEquals(StreamLanguage.EN, byProvider["Vidoza"])
    }

    @Test
    fun preferredLanguageRanksFirstAndNothingIsInvented() {
        val html = """
            <div class="row">
              ${button("VOE", """data-language-label="Deutsch"""")}
              ${button("Vidoza", """data-language-label="Englisch"""")}
            </div>
        """
        assertEquals("Vidoza", parser.extractPlayBlobCandidates(html, page, StreamLanguage.EN).first().provider)
        assertEquals("VOE", parser.extractPlayBlobCandidates(html, page, StreamLanguage.DE).first().provider)
        val plain = """<div><h3>Deutschland sucht</h3>${button("VOE", "")}</div>"""
        assertEquals(emptyList<String>(), parser.extractAvailableLanguages("<div>${button("VOE", "")}</div>", page))
        assertEquals(emptyList<String>(), parser.extractAvailableLanguages(plain, page))
    }
}
