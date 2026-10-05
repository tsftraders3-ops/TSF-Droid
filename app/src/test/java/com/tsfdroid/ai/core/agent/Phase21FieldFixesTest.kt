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
}
