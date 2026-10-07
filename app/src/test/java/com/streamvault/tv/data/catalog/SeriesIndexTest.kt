package com.streamvault.tv.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markup as served by the series source's /serien page, reduced to a few entries. */
class SeriesIndexTest {
    private val html = """
        <ul>
          <li class="series-item" data-search="absturz! absturz | 2dezit"><a href="/serie/absturz">Absturz!</a></li>
          <li class="series-item" data-search="grey's anatomy - die jungen ärzte greys anatomy"><a href="/serie/grey-s-anatomy-die-jungen-aerzte">Grey's Anatomy - Die jungen Ärzte</a></li>
          <li class="series-item" data-search="greyzone"><a href="/serie/greyzone">Greyzone</a></li>
          <li class="series-item" data-search="über uns ueber uns"><a href="/serie/ueber-uns">Über uns</a></li>
          <li class="series-item" data-search="acapulco h.e.a.t. acapulco h e a t"><a href="/serie/acapulco-h-e-a-t">Acapulco H.E.A.T.</a></li>
          <li class="series-item" data-search="spider-man spider man"><a href="/serie/spider-man">Spider-Man</a></li>
          <li class="series-item" data-search="absturz"><a href="/serie/absturz">Absturz! (Duplikat)</a></li>
        </ul>
    """
    private val entries = SeriesIndex.parse(html)

    private fun find(q: String) = SeriesIndex.search(entries, q, 10).map { it.slug }

    @Test
    fun parsesEveryTitleOnce() {
        assertEquals(6, entries.size)
        assertEquals("Absturz!", entries.first().title)
    }

    @Test
    fun keyboardWithoutPunctuationOrUmlautsStillFinds() {
        assertEquals("grey-s-anatomy-die-jungen-aerzte", find("GREYS ANATOMY").first())
        assertEquals(listOf("spider-man"), find("SPIDER MAN"))
        assertEquals(listOf("ueber-uns"), find("UBER"))
        assertEquals(listOf("ueber-uns"), find("UEBER UNS"))
        assertEquals(listOf("acapulco-h-e-a-t"), find("ACAPULCO HEAT").ifEmpty { find("ACAPULCO H E A T") })
    }

    @Test
    fun alternativeTitlesMatch() {
        assertEquals(listOf("absturz"), find("2dezit"))
    }

    @Test
    fun titlePrefixRanksBeforeLaterMatch() {
        val hits = find("grey")
        assertEquals("greyzone", hits.first())
        assertTrue("grey-s-anatomy-die-jungen-aerzte" in hits)
    }

    @Test
    fun shortOrUnknownQueriesReturnNothing() {
        assertEquals(emptyList<String>(), find("g"))
        assertEquals(emptyList<String>(), find("zzzz"))
    }
}
