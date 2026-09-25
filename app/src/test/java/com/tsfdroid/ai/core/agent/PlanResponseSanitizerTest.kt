package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the v1.0.4 plan-answer hardening: reasoning blocks stripped,
 * balanced JSON recovered from prose, and prose replies classified into the
 * CHAT / ASK_USER action protocol instead of failing the planning turn.
 *
 * The fixtures mirror real free-tier shapes observed on
 * mimo-v2.6-flash-free (reasoning deltas + narrated JSON) during the
 * v1.0.3 field failures.
 */
class PlanResponseSanitizerTest {

    // --- stripReasoningBlocks ---

    @Test
    fun `closed think blocks are removed wherever they appear`() {
        val raw = "<think>Let me plan. User wants YouTube.</think>{\"goal\":\"play\"}"
        assertEquals("{\"goal\":\"play\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `reasoning before and after the plan is stripped`() {
        val raw = "<think>a</think>{\"goal\":\"x\"}<think>double check</think>"
        assertEquals("{\"goal\":\"x\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `unterminated think block drops everything from the tag on`() {
        val raw = "{\"goal\":\"keep\"}\n<think>stream cut mid-thought"
        assertEquals("{\"goal\":\"keep\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `reasoning tag variant is also stripped`() {
        val raw = "<reasoning>why</reasoning>{\"goal\":\"y\"}"
        assertEquals("{\"goal\":\"y\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `text without thinking passes through`() {
        assertEquals("{\"goal\":\"z\"}", PlanResponseSanitizer.stripReasoningBlocks("{\"goal\":\"z\"}"))
    }

    // --- extractFirstJsonObject ---

    @Test
    fun `plan narrated before prose is recovered`() {
        val raw = "Sure, here is the plan to open YouTube:\n{\"goal\":\"play\",\"steps\":[{\"stepId\":\"s1\"}]}"
        assertEquals(
            "{\"goal\":\"play\",\"steps\":[{\"stepId\":\"s1\"}]}",
            PlanResponseSanitizer.extractFirstJsonObject(raw)
        )
    }

    @Test
    fun `braces inside string literals do not break the depth scan`() {
        val raw = "prefix {\"a\":\"curly } brace\",\"b\":{\"c\":\"\\\\\"}} suffix"
        assertEquals(
            "{\"a\":\"curly } brace\",\"b\":{\"c\":\"\\\\\"}}",
            PlanResponseSanitizer.extractFirstJsonObject(raw)
        )
    }

    @Test
    fun `trailing prose after the object is not included`() {
        val raw = "{\"a\":1} Hope that helps!"
        assertEquals("{\"a\":1}", PlanResponseSanitizer.extractFirstJsonObject(raw))
    }

    @Test
    fun `unbalanced object returns null`() {
        assertNull(PlanResponseSanitizer.extractFirstJsonObject("speech { without end"))
        assertNull(PlanResponseSanitizer.extractFirstJsonObject("no braces at all"))
    }

    @Test
    fun `first object wins when two are present`() {
        val raw = "{\"first\":true} then {\"second\":true}"
        assertEquals("{\"first\":true}", PlanResponseSanitizer.extractFirstJsonObject(raw))
    }

    // --- classifyProseReply ---

    @Test
    fun `clarifying question routes to ASK_USER`() {
        val (action, params) = PlanResponseSanitizer.classifyProseReply(
            "Sure — can call someone if you tell me the name. Who should I call?"
        )!!
        assertEquals("ASK_USER", action)
        assertTrue(params["question"]!!.endsWith("Who should I call?"))
    }

    @Test
    fun `conversational answer routes to CHAT`() {
        val (action, params) = PlanResponseSanitizer.classifyProseReply(
            "I can open YouTube for you whenever you like."
        )!!
        assertEquals("CHAT", action)
        assertTrue(params["response"]!!.startsWith("I can open YouTube"))
    }

    @Test
    fun `json-shaped text is never classified as prose`() {
        assertNull(PlanResponseSanitizer.classifyProseReply("{\"broken\": true"))
        assertNull(PlanResponseSanitizer.classifyProseReply("[1, 2"))
    }

    @Test
    fun `blank text yields no classification`() {
        assertNull(PlanResponseSanitizer.classifyProseReply("   \n  "))
    }

    @Test
    fun `long prose is truncated to the parameter budget`() {
        val long = "word ".repeat(200)
        val (_, params) = PlanResponseSanitizer.classifyProseReply(long)!!
        assertTrue(params.values.first().length <= 400)
    }
}
