package com.tsfdroid.ai.core.agent

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Minimal 5-field cron evaluator for scheduled macros.
 *
 * The SCHEDULE_MACRO action stores `cron:<expression>` in MacroEntity.trigger
 * (for example `cron:` with an every-10-minute step, or `cron:` with 09:00 on
 * weekdays). This object parses and evaluates those expressions with the
 * semantics users expect from Vixie cron:
 *
 *  - Fields: minute (0-59), hour (0-23), day-of-month (1-31), month (1-12 or
 *    JAN-DEC), day-of-week (0-7 or SUN-SAT, where both 0 and 7 mean Sunday).
 *  - Each field accepts `*`, a plain value, a range `a-b`, a slash-step
 *    (with or without a range), and comma-separated lists of those.
 *  - If both day-of-month and day-of-week are restricted (not `*`), a match on
 *    either one is enough (the classic Vixie OR rule).
 *
 * Scheduling granularity is the [MacroSchedulerWorker] cadence (15 minutes):
 * an expression fires at most once per worker window, so a 5-minute step
 * behaves as "every 15 minutes". That keeps evaluations cheap and makes
 * catch-up after a device was off fire at most once per missed expression.
 */
object MacroCron {

    data class Schedule(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        /** null = unrestricted (`*`). */
        val daysOfMonth: Set<Int>?,
        val months: Set<Int>,
        /** null = unrestricted (`*`). Sunday normalized to 0. */
        val daysOfWeek: Set<Int>?
    )

    private val MONTH_NAMES = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    private val DAY_NAMES = mapOf(
        "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6
    )

    /**
     * Parses a 5-field cron expression. Returns null for anything malformed —
     * callers treat that as "never fire, log once" instead of guessing.
     */
    fun parse(expression: String): Schedule? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null

        val minutes = parseField(fields[0], 0, 59, null) ?: return null
        val hours = parseField(fields[1], 0, 23, null) ?: return null
        // A bare `*` means UNRESTRICTED for the two day fields (stored as
        // null) — a different thing from a parse error, which also returns
        // null but must abort the whole expression. Minutes/hours/months
        // have no unrestricted state: `*` there is simply the full range.
        val dom = if (fields[2] == "*") {
            null
        } else {
            parseField(fields[2], 1, 31, null) ?: return null
        }
        val months = parseField(fields[3], 1, 12, MONTH_NAMES) ?: return null
        val dow = if (fields[4] == "*") {
            null
        } else {
            parseField(fields[4], 0, 7, DAY_NAMES) ?: return null
        }

        return Schedule(
            minutes = minutes,
            hours = hours,
            daysOfMonth = dom,
            months = months,
            daysOfWeek = dow?.map { if (it == 7) 0 else it }?.toSet()
        )
    }

    /** True when the calendar minute pointed at by [cal] satisfies the schedule. */
    fun matchesAt(schedule: Schedule, cal: Calendar): Boolean {
        if (cal.get(Calendar.MINUTE) !in schedule.minutes) return false
        if (cal.get(Calendar.HOUR_OF_DAY) !in schedule.hours) return false
        if ((cal.get(Calendar.MONTH) + 1) !in schedule.months) return false

        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        // java.util.Calendar: SUNDAY=1 .. SATURDAY=7 — cron wants 0=Sunday.
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1

        val domRestricted = schedule.daysOfMonth != null
        val dowRestricted = schedule.daysOfWeek != null
        return when {
            domRestricted && dowRestricted ->
                dayOfMonth in schedule.daysOfMonth || dayOfWeek in schedule.daysOfWeek
            domRestricted -> dayOfMonth in schedule.daysOfMonth
            dowRestricted -> dayOfWeek in schedule.daysOfWeek
            else -> true
        }
    }

    /**
     * True when any minute in the half-open-until-closed window
     * (windowStartMs, windowEndMs] satisfies the schedule.
     *
     * Windows longer than [MAX_WINDOW_MS] (62 days) are truncated to their
     * tail so a device that was off for a season fires each macro at most
     * once instead of spinning through months of minutes.
     */
    fun firesWithin(schedule: Schedule, windowStartMs: Long, windowEndMs: Long): Boolean {
        if (windowEndMs <= windowStartMs) return false
        val start = maxOf(windowStartMs, windowEndMs - MAX_WINDOW_MS)

        val cal = Calendar.getInstance()
        cal.timeInMillis = windowEndMs
        // Walk whole minutes back from the window end so the boundary minute
        // that exactly equals windowEnd is included (window end is inclusive).
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > windowEndMs) {
            cal.add(Calendar.MINUTE, -1)
        }

        // (start, end] over whole-minute buckets: a firing at instant T is in
        // the window when T > windowStart and T <= windowEnd. Minutes already
        // consumed by the previous worker run (T <= windowStart) must NOT
        // fire again, so the loop stops at or before `start` — never past it.
        while (cal.timeInMillis > start) {
            if (matchesAt(schedule, cal)) return true
            cal.add(Calendar.MINUTE, -1)
        }
        return false
    }

    /** Extracts the cron body from a macro trigger like `cron:0 9 * * *`. */
    fun expressionFromTrigger(trigger: String): String? {
        // Trim first — trigger strings are written by SCHEDULE_MACRO
        // (`cron:<expr>`) but a hand-edited or imported value may carry
        // surrounding whitespace.
        val cleaned = trigger.trim()
        if (!cleaned.startsWith("cron:", ignoreCase = true)) return null
        val expr = cleaned.substringAfter(':').trim()
        return expr.takeIf { it.isNotEmpty() }
    }

    private fun parseField(
        raw: String,
        min: Int,
        max: Int,
        names: Map<String, Int>?
    ): Set<Int>? {
        if (raw.isEmpty()) return null
        // NOTE: a bare `*` must never reach this function — parse() guards
        // the two day fields for it (unrestricted ≠ error). For
        // minutes/hours/months the part-level `*` handling expands to the
        // full range, which is the correct meaning there.

        val values = mutableSetOf<Int>()
        for (part in raw.split(',')) {
            if (!parsePart(part, min, max, names, values)) return null
        }
        if (values.isEmpty()) return null
        return values
    }

    private fun parsePart(
        part: String,
        min: Int,
        max: Int,
        names: Map<String, Int>?,
        into: MutableSet<Int>
    ): Boolean {
        var body = part
        var step = 1
        val slash = part.indexOf('/')
        if (slash >= 0) {
            body = part.substring(0, slash)
            val stepText = part.substring(slash + 1)
            if (stepText.isEmpty()) return false
            step = stepText.toIntOrNull() ?: return false
            if (step <= 0) return false
        }

        val rangeStart: Int
        val rangeEnd: Int
        if (body == "*") {
            rangeStart = min
            rangeEnd = max
        } else {
            val dash = body.indexOf('-')
            if (dash >= 0) {
                val a = decodeValue(body.substring(0, dash), min, max, names) ?: return false
                val b = decodeValue(body.substring(dash + 1), min, max, names) ?: return false
                if (a > b) return false
                rangeStart = a
                rangeEnd = b
            } else {
                val v = decodeValue(body, min, max, names) ?: return false
                // No step at all: a plain value.
                if (slash < 0) {
                    into.add(v)
                    return true
                }
                // `a/n` means "starting at a, every n" to the end of the field
                // (Vixie semantics) — NOT just the single value a.
                rangeStart = v
                rangeEnd = max
            }
        }

        var v = rangeStart
        while (v <= rangeEnd) {
            into.add(v)
            v += step
        }
        return true
    }

    private fun decodeValue(
        text: String,
        min: Int,
        max: Int,
        names: Map<String, Int>?
    ): Int? {
        if (text.isEmpty()) return null
        val value = text.toIntOrNull() ?: names?.get(text.uppercase())
        if (value == null) return null
        // Day-of-week allows 7 as an alias for Sunday.
        val effectiveMax = if (names === DAY_NAMES) 7 else max
        if (value < min || value > effectiveMax) return null
        return value
    }

    private val MAX_WINDOW_MS = TimeUnit.DAYS.toMillis(62)
}
