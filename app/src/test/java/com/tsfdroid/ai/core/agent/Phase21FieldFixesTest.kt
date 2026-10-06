package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 field-corpus regression — Phase 21 bars B1/B5/B6/B7, the pieces that
 * live outside AnswerHygiene/GoalContract: routing, alias anchoring, artifact
 * word coverage, thinking joins, and the export schema v2 fields.
 */
class Phase21FieldFixesTest {

    // ── B6: the read-and-remember alias is verb-phrase anchored ──────────

    @Test
    fun `field morning-briefing query no longer trips the screen-read alias`() {
        val field = "check my calendar events for today, fetch the current weather " +
            "forecast for kolkata, read my last 5 unread emails, and get top 3 tech " +
            "headlines. cross-reference my meetings with the weather to see if any " +
            "outdoor travel clashes with rain, compile a morning briefing note titled " +
            "\"Daily Plan Oct 5\" with actionable priorities, save it to my notes, and " +
            "then start playing some acoustic lofi music on spotify."
        assertFalse(AliasResolver.isReadAndRememberRequest(field))
    }

    @Test
    fun `genuine read-and-save-screen requests still trip the alias`() {
        assertTrue(AliasResolver.isReadAndRememberRequest("read this screen and save the details"))
        assertTrue(AliasResolver.isReadAndRememberRequest("save this screen to notes"))
        assertTrue(AliasResolver.isReadAndRememberRequest("remember this screen"))
    }

    // ── P1-8: "save it as X" is an artifact ask ──────────────────────────

    @Test
    fun `field ai_policy goal is recognized as an artifact ask`() {
        val goal = "research the latest ai policy updates and save it as ai_policy_update.txt"
        assertTrue(PlanResponseSanitizer.goalWantsArtifact(goal))
        // A WEB_SEARCH-only plan against it DEFERS the goal (the v1.5.0 hole).
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(listOf("WEB_SEARCH"), goal)
        )
    }

    // ── B1: the unfulfillable-goal note ──────────────────────────────────

    @Test
    fun `field cab goal produces an unfulfillable-goal note`() {
        val goal = "book cab for yesterday 5pm wait no I mean tommrow 5am"
        val note = GoalContract.unfulfillableGoalNote(goal)
        assertNotNull(note)
        assertTrue(note!!.contains("nothing has been booked"))
        assertTrue(note.contains("booking"))
    }

    @Test
    fun `ordinary goals produce no unfulfillable note`() {
        assertNull(GoalContract.unfulfillableGoalNote("set an alarm for 7am"))
        assertNull(GoalContract.unfulfillableGoalNote("what is the gold price today"))
        assertNull(GoalContract.unfulfillableGoalNote("write a python script call scrap_titles.py"))
    }

    // ── B6: private first-person queries never go to a public engine ────

    @Test
    fun `field morning-briefing query yields only the public weather clause`() {
        val field = "check my calendar events for today, fetch the current weather " +
            "forecast for kolkata, read my last 5 unread emails, and get top 3 tech " +
            "headlines, then start playing some acoustic lofi music on spotify"
        val clause = GoalContract.publicSearchClause(field)
        assertNotNull(clause)
        assertFalse(clause!!.contains("my calendar"))
        assertFalse(clause.contains("my last 5 unread emails"))
        assertFalse(clause.contains("spotify"))
        assertTrue(clause.contains("weather"))
        assertTrue(clause.contains("kolkata"))
    }

    @Test
    fun `ordinary search queries pass through untouched`() {
        assertNull(GoalContract.publicSearchClause("current gold price in kolkata today"))
        assertNull(GoalContract.publicSearchClause("gemini 1.5 flash pricing per 1M tokens"))
    }

    // ── P2-4: thinking segments join with newlines ─────────────────────

    @Test
    fun `sentence-complete reasoning segments join with a newline`() {
        val joined = AnswerHygiene.joinThinkingSegments("Let me start.", "Let me plan this.")
        assertEquals("Let me start.\nLet me plan this.", joined)
    }

    @Test
    fun `mid-word deltas still glue seamlessly`() {
        assertEquals("Let me st", AnswerHygiene.joinThinkingSegments("Let me s", "t"))
        assertEquals("Let me thinking", AnswerHygiene.joinThinkingSegments("Let me think", "ing"))
    }

    // ── P1-5: SEND_SMS digit extraction (via GoalContract) ──────────────

    @Test
    fun `field sms phrase resolves to the embedded number`() {
        assertEquals("123", GoalContract.extractPhoneNumber("sms hi to the number 123"))
        assertNull(GoalContract.extractPhoneNumber("send it to rahul"))
    }

    // ── B5: the needs-input prompt copy is human ─────────────────────────

    @Test
    fun `needs-input answers never echo internal param names`() {
        val q = GoalContract.humanizeParamPrompt("CLICK_TEXT", "searchText",
            "I need the searchText to complete this. Text to find the field")
        assertFalse(q.contains("searchText"))
        assertTrue(q.startsWith("Which text"))
    }

    // ── Round 6 (run 37373954932): the blank-filePath fill (b3) ─────────

    @Test
    fun `field b3 goal fills the blank write-step filePath from the goal`() {
        // The VERBATIM E2E task whose model plan shipped an empty filePath
        // and parked the turn on "I need the filePath" for 10 minutes.
        val goal = "create a markdown file called fieldfix_marker.md containing the " +
            "exact line TSF FIELD MARKER 77 followed by one short paragraph about gold prices"
        assertEquals(
            "fieldfix_marker.md",
            GoalContract.deliverableNameForWriteStep(goal, emptyMap(), emptySet())
        )
    }

    @Test
    fun `an already-named write step is left alone`() {
        val goal = "create a markdown file called fieldfix_marker.md containing the marker"
        assertNull(
            GoalContract.deliverableNameForWriteStep(
                goal, mapOf("filePath" to "my_own_name.md"), emptySet()
            )
        )
    }

    @Test
    fun `a multi-file goal hands the second name to the second blank step`() {
        val goal = "compile a report called ondevice_llm_benchmark_2026.md and " +
            "save it as llm_perf_metrics.csv"
        assertEquals(
            "llm_perf_metrics.csv",
            GoalContract.deliverableNameForWriteStep(
                goal, emptyMap(), setOf("ondevice_llm_benchmark_2026.md")
            )
        )
    }

    @Test
    fun `a slug fallback only fires for clear artifact asks`() {
        // The 2-file field goal with NO blank write step stays null...
        assertNull(
            GoalContract.deliverableNameForWriteStep(
                "can you tell me the location where the export chats are saved",
                emptyMap(), emptySet()
            )
        )
        // ...and a slug-less goal never invents a name.
        assertNull(GoalContract.deliverableNameForWriteStep("", emptyMap(), emptySet()))
    }

    // ── Round 6: the claim audit ignores URLs (the gold answer) ──────────

    @Test
    fun `cited URLs are never claimed as promised files`() {
        // The EXACT shape from the run-37373954932 gold answer: a sources
        // footer citing goldprice.org/live-gold-price.html produced a false
        // "(Honesty note: I mentioned 'live-gold-price.html'...)". A URL is
        // a link, not a deliverable.
        val summary = "The current gold price is around \$2,650 per ounce.\n\n" +
            "Sources: https://www.kitco.com/price/precious-metals, " +
            "https://goldprice.org/live-gold-price.html"
        assertEquals(emptySet<String>(), GoalContract.claimedFilenamesIn(summary))
    }

    @Test
    fun `a genuinely promised-but-missing file is still claimed`() {
        // The field P1-6 contract survives the URL strip: "Here are both
        // files" with only one written must still get its honesty note.
        val summary = "Here are both files, complete and ready to use: " +
            "agent_frameworks.md and AgentKeepAliveManager.kt"
        assertEquals(
            setOf("agent_frameworks.md", "agentkeepalivemanager.kt"),
            GoalContract.claimedFilenamesIn(summary)
        )
    }

    @Test
    fun `markdown link targets are not claims either`() {
        val summary = "I wrote it up in [the guide](https://example.com/docs/guide.md) as requested."
        assertEquals(emptySet<String>(), GoalContract.claimedFilenamesIn(summary))
    }
}
