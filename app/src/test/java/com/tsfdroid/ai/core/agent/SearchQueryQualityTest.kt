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
}
