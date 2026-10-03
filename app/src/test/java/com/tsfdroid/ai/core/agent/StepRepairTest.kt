package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The xauusd lesson (2026-10-02 field report): "web fetch the price of
 * xauusd" → FETCH_URL with a phrase as its url → fetch failed on three
 * strategies → the WEB_SEARCH fallback inherited phrase-in-`url` params →
 * schema validation demanded the missing `query` → NeedsInput dead end asking
 * the user for a query they had already given.
 *
 * These repairs are pure logic — they get the standalone-JVM treatment.
 */
class StepRepairTest {

    // ------------------------------------------------------------ isUrlShaped

    @Test
    fun `real web addresses are url-shaped`() {
        listOf(
            "goldprice.org",
            "https://example.com/page",
            "http://example.com",
            "https://sub.domain.co.uk/path?query=1",
            "example.com:8080/path",
            "localhost",
            "localhost:3000",
            "docs.google.com",
            "en.m.wikipedia.org/wiki/Gold"
        ).forEach { s ->
            assertTrue("'$s' should be url-shaped", StepRepair.isUrlShaped(s))
        }
    }

    @Test
    fun `phrases and bare words are not url-shaped`() {
        listOf(
            "the price of xauusd",
            "xauusd",
            "price of gold today",
            "",
            "   ",
            "https://xauusd",           // scheme but no TLD label
            "gold",                      // single label
            "web fetch the price",
            "gold price.org"             // space inside
        ).forEach { s ->
            assertFalse("'$s' should NOT be url-shaped", StepRepair.isUrlShaped(s))
        }
    }

    // ------------------------------------------------------------ fetchToSearch

    @Test
    fun `the exact field-report step becomes a search`() {
        // The screenshot: FETCH_URL {url: "the price of xauusd"} for the goal
        // "web fetch the price of xauusd".
        val repair = StepRepair.fetchToSearch(
            "FETCH_URL",
            mapOf("url" to "the price of xauusd"),
            "web fetch the price of xauusd"
        )
        assertNotNull(repair)
        assertEquals("WEB_SEARCH", repair!!.first)
        assertEquals("price of xauusd", repair.second["query"])
    }

    @Test
    fun `query-slot phrases are repaired too`() {
        // The action's url alias is `query` — a phrase there is the same bug.
        val repair = StepRepair.fetchToSearch(
            "FETCH_URL",
            mapOf("query" to "price of xauusd"),
            "web fetch the price of xauusd"
        )
        assertNotNull(repair)
        assertEquals("WEB_SEARCH", repair!!.first)
        assertEquals("price of xauusd", repair.second["query"])
    }

    @Test
    fun `bare symbol urls search for the symbol`() {
        val repair = StepRepair.fetchToSearch(
            "FETCH_URL",
            mapOf("url" to "xauusd"),
            "web fetch the price of xauusd"
        )
        assertNotNull(repair)
        assertEquals("WEB_SEARCH", repair!!.first)
        assertEquals("xauusd", repair.second["query"])
    }

    @Test
    fun `empty url slots derive the query from the goal`() {
        val repair = StepRepair.fetchToSearch(
            "SUMMARIZE_URL",
            emptyMap(),
            "web fetch the price of xauusd"
        )
        assertNotNull(repair)
        assertEquals("WEB_SEARCH", repair!!.first)
        assertEquals("price of xauusd", repair.second["query"])
    }

    @Test
    fun `real urls are left alone`() {
        listOf(
            "https://example.com/article",
            "goldprice.org",
            "localhost:8080"
        ).forEach { url ->
            assertNull(
                "a real url must not be rewritten",
                StepRepair.fetchToSearch(
                    "FETCH_URL",
                    mapOf("url" to url),
                    "web fetch the price of xauusd"
                )
            )
        }
    }

    @Test
    fun `non-fetch actions are not rewritten`() {
        assertNull(
            StepRepair.fetchToSearch(
                "WEB_SEARCH",
                mapOf("query" to "gold price"),
                "search gold price"
            )
        )
        assertNull(
            StepRepair.fetchToSearch(
                "OPEN_APP",
                mapOf("appName" to "Chrome"),
                "open chrome"
            )
        )
    }

    @Test
    fun `non-fetch actions with phrase urls are not rewritten`() {
        // OPEN_APP_OR_WEBSITE-style repair paths live in PlanValidator's own
        // unknown-action handling; StepRepair must not touch them.
        assertNull(
            StepRepair.fetchToSearch(
                "OPEN_APP",
                mapOf("url" to "the price of xauusd"),
                "web fetch the price of xauusd"
            )
        )
    }

    // ------------------------------------------------------------ repairSearchQuery

    @Test
    fun `the fallback-dispatch case - query hiding in the url slot`() {
        // The screenshot: the WEB_SEARCH fallback inherited {url: ...} only.
        val repaired = StepRepair.repairSearchQuery(
            mapOf("url" to "current gold price USD"),
            "fetch the current gold price"
        )
        assertNotNull(repaired)
        assertEquals("current gold price USD", repaired!!["query"])
    }

    @Test
    fun `blank query with no alias derives from the goal`() {
        val repaired = StepRepair.repairSearchQuery(
            emptyMap(),
            "web fetch the price of xauusd"
        )
        assertNotNull(repaired)
        assertEquals("price of xauusd", repaired!!["query"])
    }

    @Test
    fun `degenerate aliases fall back to the goal`() {
        // url: "current" — the alias is a torn-out adjective, use the goal.
        val repaired = StepRepair.repairSearchQuery(
            mapOf("url" to "current"),
            "fetch the current gold price"
        )
        assertNotNull(repaired)
        assertEquals("current gold price", repaired!!["query"])
    }

    // ------------------------------------------------------------ defeatistAskToSearch

    @Test
    fun `present queries are left alone`() {
        assertNull(
            StepRepair.repairSearchQuery(
                mapOf("query" to "current gold price USD"),
                "fetch the current gold price"
            )
        )
    }

    @Test
    fun `the third gold lesson - a defeatist single ask becomes a search`() {
        // Run 36987915019: the planner wrote ASK_USER("I'm not able to pull
        // live market data in this session...") for "cna u fetch the price
        // of gold now" — zero searches executed, the turn apologized.
        val repair = StepRepair.defeatistAskToSearch(
            "ASK_USER",
            mapOf("question" to "I'm not able to pull live market data in this session (tool limitations). What would you like me to do?"),
            "cna u fetch the price of gold now",
            totalSteps = 1
        )
        assertNotNull(repair)
        assertEquals("WEB_SEARCH", repair!!.first)
        assertEquals("price of gold", repair.second["query"])
    }

    @Test
    fun `legitimate asks are never rewritten`() {
        // cap21's exact ask: "which city do you prefer" — no defeatism.
        assertNull(
            StepRepair.defeatistAskToSearch(
                "ASK_USER",
                mapOf("question" to "Which city do you prefer between Pune and Mumbai?"),
                "ask me which city I prefer",
                totalSteps = 1
            )
        )
    }

    @Test
    fun `multi-step ask plans are left alone`() {
        // Asking mid-plan is normal agentic behavior — only the single-step
        // defeatist surrender is rewritten.
        assertNull(
            StepRepair.defeatistAskToSearch(
                "ASK_USER",
                mapOf("question" to "I can't decide without knowing your budget - what is it?"),
                "book me a trip",
                totalSteps = 3
            )
        )
    }

    @Test
    fun `non-ask actions are never rewritten`() {
        assertNull(
            StepRepair.defeatistAskToSearch(
                "WEB_SEARCH",
                mapOf("query" to "gold price"),
                "fetch gold price",
                totalSteps = 1
            )
        )
    }

    // ---------- v1.4.0: the second xauusd field lesson (run-37111962938) ----------

    @Test
    fun `google finance quote page with price goal becomes check stock`() {
        val repaired = StepRepair.fetchToQuote(
            "FETCH_URL",
            mapOf("url" to "https://www.google.com/finance/quote/XAU-USD"),
            "Fetch the current price of XAUUSD"
        )
        assertNotNull(repaired)
        assertEquals("CHECK_STOCK", repaired!!.first)
        assertEquals("XAUUSD", repaired.second["symbol"])
    }

    @Test
    fun `tradingview symbol url becomes check stock`() {
        val repaired = StepRepair.fetchToQuote(
            "FETCH_URL",
            mapOf("url" to "https://www.tradingview.com/symbols/XAUUSD/"),
            "web fetch the price of xauusd"
        )
        assertNotNull(repaired)
        assertEquals("CHECK_STOCK", repaired!!.first)
        assertEquals("XAUUSD", repaired.second["symbol"])
    }

    @Test
    fun `investing currency pair becomes check stock`() {
        val repaired = StepRepair.fetchToQuote(
            "SUMMARIZE_URL",
            mapOf("url" to "https://www.investing.com/currencies/xau-usd"),
            "what is the current rate of gold"
        )
        assertNotNull(repaired)
        assertEquals("XAUUSD", repaired!!.second["symbol"])
    }

    @Test
    fun `non-quote urls are untouched by the quote repair`() {
        assertNull(
            StepRepair.fetchToQuote(
                "FETCH_URL",
                mapOf("url" to "https://example.com/info"),
                "fetch the current price of gold"
            )
        )
        assertNull(
            StepRepair.fetchToQuote(
                "FETCH_URL",
                mapOf("url" to "https://www.google.com/search?q=gold"),
                "fetch the current price of gold"
            )
        )
    }

    @Test
    fun `quote page without data goal is untouched`() {
        assertNull(
            StepRepair.fetchToQuote(
                "FETCH_URL",
                mapOf("url" to "https://www.google.com/finance/quote/XAU-USD"),
                "explain how google finance pages are structured"
            )
        )
    }

    @Test
    fun `path-word tail means no repair - never guess a wrong ticker`() {
        // tail "finance" is blacklisted and goal-derived guessing is FORBIDDEN
        // ("price of gold" -> GOLD = Barrick Gold Corp, a real but WRONG ticker)
        assertNull(
            StepRepair.fetchToQuote(
                "FETCH_URL",
                mapOf("url" to "https://www.google.com/finance"),
                "fetch the current price of XAUUSD"
            )
        )
    }

    // v1.4.0 (run-37118014660): the poisoned-backend quote assist.

    @Test
    fun `gold price goal gains a check stock step`() {
        val step = StepRepair.priceGoalQuoteStep(
            "cna u fetch the price of gold now",
            listOf("WEB_SEARCH")
        )
        assertNotNull(step)
        assertEquals("CHECK_STOCK", step!!.first)
        assertEquals("XAU", step.second["symbol"])
    }

    @Test
    fun `bitcoin price goal gains a check stock step`() {
        val step = StepRepair.priceGoalQuoteStep(
            "Search the web for the current Bitcoin price in USD and tell me",
            listOf("WEB_SEARCH", "FETCH_URL")
        )
        assertNotNull(step)
        assertEquals("BTC-USD", step!!.second["symbol"])
    }

    @Test
    fun `xauusd goal maps to xau`() {
        val step = StepRepair.priceGoalQuoteStep(
            "what is the current price of xauusd",
            listOf("WEB_SEARCH")
        )
        assertNotNull(step)
        assertEquals("XAU", step!!.second["symbol"])
    }

    @Test
    fun `no quote step without a price word`() {
        assertNull(
            StepRepair.priceGoalQuoteStep(
                "write a report about gold mining",
                listOf("WEB_SEARCH")
            )
        )
    }

    @Test
    fun `no quote step for unlisted instruments`() {
        assertNull(
            StepRepair.priceGoalQuoteStep(
                "ok search for latest iphone price",
                listOf("WEB_SEARCH")
            )
        )
    }

    @Test
    fun `no quote step when check stock already planned`() {
        assertNull(
            StepRepair.priceGoalQuoteStep(
                "fetch the current price of gold",
                listOf("WEB_SEARCH", "CHECK_STOCK")
            )
        )
    }
}
