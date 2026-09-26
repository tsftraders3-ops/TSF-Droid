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
    fun `runaway prose is bounded to the 16k budget`() {
        val long = "word ".repeat(10_000)
        val (_, params) = PlanResponseSanitizer.classifyProseReply(long)!!
        assertTrue(params.values.first().length <= PlanResponseSanitizer.PROSE_MAX_CHARS)
    }

    @Test
    fun `v1_0_5 long real answers are no longer mangled to 400 chars`() {
        // The v1.0.4 field failure: a capability-audit answer reached the
        // dispatcher chopped mid-word ("…which ca"). Real answers up to a few
        // thousand chars must survive classification intact.
        val audit = buildString {
            append("I checked every capability in the list. ")
            repeat(60) { i -> append("Capability number $i works in this environment. ") }
            append("End of report, which ca") // > 400 chars total
        }
        val (_, params) = PlanResponseSanitizer.classifyProseReply(audit)!!
        val response = params["response"]!!
        assertTrue(response.length > 400)
        assertTrue(response.endsWith("which ca"))
        assertTrue(response.contains("Capability number 59"))
    }

    @Test
    fun `whitespace collapse keeps the answer readable without inflating it`() {
        val text = "Answer   with    many    spaces\n\nand newlines"
        val (_, params) = PlanResponseSanitizer.classifyProseReply(text)!!
        assertEquals("Answer with many spaces and newlines", params["response"])
    }

    // --- proseDeclinesAction (v1.0.5 deferral gate) ---

    @Test
    fun `short commitment against an artifact goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "I am creating the HTML file for you.",
                "Create a file at Documents/e2e_site.html with a heading Hello E2E"
            )
        )
    }

    @Test
    fun `lets build it against a website goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Let's build it!",
                "build an HTML website for me"
            )
        )
    }

    @Test
    fun `long substantive answers are never treated as deferrals`() {
        val audit = "Here is the capability report you asked for. " + "Detail ".repeat(120)
        assertTrue(audit.length > 600)
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(audit, "make a report of your capabilities"))
    }

    @Test
    fun `short conversational answer to a non-artifact goal is not a deferral`() {
        assertTrue(
            !PlanResponseSanitizer.proseDeclinesAction(
                "OpenAI released a new model today.",
                "what is new in AI?"
            )
        )
    }

    @Test
    fun `blank response is never a deferral`() {
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction("", "create a file"))
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(null, "create a file"))
    }

    // --- v1.0.6 data-goal deferral gate (the gold-price field failure) ---

    @Test
    fun `let me check against a price goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Let me check the current gold price for you.",
                "csn u fetch the price of gold"
            )
        )
    }

    @Test
    fun `let me fetch against a search goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Sure! Let me search for the latest iPhone price.",
                "search for latest iphone price"
            )
        )
    }

    @Test
    fun `substantive data answer to a data goal is not a deferral`() {
        assertTrue(
            !PlanResponseSanitizer.proseDeclinesAction(
                "Gold is trading around $4,284 per ounce today, up 0.4%.",
                "csn u fetch the price of gold"
            )
        )
    }

    @Test
    fun `goalWantsWebData matches price fetch search phrasing`() {
        assertTrue(PlanResponseSanitizer.goalWantsWebData("fetch the price of gold"))
        assertTrue(PlanResponseSanitizer.goalWantsWebData("latest iphone price"))
        assertTrue(!PlanResponseSanitizer.goalWantsWebData("tell me a joke"))
    }

    @Test
    fun `goalWantsArtifact matches file html pdf phrasing`() {
        assertTrue(PlanResponseSanitizer.goalWantsArtifact("create an award winning website in html"))
        assertTrue(PlanResponseSanitizer.goalWantsArtifact("make a pdf report of gold price"))
        assertTrue(!PlanResponseSanitizer.goalWantsArtifact("what is the capital of France"))
    }

    @Test
    fun `let me put together against a website goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Love it! Let me put together something slick for you.",
                "can u create a award winning website in html"
            )
        )
    }
}
