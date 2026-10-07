package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestTest {
    @Test
    fun filmQueryIsPathEncoded() {
        // The film source answers "der%20pate" and returns nothing for "der+pate".
        assertEquals("der%20pate", SiteSearch.pathSegment("der pate"))
        assertEquals("Kom%C3%B6die", SiteSearch.pathSegment("Komödie"))
    }

    @Test
    fun resultPageKeepsSeriesCardsAndDropsEpisodeCards() {
        val html = """
            <h2>Serien</h2>
            <a href="/serie/greys-anatomy-b-team"><div class="card cover-card">
              <a href="/serie/greys-anatomy-b-team" class="show-cover"><img src="/media/b-team.jpg" alt="x"></a>
              <h6>Grey’s Anatomy: B-Team</h6></div></a>
            <h2>Episoden</h2>
            <div class="card cover-card">
              <a href="/serie/ted-lasso/staffel-1/episode-3" class="show-cover"><img src="/media/ted.jpg"></a>
              <h6>Ted Lasso</h6></div>
            <footer><a href="/serie/irgendwas">Beliebt</a></footer>
        """
        val hits = SiteSearch.parseResultPage(html, "https://example.test")
        assertEquals(listOf("greys-anatomy-b-team"), hits.map { it.id })
        assertEquals("https://example.test/media/b-team.jpg", hits.single().posterUrl)
    }

    @Test
    fun genreBlockLooksAtGenreAndTitleNotAtThePlot() {
        val blocked = setOf("horror")
        val drama = Series(id = "stilles-haus", title = "Stilles Haus", genres = listOf("drama"),
            overview = "Kein Horror, sondern ein leises Familiendrama.")
        val horror = Series(id = "nachtschicht", title = "Nachtschicht", genres = listOf("horror"))
        assertFalse(ContentGate.isBlocked(drama, blocked))
        assertTrue(ContentGate.isBlocked(horror, blocked))
    }
}
