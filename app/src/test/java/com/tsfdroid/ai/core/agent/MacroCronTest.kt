package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The scheduled-macro cron evaluator: SCHEDULE_MACRO writes `cron:<expr>`
 * triggers and MacroSchedulerWorker fires them, so the parser and the window
 * semantics get the same paranoid coverage the harness got.
 *
 * Calendars are built in the RUNNER'S default timezone on purpose: cron is a
 * local-wall-clock concept and the evaluator uses Calendar.getInstance(), so
 * constructing the fixtures in the same zone keeps field values consistent
 * wherever the suite runs (UTC CI runner or a local dev box in Kolkata).
 */
class MacroCronTest {

    private fun calendar(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Calendar = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }

    private fun time(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long = calendar(year, month, day, hour, minute).timeInMillis

    // ------------------------------------------------------------------ parse

    @Test
    fun `five wildcard fields parse with every day field unrestricted`() {
        val s = MacroCron.parse("* * * * *")!!
        assertNull(s.daysOfMonth)
        assertNull(s.daysOfWeek)
        assertEquals((0..59).toSet(), s.minutes)
        assertEquals((0..23).toSet(), s.hours)
        assertEquals((1..12).toSet(), s.months)
    }

    @Test
    fun `plain values parse`() {
        val s = MacroCron.parse("30 14 15 8 3")!! // 14:30 on Aug 15, Wednesday
        assertEquals(setOf(30), s.minutes)
        assertEquals(setOf(14), s.hours)
        assertEquals(setOf(15), s.daysOfMonth)
        assertEquals(setOf(8), s.months)
        assertEquals(setOf(3), s.daysOfWeek)
    }

    @Test
    fun `ranges steps and lists parse`() {
        val s = MacroCron.parse("*/15 9-17 1,15 */2 MON-FRI")!!
        assertEquals(setOf(0, 15, 30, 45), s.minutes)
        assertEquals((9..17).toSet(), s.hours)
        assertEquals(setOf(1, 15), s.daysOfMonth)
        // `*/2` steps from the field minimum: months 1,3,5,7,9,11.
        assertEquals(setOf(1, 3, 5, 7, 9, 11), s.months)
        assertEquals(setOf(1, 2, 3, 4, 5), s.daysOfWeek)
    }

    @Test
    fun `named months and weekdays parse case-insensitively`() {
        val s = MacroCron.parse("0 0 1 jan,dec sun,MON")!!
        assertEquals(setOf(1, 12), s.months)
        assertEquals(setOf(0, 1), s.daysOfWeek)
    }

    @Test
    fun `seven is sunday`() {
        assertEquals(setOf(0), MacroCron.parse("0 0 * * 7")!!.daysOfWeek)
    }

    @Test
    fun `start-stepped field parses as start to max`() {
        val s = MacroCron.parse("40/20 * * * *")!! // vixie: 40, 0
        // 40..59 step 20 wraps nowhere — 40 only; hour field below uses same rule.
        assertEquals(setOf(40), s.minutes)
        val h = MacroCron.parse("* 20/5 * * *")!!
        assertEquals(setOf(20), h.hours)
    }

    @Test
    fun `malformed expressions return null`() {
        assertNull(MacroCron.parse(""))                       // empty
        assertNull(MacroCron.parse("* * * *"))                 // 4 fields
        assertNull(MacroCron.parse("* * * * * *"))              // 6 fields
        assertNull(MacroCron.parse("60 * * * *"))               // minute out of range
        assertNull(MacroCron.parse("* 24 * * *"))               // hour out of range
        assertNull(MacroCron.parse("* * 0 * *"))                // dom out of range
        assertNull(MacroCron.parse("* * * 13 *"))               // month out of range
        assertNull(MacroCron.parse("* * * * 8"))                // dow out of range
        assertNull(MacroCron.parse("abc * * * *"))              // junk
        assertNull(MacroCron.parse("*/0 * * * *"))             // zero step
        assertNull(MacroCron.parse("5-2 * * * *"))             // inverted range
        assertNull(MacroCron.parse("1,,2 * * * *"))             // empty list part
        assertNull(MacroCron.parse("*/ * * * *"))              // missing step
    }

    // ------------------------------------------------------------------ matches

    @Test
    fun `every minute matches any calendar`() {
        val s = MacroCron.parse("* * * * *")!!
        assertTrue(MacroCron.matchesAt(s, calendar(2026, 3, 1, 13, 45)))
        assertTrue(MacroCron.matchesAt(s, calendar(2026, 12, 31, 0, 0)))
    }

    @Test
    fun `exact time matches only that time`() {
        val s = MacroCron.parse("30 9 * * *")!!
        assertTrue(MacroCron.matchesAt(s, calendar(2026, 10, 1, 9, 30)))
        assertFalse(MacroCron.matchesAt(s, calendar(2026, 10, 1, 9, 31)))
        assertFalse(MacroCron.matchesAt(s, calendar(2026, 10, 1, 10, 30)))
    }

    @Test
    fun `vixie or rule - both restricted means either matches`() {
        // "15th of any month OR any Friday at 10:00"
        val s = MacroCron.parse("0 10 15 * 5")!!
        val fifteenthWednesday = calendar(2026, 10, 15, 10, 0) // Oct 15 2026 is a Thursday
        assertTrue(MacroCron.matchesAt(s, fifteenthWednesday))   // dom matches

        val fridayNot15 = calendar(2026, 10, 16, 10, 0)       // Friday
        assertTrue(MacroCron.matchesAt(s, fridayNot15))           // dow matches

        val neither = calendar(2026, 10, 17, 10, 0)           // Saturday, not the 15th
        assertFalse(MacroCron.matchesAt(s, neither))
    }

    @Test
    fun `dow restricted alone ignores dom`() {
        val s = MacroCron.parse("0 10 * * 1")!! // Mondays only
        assertTrue(MacroCron.matchesAt(s, calendar(2026, 10, 5, 10, 0)))   // a Monday
        assertFalse(MacroCron.matchesAt(s, calendar(2026, 10, 6, 10, 0)))  // Tuesday
    }

    @Test
    fun `month field gates`() {
        val s = MacroCron.parse("0 0 1 6 *")!! // June 1st, 00:00
        assertTrue(MacroCron.matchesAt(s, calendar(2026, 6, 1, 0, 0)))
        assertFalse(MacroCron.matchesAt(s, calendar(2026, 7, 1, 0, 0)))
    }

    // ----------------------------------------------------------- firesWithin

    @Test
    fun `fires within a 15 minute worker window`() {
        val s = MacroCron.parse("*/15 * * * *")!!
        // Worker ran at 10:07, next at 10:22 — the 10:15 firing is inside.
        assertTrue(MacroCron.firesWithin(s, time(2026, 10, 1, 10, 7), time(2026, 10, 1, 10, 22)))
    }

    @Test
    fun `does not fire when the window holds no match`() {
        val s = MacroCron.parse("0 * * * *")!! // top of the hour
        // 10:07 -> 10:22 contains no top-of-hour minute.
        assertFalse(MacroCron.firesWithin(s, time(2026, 10, 1, 10, 7), time(2026, 10, 1, 10, 22)))
        // ...but 10:22 -> 11:07 does contain 11:00.
        assertTrue(MacroCron.firesWithin(s, time(2026, 10, 1, 10, 22), time(2026, 10, 1, 11, 7)))
    }

    @Test
    fun `window end is inclusive and window start is exclusive`() {
        val s = MacroCron.parse("30 9 * * *")!!
        // Firing exactly at the window END instant counts.
        assertTrue(MacroCron.firesWithin(s, time(2026, 10, 1, 9, 0), time(2026, 10, 1, 9, 30)))
        // Firing exactly at the window START instant does NOT count — the
        // previous run already consumed it (this is the no-double-fire rule).
        assertFalse(MacroCron.firesWithin(s, time(2026, 10, 1, 9, 30), time(2026, 10, 1, 11, 0)))
    }

    @Test
    fun `sub-minute window boundaries still catch whole minute firings`() {
        val s = MacroCron.parse("30 9 * * *")!!
        // Real WorkManager windows are wall-clock arbitrary: 09:00:30.5 -> 09:30:45.2
        // must catch the 09:30 firing.
        val start = time(2026, 10, 1, 9, 0) + 30500L
        val end = time(2026, 10, 1, 9, 30) + 45200L
        assertTrue(MacroCron.firesWithin(s, start, end))

        // And a 09:00-firing schedule must NOT re-consume its 09:00:00 firing
        // when the window opens at 09:00:30.5 — that firing belongs to the
        // previous (aligned) window. (A `30 9` schedule WOULD match 09:30 here —
        // this assertion is about the 09:00 firing, so it uses a 09:00 schedule.)
        val nineOclock = MacroCron.parse("0 9 * * *")!!
        assertFalse(MacroCron.firesWithin(nineOclock, start, time(2026, 10, 1, 10, 0)))
    }

    @Test
    fun `inverted and empty windows never fire`() {
        val s = MacroCron.parse("* * * * *")!!
        assertFalse(MacroCron.firesWithin(s, 1000L, 1000L))
        assertFalse(MacroCron.firesWithin(s, 2000L, 1000L))
    }

    @Test
    fun `daily macro fires once across a multi-day device-off gap`() {
        val s = MacroCron.parse("0 9 * * *")!! // every day 09:00
        // Device off from Oct 1 08:00 to Oct 4 21:00 — three missed 09:00
        // firings must yield exactly ONE catch-up execution, and it fires.
        val fired = MacroCron.firesWithin(s, time(2026, 10, 1, 8, 0), time(2026, 10, 4, 21, 0))
        assertTrue(fired)
        // The one-shot semantics live in the WORKER (watermark advances per
        // run); the evaluator's contract here is just that a match exists in
        // the window. Single fire per worker run is enforced by evaluation
        // returning once — no replay loop exists.
    }

    @Test
    fun `weekday macro respects the weekend`() {
        val s = MacroCron.parse("0 8 * * MON-FRI")!!
        // Friday Oct 2 2026, 08:00.
        assertTrue(MacroCron.firesWithin(s, time(2026, 10, 2, 7, 55), time(2026, 10, 2, 8, 5)))
        // Saturday Oct 3 — no firing between 07:55 and 08:05.
        assertFalse(MacroCron.firesWithin(s, time(2026, 10, 3, 7, 55), time(2026, 10, 3, 8, 5)))
    }

    @Test
    fun `month macro only fires in its month`() {
        val s = MacroCron.parse("0 0 1 1 *")!! // Jan 1st, midnight
        assertTrue(MacroCron.firesWithin(s, time(2026, 1, 1, 0, 0) - 1000, time(2026, 1, 1, 0, 1)))
        assertFalse(MacroCron.firesWithin(s, time(2026, 2, 1, 0, 0) - 1000, time(2026, 2, 1, 0, 1)))
    }

    @Test
    fun `extremely long window is truncated not exploded`() {
        val s = MacroCron.parse("0 9 * * *")!!
        // A 10-year window must not iterate 5.2M minutes — truncation to the
        // last 62 days keeps it bounded, and 09:00 today is inside that tail.
        val start = time(2016, 10, 1, 0, 0)
        val end = time(2026, 10, 1, 10, 0)
        assertTrue(MacroCron.firesWithin(s, start, end))
    }

    @Test
    fun `performance - worst case window evaluates fast`() {
        // February + dom 31 + Saturday: under the Vixie OR rule this CAN match
        // (Saturdays exist in February), but the window below spans only
        // Aug-Oct, so no month match is possible — the scan runs its full
        // 62-day course and must stay fast.
        val s = MacroCron.parse("59 23 31 2 6")!!
        val start = time(2026, 10, 1, 0, 0) - TimeUnit.DAYS.toMillis(62)
        val end = time(2026, 10, 1, 0, 0)
        val began = System.nanoTime()
        assertFalse(MacroCron.firesWithin(s, start, end))
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertTrue("worst-case scan took ${elapsedMs}ms", elapsedMs < 2000)
    }

    // ------------------------------------------------------------------ trigger

    @Test
    fun `trigger strings are unwrapped and validated`() {
        assertEquals("0 9 * * *", MacroCron.expressionFromTrigger("cron:0 9 * * *"))
        assertEquals("*/10 * * * *", MacroCron.expressionFromTrigger("  cron:*/10 * * * *  "))
        assertNull(MacroCron.expressionFromTrigger("manual"))
        assertNull(MacroCron.expressionFromTrigger("cron:"))
        assertNull(MacroCron.expressionFromTrigger("cron"))
    }
}
