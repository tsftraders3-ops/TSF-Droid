package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 field-corpus regression (B2 — answer hygiene). Every poison fixture
 * below is VERBATIM from the 2026-10-05 field exports; every clean fixture is
 * a real answer from the same corpus that must survive UNCHANGED (the
 * anti-regression half of the bar: a hygiene pass that rewrites good answers
 * is a regression, not a fix).
 */
class AnswerHygieneTest {

    // ── Field poison: the six leading web_search syntax lines (chat-033518) ──

    @Test
    fun `field syntax dump - leading tool lines are stripped, real content survives`() {
        val field = "web_search {\"query\": \"Gemini 1.5 Flash pricing input output tokens per 1M\"}web_search {\"query\": \"Claude 3.5 Sonnet pricing input output tokens per 1M\"}\n" +
            "web_search {\"query\": \"GPT-4o-mini pricing input output tokens per 1M\"}\n" +
            "web_search {\"query\": \"Gemini 1.5 Flash cached token discount\"}\n" +
            "web_search {\"query\": \"OpenAI GPT-4o-mini rate limits rpm tpm\"}\n" +
            "web_search {\"query\": \"Anthropic Claude 3.5 Sonnet rate limits rpm tpm\"}I have gathered the following pricing and limits data from official sources:\n\n" +
            "**Gemini 1.5 Flash** (Google AI Studio / Vertex AI):\n" +
            "- Input: $0.075 per 1M tokens (as of 2025-2026 pricing)"
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(field)
        assertNotNull(sanitized)
        assertFalse(sanitized!!.contains("web_search {"))
        assertTrue(sanitized.startsWith("I have gathered"))
        assertTrue(sanitized.contains("Gemini 1.5 Flash"))
    }

    @Test
    fun `field syntax dump - syntax lines on separate lines stripped`() {
        val field = "web_search {\"query\": \"Gemini pricing\"}\n" +
            "fetch_url {\"url\": \"https://ai.google.dev/pricing\"}\n" +
            "Here is the pricing analysis you asked for.\n\nGemini Flash is cheap."
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(field)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.startsWith("Here is the pricing analysis"))
        assertFalse(sanitized.contains("web_search"))
    }

    // ── Field poison: the harness stub as the whole answer (chat-021145) ──

    @Test
    fun `field stub answer - whole-message stub returns null for the fallback ladder`() {
        val field = "I used web_search({\"query\": \"Reuters stocks oil dollar yields October 4 2026 close\"}) to work on this."
        assertNull(AnswerHygiene.sanitizeFinalAnswer(field))
        assertTrue(AnswerHygiene.isStubShaped(field))
    }

    @Test
    fun `leading stub sentence before real content is removed`() {
        val mixed = "I used web_search({\"query\": \"gold price\"}) to work on this.\n\nThe current gold price is Rs 14,780 per gram for 24K."
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(mixed)
        assertNotNull(sanitized)
        assertFalse(sanitized!!.contains("to work on this"))
        assertTrue(sanitized.contains("14,780"))
    }

    // ── Field poison: the JSON speech wrapper (chat-021218) ──

    @Test
    fun `field json wrapper - fenced envelope is unwrapped to the speech text`() {
        val field = "```json { \"speech\": \"Here are two regex options for Indian mobile numbers:\\n\\nSTRICT (recommended):\\n^(?:\\\\+91[\\\\s-]?)?[6-9]\\\\d{9}$\", \"type\": \"SIMPLE\", \"action\": null, \"params\": {} }"
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(field)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.startsWith("Here are two regex options"))
        assertFalse(sanitized.contains("speech"))
        assertFalse(sanitized.contains("```"))
    }

    @Test
    fun `bare json envelope without fence is also unwrapped`() {
        val field = "{\"response\": \"The 24K gold rate in Kolkata is Rs 14,780 per gram.\"}"
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(field)
        assertEquals("The 24K gold rate in Kolkata is Rs 14,780 per gram.", sanitized)
    }

    // ── Placeholder detection (the gold PDF template) ──

    @Test
    fun `field placeholder template is detected`() {
        val template = "COMPARISON TABLE\n24K | [today 24k price] | [last week 24k price] | [calc %]\n" +
            "SOURCES\n1. [Financial news site 1]"
        assertTrue(AnswerHygiene.hasUnfilledPlaceholders(template))
    }

    @Test
    fun `normal prose with brackets is not flagged`() {
        assertFalse(AnswerHygiene.hasUnfilledPlaceholders(
            "See the array [1, 2, 3] and the markdown [link](https://example.com)."
        ))
    }

    // ── Oversized fence collapse (the 45k inline dump) ──

    @Test
    fun `oversized fenced dump is collapsed with a save-it note`() {
        val huge = "```\n" + "A".repeat(9000) + "\n```"
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(huge)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.length < 2500)
        assertTrue(sanitized.contains("save the full version"))
    }

    @Test
    fun `small code fences pass through untouched`() {
        val code = "Here is your script:\n```python\nprint('hello')\n```"
        assertEquals(code, AnswerHygiene.sanitizeFinalAnswer(code))
    }

    // ── Anti-regression: clean field answers pass UNCHANGED ──

    @Test
    fun `clean field answer - gold reply passes untouched`() {
        val clean = "Today's 24K gold rate in Kolkata is Rs 14,780 per gram (Moneycontrol), " +
            "and 22K is Rs 13,538. The week-over-week change is about 1.4%."
        assertEquals(clean, AnswerHygiene.sanitizeFinalAnswer(clean))
    }

    @Test
    fun `clean field answer - honest confession passes untouched`() {
        val confession = "Honestly, I don't have a platform to report — I jumped ahead earlier. " +
            "No booking was actually completed on Uber, Ola, or anywhere else."
        assertEquals(confession, AnswerHygiene.sanitizeFinalAnswer(confession))
    }

    @Test
    fun `clean field answer - file confirmation passes untouched`() {
        val clean = "File saved at workout_routine.md — it's ready to open below."
        assertEquals(clean, AnswerHygiene.sanitizeFinalAnswer(clean))
    }

    @Test
    fun `code answer containing braces is not mistaken for an envelope`() {
        val codeAnswer = "Here's the merge script:\n\n```python\nimport json\n\ndef merge(a, b):\n" +
            "    return {**a, **b}\n```"
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(codeAnswer)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.contains("def merge"))
        assertTrue(sanitized.contains("{**a, **b}"))
    }

    @Test
    fun `answer mentioning a tool name in prose is not stripped`() {
        val clean = "I searched with the web search capability and found the price is $2,109."
        val sanitized = AnswerHygiene.sanitizeFinalAnswer(clean)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.contains("web search capability"))
    }
}
