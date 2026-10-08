package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.1 (E2E run 37651287426, the b3 field regression): the live planner's
 * plan JSON carries a PARAPHRASED goal. That run rewrote
 * "create a markdown file called fieldfix_marker.md containing the exact
 * line TSF FIELD MARKER 77 followed by one short paragraph about gold
 * prices" into "Create fieldfix_marker.md containing 'TSF FIELD MARKER 77'
 * followed by one short paragraph about gold prices" — dropping the words
 * that carry the artifact signal.
 *
 * Every goal-shape heuristic downstream (the parse-time deferral gate, the
 * execution-time deferral gate, the concrete-artifact branch selector in
 * the deterministic synthesizer, the deliverable-name fallback) then read
 * a FILE-CREATION goal as a pure DATA goal: the synthesized plan was
 * WEB_SEARCH + CHECK_STOCK with no write step, the turn shipped a gold
 * market note, and the marker file was never written. The E2E caught it
 * honestly; these tests pin the contract that fixes it:
 *
 *   the user's own words are the ground truth for goal classification —
 *   a model paraphrase can never weaken them.
 */
class PlanGoalRestorationTest {

    private val userMessage = "create a markdown file called fieldfix_marker.md containing the " +
        "exact line TSF FIELD MARKER 77 followed by one short paragraph about gold prices"

    // The exact paraphrase the run-37651287426 planner emitted (recovered
    // from the run's PlanValidator log line).
    private val modelParaphrase = "Create fieldfix_marker.md containing 'TSF FIELD MARKER 77' " +
        "followed by one short paragraph about gold prices"

    // ------------------------------------------------ the hazard, documented

    @Test
    fun `the run-37651287426 paraphrase really does drop the artifact signal`() {
        // The hazard this fix exists for: the paraphrase alone no longer
        // reads as an artifact ask by ANY classifier...
        assertFalse(PlanResponseSanitizer.goalWantsArtifact(modelParaphrase))
        assertFalse(PlanResponseSanitizer.goalWantsConcreteArtifact(modelParaphrase))
        // ...while the user's own words carry it through both.
        assertTrue(PlanResponseSanitizer.goalWantsArtifact(userMessage))
        assertTrue(PlanResponseSanitizer.goalWantsConcreteArtifact(userMessage))
    }

    // ------------------------------------------------ the restoration itself

    @Test
    fun `the restored goal keeps the user's words over the paraphrase`() {
        assertEquals(
            userMessage,
            PlanResponseSanitizer.restoredGoal(userMessage, modelParaphrase)
        )
    }

    @Test
    fun `a blank user message falls back to the model goal, then the default`() {
        assertEquals(
            modelParaphrase,
            PlanResponseSanitizer.restoredGoal("", modelParaphrase)
        )
        assertEquals(
            modelParaphrase,
            PlanResponseSanitizer.restoredGoal("   ", modelParaphrase)
        )
        assertEquals(
            "User request",
            PlanResponseSanitizer.restoredGoal("", "")
        )
    }

    // ------------------------------- the gates fed the restored goal behave

    @Test
    fun `an all-CHAT plan against the restored goal defers as an artifact ask`() {
        val goal = PlanResponseSanitizer.restoredGoal(userMessage, modelParaphrase)

        // The parse-time gate: [CHAT] cannot satisfy a file-creation goal.
        assertTrue(PlanResponseSanitizer.planDefersGoal(listOf("CHAT"), goal))
        // And the deterministic synthesizer's search-only branch stays OFF
        // for it (synthesizeExecutablePlan only takes that branch when the
        // goal is NOT a concrete artifact ask).
        assertTrue(PlanResponseSanitizer.goalWantsConcreteArtifact(goal))
    }

    @Test
    fun `the requested filename survives both the paraphrase and the restoration`() {
        // The deliverable-name fallback (planWithWriteStepForGoal ->
        // deliverableNameForWriteStep) reads the plan goal; both forms must
        // yield the requested file.
        assertEquals(
            listOf("fieldfix_marker.md"),
            GoalContract.parseRequestedFilenames(modelParaphrase)
        )
        assertEquals(
            listOf("fieldfix_marker.md"),
            GoalContract.parseRequestedFilenames(userMessage)
        )
        // The synthesized WRITE step's requested line survives both forms
        // too (planWithWriteStepForGoal's regex).
        val lineFromUser = Regex(
            "(?i)(?:exact line|exact text|that says|saying|(?:the|this) line)\\s+[\"'\\u201c]?([A-Za-z0-9][A-Za-z0-9 _\\-/]{2,200}?)[\"'\\u201d]?(?=\\s+(?:followed|and then|and|then|plus|with)\\b|[.,;\\n]|$)"
        ).find(userMessage)?.groupValues?.get(1)?.trim()
        assertEquals("TSF FIELD MARKER 77", lineFromUser)
    }
}
