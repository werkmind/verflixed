package com.streamvault.tv.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the search bugs. Network-free by design: the macOS
 * JVM test worker has no local-network permission, so socket tests hang.
 * The query logic and the JSON parsing are the units that matter.
 */
class SiteSearchTest {

    @Test
    fun `umlauts fold to ascii digraphs`() {
        assertEquals("ueberleben", SiteSearch.foldUmlauts("überleben"))
        assertEquals("koeln", SiteSearch.foldUmlauts("köln"))
        assertEquals("maedchen", SiteSearch.foldUmlauts("mädchen"))
        assertEquals("strasse", SiteSearch.foldUmlauts("straße"))
        // Case-insensitive replace keeps surrounding casing.
        assertEquals("ueBER", SiteSearch.foldUmlauts("ÜBER"))
    }

    @Test
    fun `plain ascii is unchanged by folding`() {
        assertEquals("dark", SiteSearch.foldUmlauts("dark"))
        assertEquals("the 100", SiteSearch.foldUmlauts("the 100"))
    }

    @Test
    fun `folded query matches umlaut titles`() {
        // Local matcher folds both sides: typed "ueber" must hit "Überleben".
        val title = "Überleben"
        val q = "ueber"
        assertTrue(SiteSearch.foldUmlauts(title.lowercase()).startsWith(SiteSearch.foldUmlauts(q)))
        assertFalse(title.lowercase().startsWith(q))
    }

    @Test
    fun `suggest body parses into series with root detail path`() {
        val body = """{"shows":[
            {"name":"Dark","url":"/serie/dark"},
            {"name":"Dark <b>Staffel 2</b>","url":"/serie/dark/staffel-2"},
            {"name":"","url":"/serie/empty"},
            {"name":"No Url","url":""},
            {"name":"Wrong Kind","url":"/movies/x"}
        ]}"""
        val parsed = SiteSearch.parseSuggestBodyForTest(body, "https://example.to")
        // Dark + its staffel variant dedupe to the same root; empty/no-url/wrong-kind drop.
        assertEquals(1, parsed.size)
        assertEquals("Dark", parsed[0].title)
        assertEquals("https://example.to/serie/dark", parsed[0].detailPath)
        assertFalse(parsed[0].title.contains("Staffel"))
    }

    @Test
    fun `array body parses as well`() {
        val body = """[{"name":"Dark","url":"/serie/dark"}]"""
        val parsed = SiteSearch.parseSuggestBodyForTest(body, "https://example.to")
        assertEquals(1, parsed.size)
        assertEquals("Dark", parsed[0].title)
    }

    @Test
    fun `garbage body yields empty list instead of crashing`() {
        assertTrue(SiteSearch.parseSuggestBodyForTest("<html>404</html>", "https://example.to").isEmpty())
        assertTrue(SiteSearch.parseSuggestBodyForTest("", "https://example.to").isEmpty())
    }
}
