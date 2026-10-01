package com.tsfdroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    @Test
    fun `parses plain seconds`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertEquals(60, DurationParser.parseToSeconds("60"))
    }

    @Test
    fun `parses second units`() {
        assertEquals(5, DurationParser.parseToSeconds("5s"))
        assertEquals(5, DurationParser.parseToSeconds("5 seconds"))
        assertEquals(30, DurationParser.parseToSeconds("30 sec"))
    }

    @Test
    fun `parses minute units`() {
        assertEquals(300, DurationParser.parseToSeconds("5 minutes"))
        assertEquals(120, DurationParser.parseToSeconds("2 minute"))
        assertEquals(60, DurationParser.parseToSeconds("1 min"))
    }

    @Test
    fun `parses hour units`() {
        assertEquals(3600, DurationParser.parseToSeconds("1 hour"))
        assertEquals(7200, DurationParser.parseToSeconds("2 hours"))
    }

    @Test
    fun `parses compact adjacent units`() {
        assertEquals(5400, DurationParser.parseToSeconds("1h30m"))
        assertEquals(90, DurationParser.parseToSeconds("1m30s"))
    }

    @Test
    fun `bare digit fallback ignores embedded numbers`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertNull(DurationParser.parseToSeconds("at 2pm yesterday"))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(DurationParser.parseToSeconds(""))
        assertNull(DurationParser.parseToSeconds("   "))
    }

    // v1.3.0 (upstream ticket #30): fractional amounts used to be silently
    // truncated — "2m59.56s" parsed as 176s because the integer-only capture
    // dropped the "59." ahead of the "s" and matched only "56s".
    @Test
    fun `parses fractional seconds in compact forms`() {
        assertEquals(180, DurationParser.parseToSeconds("2m59.56s"))
        assertEquals(181, DurationParser.parseToSeconds("3m0.9s"))
    }

    @Test
    fun `parses fractional hour and minute amounts`() {
        assertEquals(5400, DurationParser.parseToSeconds("1.5h"))
        assertEquals(90, DurationParser.parseToSeconds("1.5 minutes"))
    }

    @Test
    fun `fractional results round to nearest second and never reach zero`() {
        assertEquals(1, DurationParser.parseToSeconds("0.4s"))
        assertEquals(90, DurationParser.parseToSeconds("1.4999m"))
    }
}
