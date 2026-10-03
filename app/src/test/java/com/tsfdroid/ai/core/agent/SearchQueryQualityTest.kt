package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gold-query lesson: a planner once wrote `WEB_SEARCH {query: "current"}`
 * for "Fetch the current gold price" and the turn honestly delivered a
 * fintech's marketing page as its price data. These rules are pure logic —
 * they get the standalone-JVM treatment.
 */
class SearchQueryQualityTest {

    // ------------------------------------------------------------ isDegenerate

    @Test
    fun `lone generic adjectives and request words are degenerate`() {
        listOf(
            "current", "latest", "today", "now", "price", "what", "how",
            "search", "find", "information", "app", "online"
        ).forEach { q ->
            assertTrue("'$q' should be degenerate", SearchQueryQuality.isDegenerate(q))
        }
    }

    @Test
    fun `real search phrases are never degenerate`() {
        listOf(
            "current gold price", "gold price", "solar energy growth india",
            "iphone 15 price in india", "weather in pune today"
        ).forEach { q ->
            assertFalse("'$q' should NOT be degenerate", SearchQueryQuality.isDegenerate(q))
        }
        // A lone article IS degenerate by design.
        assertTrue(SearchQueryQuality.isDegenerate("a"))
    }

    @Test
    fun `case and surrounding whitespace do not hide a degenerate word`() {
        assertTrue(SearchQueryQuality.isDegenerate("  CURRENT "))
        assertTrue(SearchQueryQuality.isDegenerate("Latest"))
    }

    @Test
    fun `blank query is not this rule's case`() {
        assertFalse(SearchQueryQuality.isDegenerate(""))
        assertFalse(SearchQueryQuality.isDegenerate("   "))
    }

    // ---------------------------------------------------------------- fromGoal

    @Test
    fun `the gold goal derives the gold query`() {
        assertEquals(
            "current gold price",
            SearchQueryQuality.fromGoal("Fetch the current gold price")
        )
    }

    @Test
    fun `colloquial phrasing is stripped`() {
        assertEquals(
            "price of gold",
            SearchQueryQuality.fromGoal("cna u fetch the price of gold now")
        )
    }

    @Test
    fun `research goals keep their substance`() {
        assertEquals(
            "deep research report about solar energy growth in india",
            SearchQueryQuality.fromGoal(
                "write a deep research report about solar energy growth in india as a pdf"
            )
        )
    }

    @Test
    fun `questions become their subject`() {
        assertEquals(
            "gold price",
            SearchQueryQuality.fromGoal("what is the gold price")
        )
    }

    @Test
    fun `a goal with no framing survives unchanged`() {
        assertEquals(
            "solar energy growth india",
            SearchQueryQuality.fromGoal("solar energy growth india")
        )
    }

    @Test
    fun `a goal that strips to nothing falls back to the goal itself`() {
        assertEquals(
            "price",
            SearchQueryQuality.fromGoal("price")
        )
    }

    // v1.3.1 (the xauusd field report): "web fetch" compounds.

    @Test
    fun `web fetch framing is stripped`() {
        assertEquals(
            "price of xauusd",
            SearchQueryQuality.fromGoal("web fetch the price of xauusd")
        )
        assertEquals(
            "current gold price USD",
            SearchQueryQuality.fromGoal("websearch the current gold price USD")
        )
    }

    // v1.3.1 round 3 (the second gold lesson): the backend relevance gate.

    @Test
    fun `off-topic result sets are rejected`() {
        // The CI runner's Bing answered "current gold price today per gram"
        // with current.com + a Chinese dictionary (run 36978408264) —
        // the torn-out degenerate adjective echoing back is NOT relevance.
        assertFalse(
            SearchQueryQuality.resultsAreRelevant(
                "current gold price today per gram",
                listOf(
                    "current.com https://current.com",
                    "iciba.com https://www.iciba.com/word?w=current",
                    "YouTube https://youtube.com"
                )
            )
        )
    }

    @Test
    fun `on-topic result sets pass`() {
        assertTrue(
            SearchQueryQuality.resultsAreRelevant(
                "current gold price today per gram",
                listOf(
                    "Live Price of Gold - 24-hour live gold rates https://livepriceofgold.com/",
                    "US Gold Price per Gram: \$134.55 USD Today https://www.livepriceofgold.com/usa-gold-price-per-gram.html"
                )
            )
        )
        // The CBSE test's query and its gov.in answer.
        assertTrue(
            SearchQueryQuality.resultsAreRelevant(
                "CBSE class 10 board exam dates 2026",
                listOf("CBSE Date Sheet 2026 cbse.gov.in")
            )
        )
    }

    @Test
    fun `queries with only generic tokens are ungated`() {
        // Nothing decisive to gate on — accept rather than over-reject.
        assertTrue(
            SearchQueryQuality.resultsAreRelevant(
                "current",
                listOf("anything at all https://example.com")
            )
        )
    }

    // v1.4.0 (run-37118014660, the India-VIX lesson): quorum matching.

    @Test
    fun `country page does not satisfy an index query`() {
        // "India VIX 5 year historical data" answered with wikipedia's India
        // COUNTRY page — "india" alone echoing is NOT relevance.
        assertFalse(
            SearchQueryQuality.resultsAreRelevant(
                "India VIX 5 year historical data analysis NSE",
                listOf(
                    "India - Wikipedia https://en.wikipedia.org/wiki/India",
                    "India | Britannica https://www.britannica.com/place/India"
                )
            )
        )
    }

    @Test
    fun `a real index page satisfies an index query`() {
        assertTrue(
            SearchQueryQuality.resultsAreRelevant(
                "India VIX 5 year historical data",
                listOf(
                    "India VIX - Wikipedia https://en.wikipedia.org/wiki/India_VIX — India VIX is a volatility index based on the NIFTY index"
                )
            )
        )
    }

    @Test
    fun `stemming lets dates match date sheet`() {
        // The CBSE acceptance case: "dates" vs "Date Sheet" — trailing-s stem.
        assertTrue(
            SearchQueryQuality.resultsAreRelevant(
                "CBSE class 10 board exam dates 2026",
                listOf("CBSE Date Sheet 2026 cbse.gov.in")
            )
        )
    }
}
