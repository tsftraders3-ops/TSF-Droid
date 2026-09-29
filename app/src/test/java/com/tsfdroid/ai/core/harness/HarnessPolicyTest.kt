package com.tsfdroid.ai.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 harness-policy unit tests: the output-limit continuation policy,
 * the effort budgets, and the activity-trace (de)serialization.
 */
class HarnessPolicyTest {

    // --- OutputContinuator.shouldContinue ---------------------------------

    @Test
    fun `continues on finish_reason length with budget left`() {
        assertTrue(
            OutputContinuator.shouldContinue(
                finishReason = "length",
                accumulated = "a partial answer",
                continuationsSoFar = 0,
                maxContinuations = 3
            )
        )
    }

    @Test
    fun `does not continue on stop or null finish reason`() {
        assertFalse(
            OutputContinuator.shouldContinue("stop", "text", 0, 3)
        )
        assertFalse(
            OutputContinuator.shouldContinue(null, "text", 0, 3)
        )
        // tool_calls finish: not a truncation signal.
        assertFalse(
            OutputContinuator.shouldContinue("tool_calls", "", 0, 3)
        )
    }

    @Test
    fun `continuation budget is a hard ceiling`() {
        val max = com.tsfdroid.ai.core.agent.HarnessLoop.MAX_CONTINUATIONS
        for (used in 0 until max) {
            assertTrue(
                "continuation $used of $max should be allowed",
                OutputContinuator.shouldContinue("length", "text", used, max)
            )
        }
        assertFalse(
            OutputContinuator.shouldContinue("length", "text", max, max)
        )
    }

    @Test
    fun `blank accumulated text never continues`() {
        assertFalse(
            OutputContinuator.shouldContinue("length", "", 0, 3)
        )
    }

    @Test
    fun `case insensitive length match`() {
        assertTrue(
            OutputContinuator.shouldContinue("LENGTH", "text", 0, 3)
        )
    }

    // --- OutputContinuator.trimRepeatedOverlap ----------------------------

    @Test
    fun `repeated tail overlap is trimmed`() {
        val previous = "The quantum model was proposed in 1985 and later refined."
        val continuation = "and later refined. Then by many teams over the years."
        val trimmed = OutputContinuator.trimRepeatedOverlap(previous, continuation)
        assertEquals(" Then by many teams over the years.", trimmed)
    }

    @Test
    fun `no overlap returns continuation untouched`() {
        val previous = "Completely different ending words here."
        val continuation = "A fresh start of the continuation."
        assertEquals(
            continuation,
            OutputContinuator.trimRepeatedOverlap(previous, continuation)
        )
    }

    @Test
    fun `empty inputs are safe`() {
        // No previous text -> nothing to overlap; continuation passes through.
        assertEquals("x", OutputContinuator.trimRepeatedOverlap("", "x"))
        // Empty continuation -> nothing to stitch; passes through as empty.
        assertEquals("", OutputContinuator.trimRepeatedOverlap("prev", ""))
    }

    // --- ActivitySteps (de)serialization ----------------------------------

    @Test
    fun `activity steps round-trip through json`() {
        val steps = listOf(
            ActivityStep(
                kind = ActivityStep.KIND_TOOL,
                label = "WEB_SEARCH",
                detail = "3 results",
                status = ActivityStep.STATUS_DONE
            ),
            ActivityStep(
                kind = ActivityStep.KIND_CONTINUATION,
                label = "Answer continued (auto)",
                status = ActivityStep.STATUS_RUNNING
            )
        )
        val encoded = ActivitySteps.encode(steps)
        assertTrue(!encoded.isNullOrBlank())
        val decoded = ActivitySteps.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals("WEB_SEARCH", decoded[0].label)
        assertEquals("3 results", decoded[0].detail)
        assertEquals(ActivityStep.STATUS_RUNNING, decoded[1].status)
    }

    @Test
    fun `empty and corrupt traces decode safely`() {
        assertNull(ActivitySteps.encode(emptyList()))
        assertTrue(ActivitySteps.decode(null).isEmpty())
        assertTrue(ActivitySteps.decode("").isEmpty())
        assertTrue(ActivitySteps.decode("not json at all").isEmpty())
    }
}
