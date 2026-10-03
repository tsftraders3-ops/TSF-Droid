package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0: the Hermes answer engine — the deterministic layers of the
 * FINAL-ANSWER contract, unit-pinned. Field evidence (2026-10-03): the
 * raw "Top web results" listing and "PDF created: /storage/..." paths
 * were pasted as the chat reply; the engine must detect, reject, and
 * deterministically replace them when the synthesis tier is unreachable.
 */
class AnswerEngineTest {

    private fun step(action: String, result: String?, status: StepStatus = StepStatus.COMPLETED) =
        PlanStep(
            stepId = "$action-1", order = 0, description = "$action step",
            action = action, params = emptyMap(), status = status, result = result
        )

    private val goldListing = "Top web results for 'XAUUSD current price gold spot today':\n" +
        "1. Gold spot price today (XAU/USD) — \$4,199.40/oz (-0.07%) | NowPrice — Live XAU/USD gold spot price\n" +
        "   https://nowprice.io/gold/spot\n" +
        "2. XAUUSD Chart — Gold Spot Price Today — TradingView — Follow live gold price chart\n" +
        "   https://www.tradingview.com/symbols/XAUUSD/\n" +
        "3. XAU/USD | Gold Spot US Dollar Price - Investing.com — Live Gold Spot price\n" +
        "   https://www.investing.com/currencies/xau-usd"

    @Test
    fun `raw search listing is detected as dump`() {
        assertTrue(AnswerEngine.looksLikeRawDump(goldListing))
        assertTrue(AnswerEngine.looksLikeRawDump("Search results for 'india vix':\n1. a\n2. b\n3. c"))
    }

    @Test
    fun `synthesized prose is not a dump`() {
        assertFalse(
            AnswerEngine.looksLikeRawDump(
                "XAU/USD (gold) is at \$4,199.40 per ounce right now, down 0.07% on the day. " +
                    "Sources: nowprice.io, investing.com."
            )
        )
        assertFalse(AnswerEngine.looksLikeRawDump("Your alarm is set for 7:00 AM."))
        assertFalse(AnswerEngine.looksLikeRawDump(""))
    }

    @Test
    fun `bare url density is detected`() {
        val linky = "check these out https://a.com/x https://b.com/y https://c.com/z " +
            "https://d.com/w https://e.com/v more links here"
        assertTrue(AnswerEngine.looksLikeRawDump(linky))
    }

    @Test
    fun `numbered url entries detected`() {
        val dump = "1. thing https://a.com\n2. other https://b.com\n3. third https://c.com"
        assertTrue(AnswerEngine.looksLikeRawDump(dump))
    }

    @Test
    fun `data plan needs synthesis`() {
        assertTrue(AnswerEngine.needsSynthesis(listOf(step("WEB_SEARCH", goldListing))))
        assertTrue(AnswerEngine.needsSynthesis(listOf(step("CHECK_STOCK", goldListing))))
        assertTrue(AnswerEngine.needsSynthesis(listOf(step("GET_NEWS", goldListing))))
    }

    @Test
    fun `single clean data answer skips synthesis for speed`() {
        // v1.4.0 speed gate: CHECK_STOCK's "GC=F is at 4209.3 USD (latest
        // session close)." is ALREADY the answer — instant delivery, no LLM
        // round-trip on top of a finished answer.
        assertFalse(
            AnswerEngine.needsSynthesis(
                listOf(step("CHECK_STOCK", "GC=F is at 4209.3 USD (latest session close)."))
            )
        )
        assertFalse(
            AnswerEngine.needsSynthesis(
                listOf(step("GET_WEATHER", "Weather in Pune: 28C, clear skies, feels like 30C."))
            )
        )
        // Multi-step research still synthesizes even with clean short steps.
        assertTrue(
            AnswerEngine.needsSynthesis(
                listOf(
                    step("WEB_SEARCH", "India VIX at 14.2 points today"),
                    step("FETCH_URL", "Historical data: 5y range 10.2 to 32.8")
                )
            )
        )
    }

    @Test
    fun `device action skips synthesis for speed`() {
        assertFalse(AnswerEngine.needsSynthesis(listOf(step("SET_ALARM", "Alarm set for 7:00"))))
        assertFalse(AnswerEngine.needsSynthesis(listOf(step("FLASHLIGHT", "Toggled"))))
        assertFalse(AnswerEngine.needsSynthesis(listOf(step("WEB_SEARCH", null, StepStatus.FAILED))))
    }

    @Test
    fun `long non-data result still needs synthesis`() {
        val longResult = "A".repeat(500)
        assertTrue(AnswerEngine.needsSynthesis(listOf(step("SUMMARIZE_URL", longResult))))
    }

    @Test
    fun `extractive answer pulls the price sentence with sources`() {
        val answer = AnswerEngine.extractiveAnswer(
            "what is the current price of xauusd",
            listOf(step("WEB_SEARCH", goldListing))
        )
        assertNotNull(answer)
        assertTrue("must carry the price figure: $answer", answer!!.contains("4,199"))
        assertFalse("must not quote the listing marker: $answer", answer.lowercase().contains("top web results"))
        assertTrue("must cite sources: $answer", answer.contains("Sources:"))
        assertTrue("must cite nowprice: $answer", answer.contains("nowprice.io"))
    }

    @Test
    fun `extractive answer for stock quote result`() {
        val answer = AnswerEngine.extractiveAnswer(
            "how is the stock doing",
            listOf(step("CHECK_STOCK", "^NSEI is at 24,530.10 INR (latest session close)."))
        )
        assertNotNull(answer)
        assertTrue(answer!!.contains("24,530"))
    }

    @Test
    fun `extractive answer null when nothing usable`() {
        assertNull(AnswerEngine.extractiveAnswer("goal", listOf(step("WEB_SEARCH", "   "))))
        assertNull(AnswerEngine.extractiveAnswer("goal", emptyList()))
    }

    @Test
    fun `extractive answer skips url-only results`() {
        val answer = AnswerEngine.extractiveAnswer(
            "goal",
            listOf(step("FETCH_URL", "https://only-links.com/a\nhttps://only-links.com/b"))
        )
        assertNull(answer)
    }

    @Test
    fun `step digest shortens paths and excludes chat steps`() {
        val digest = AnswerEngine.stepDigest(
            listOf(
                step("CHAT", "already delivered"),
                step("CREATE_PDF", "PDF created: /storage/emulated/0/Android/data/com.tsfdroid.ai/files/workspace/report.pdf")
            )
        )
        assertFalse(digest.contains("CHAT"))
        assertFalse("paths must be shortened to names: $digest", digest.contains("/storage/"))
        assertTrue(digest.contains("report.pdf"))
    }

    @Test
    fun `step digest caps each result`() {
        val digest = AnswerEngine.stepDigest(
            listOf(step("FETCH_URL", "x".repeat(50_000)))
        )
        assertTrue(digest.length < 2_000)
    }

    @Test
    fun `answer contract demands answer-first no dumps`() {
        assertTrue(AnswerEngine.ANSWER_CONTRACT.contains("ANSWER FIRST"))
        assertTrue(AnswerEngine.ANSWER_CONTRACT.contains("NO RAW DUMPS"))
        assertTrue(AnswerEngine.SYNTHESIS_RETRY_NUDGE.contains("PROSE"))
    }

    @Test
    fun `sanitizer interop - stepResultSummary of listing is dump`() {
        val summary = PlanResponseSanitizer.stepResultSummary(listOf(step("WEB_SEARCH", goldListing)))
        assertNotNull(summary)
        assertTrue(AnswerEngine.looksLikeRawDump(summary!!))
    }
}
