package com.tsfdroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 round 21 (the 2026-10-03 field evidence, screenshot 20:16): the
 * green "Speaking:" status line read "Speaking: **Taparia Tools Ltd (BSE:
 * 5056…" — literal markdown in status text, and the phone's TTS SPOKE the
 * asterisks. [SpeechText.forSpeech] is the choke point every spoken/status
 * text passes through.
 */
class SpeechTextTest {

    @Test
    fun `bold and italic markers are stripped, words survive`() {
        val out = SpeechText.forSpeech("**Taparia Tools Ltd (BSE: 505685)** — ₹15.50, down 4.96%.")
        assertFalse(out.contains("**"))
        assertFalse(out.contains("*"))
        assertTrue(out.contains("Taparia Tools Ltd (BSE: 505685)"))
        assertTrue(out.contains("₹15.50"))
    }

    @Test
    fun `headings and list markers are stripped`() {
        val out = SpeechText.forSpeech(
            "## Market Summary\n- **Market cap:** ₹23.5 Cr\n1. first point\n2. second point"
        )
        assertFalse(out.contains("##"))
        assertFalse(out.contains("- **"))
        assertTrue(out.contains("Market Summary"))
        assertTrue(out.contains("Market cap:"))
        assertTrue(out.contains("first point"))
    }

    @Test
    fun `urls become the word link`() {
        val out = SpeechText.forSpeech(
            "See https://www.screener.in/company/505685/ for the full picture."
        )
        assertFalse(out.contains("http"))
        assertTrue(out.contains("link"))
    }

    @Test
    fun `markdown links keep only the label`() {
        val out = SpeechText.forSpeech("Read [the docs](https://docs.example.com) now.")
        assertTrue(out.contains("the docs"))
        assertFalse(out.contains("https://"))
    }

    @Test
    fun `fenced code blocks become a placeholder`() {
        val out = SpeechText.forSpeech("Here:\n```kotlin\nval x = 1\n```\nDone.")
        assertFalse(out.contains("val x"))
        assertFalse(out.contains("```"))
        assertTrue(out.contains("code block"))
    }

    @Test
    fun `horizontal rules become a pause`() {
        val out = SpeechText.forSpeech("Intro\n---\nConclusion")
        assertFalse(out.contains("---"))
        assertTrue(out.contains("Intro"))
        assertTrue(out.contains("Conclusion"))
    }

    @Test
    fun `inline code backticks are stripped`() {
        val out = SpeechText.forSpeech("Run `adb logcat` to see logs.")
        assertFalse(out.contains("`"))
        assertTrue(out.contains("adb logcat"))
    }

    @Test
    fun `the exact field speaking line is cleaned`() {
        // Screenshot 20:16: "Speaking: **Taparia Tools Ltd (BSE: 5056..."
        val out = SpeechText.forSpeech("**Taparia Tools Ltd (BSE: 505685) — ₹15.50, down 4.96%**")
        assertEquals("Taparia Tools Ltd (BSE: 505685) — ₹15.50, down 4.96%", out)
    }

    @Test
    fun `blank and plain text pass through untouched`() {
        assertEquals("", SpeechText.forSpeech(""))
        assertEquals("just plain words", SpeechText.forSpeech("just plain words"))
    }

    @Test
    fun `absurdly long speech is cut at a sentence boundary`() {
        val long = ("This is one sentence. ".repeat(600)) // ~15k chars
        val out = SpeechText.forSpeech(long)
        assertTrue(out.length <= 4_001)
        assertTrue(out.endsWith(".") || out.length <= 4_000)
    }

    @Test
    fun `multiple spaces and blank runs collapse`() {
        val out = SpeechText.forSpeech("a    b\n\n\n\n\nc")
        assertFalse(out.contains("    "))
        assertFalse(out.contains("\n\n\n"))
    }
}
