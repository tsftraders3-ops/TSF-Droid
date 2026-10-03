package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 round 21 (the 2026-10-03 field evidence): the harness's give-up
 * detection for number-seeking asks. The 20:11 gold screenshot shipped
 * "I wasn't able to pull an actual live XAU/USD number — the quote feed
 * returned only page listings rather than a price" as the FINAL reply after
 * two searches; these predicates are what let the harness push one guided
 * retry instead.
 */
class AnswerQualityTest {

    // ── isPriceLikeQuery ─────────────────────────────────────────────────

    @Test
    fun `the field asks are price-like`() {
        assertTrue(AnswerQuality.isPriceLikeQuery("price of Nvidia stock"))
        assertTrue(AnswerQuality.isPriceLikeQuery("ok tell me the price of xauusd"))
        assertTrue(AnswerQuality.isPriceLikeQuery("hey what's the price of gold"))
        assertTrue(AnswerQuality.isPriceLikeQuery("what's the share price of Taparia Tools"))
    }

    @Test
    fun `bare company names alone are not price-like`() {
        // Context can make "it's taparia tools" a stock ask, but the pure
        // predicate must not fire on every company mention — the goal text
        // carries "price"/"share"/"stock" when the user means a quote.
        assertFalse(AnswerQuality.isPriceLikeQuery("it's taparia tools"))
    }

    @Test
    fun `chatty asks are not price-like`() {
        assertFalse(AnswerQuality.isPriceLikeQuery("how are you today"))
        assertFalse(AnswerQuality.isPriceLikeQuery("tell me a fun fact"))
        assertFalse(AnswerQuality.isPriceLikeQuery("write me a poem about the sea"))
        assertFalse(AnswerQuality.isPriceLikeQuery("teach me about the Roman empire"))
    }

    // ── containsPriceFigure ──────────────────────────────────────────────

    @Test
    fun `dollar figures are detected`() {
        assertTrue(AnswerQuality.containsPriceFigure("NVDA is at $187.42 today"))
        assertTrue(AnswerQuality.containsPriceFigure("trading in the ~$180–$230 range"))
        assertTrue(AnswerQuality.containsPriceFigure("GC=F is at 4209.3 USD"))
    }

    @Test
    fun `rupee figures are detected`() {
        assertTrue(AnswerQuality.containsPriceFigure("Taparia Tools Ltd (BSE: 505685) — ₹15.50, down 4.96%"))
        assertTrue(AnswerQuality.containsPriceFigure("market cap ₹23.5 Cr"))
    }

    @Test
    fun `per-unit figures are detected`() {
        assertTrue(AnswerQuality.containsPriceFigure("gold at 4,209.3 USD per ounce"))
        assertTrue(AnswerQuality.containsPriceFigure("4156/oz on the spot market"))
    }

    @Test
    fun `prose without numbers is not a figure`() {
        assertFalse(AnswerQuality.containsPriceFigure("gold is trading in a range today"))
        assertFalse(AnswerQuality.containsPriceFigure("the quote feed returned only page listings"))
    }

    // ── isGiveUpAnswer ────────────────────────────────────────────────────

    @Test
    fun `the exact field give-up replies are detected`() {
        // 20:11 gold screenshot
        assertTrue(
            AnswerQuality.isGiveUpAnswer(
                "I wasn't able to pull an actual live XAU/USD number — the quote feed " +
                    "returned only page listings rather than a price, so I have no figure to " +
                    "quote you with confidence right now."
            )
        )
        // 20:19 Nvidia screenshot
        assertTrue(
            AnswerQuality.isGiveUpAnswer(
                "I can't pull a live quote right now (no tool access in this session), " +
                    "but here's what I know as a reference point."
            )
        )
        // 20:22 Nvidia screenshot
        assertTrue(
            AnswerQuality.isGiveUpAnswer(
                "I don't have a live quote feed available right now, so I can't give you " +
                    "a verified current number for NVIDIA (NVDA)."
            )
        )
    }

    @Test
    fun `grounded answers are never give-ups`() {
        assertFalse(AnswerQuality.isGiveUpAnswer("Kitco shows gold at $2,109.30 per ounce right now."))
        assertFalse(
            AnswerQuality.isGiveUpAnswer(
                "Taparia Tools Ltd (BSE: 505685) — ₹15.50, down 4.96% on the last close."
            )
        )
    }

    @Test
    fun `a partial-failure reply that still delivers a figure is not a give-up`() {
        assertFalse(
            AnswerQuality.isGiveUpAnswer(
                "The live-rates feed couldn't find the ticker, but the BSE page shows ₹15.50."
            )
        )
    }

    @Test
    fun `a plain conversational answer is not a give-up`() {
        assertFalse(AnswerQuality.isGiveUpAnswer("Sure! Here's a fun fact about gold."))
        assertFalse(AnswerQuality.isGiveUpAnswer(""))
    }
}
