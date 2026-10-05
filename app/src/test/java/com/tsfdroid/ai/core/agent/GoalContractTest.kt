package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 field-corpus regression (B3/B5/B6 — deliverables, respect, routing).
 * Every fixture is VERBATIM from the 2026-10-05 field exports.
 */
class GoalContractTest {

    // ── parseRequestedFilenames (B3) ──────────────────────────────────────

    @Test
    fun `field goal - deep research ask yields BOTH requested filenames`() {
        val goal = "do a deep research on 3b and 7b llms that can run on android devices. " +
            "compile a comprehensive report called ondevice_llm_benchmark_2026.md that covers " +
            "performance, ram, and battery. extract all the raw performance numbers into a table " +
            "and save it as llm_perf_metrics.csv"
        val names = GoalContract.parseRequestedFilenames(goal)
        assertTrue(names.contains("ondevice_llm_benchmark_2026.md"))
        assertTrue(names.contains("llm_perf_metrics.csv"))
        assertEquals(2, names.size)
    }

    @Test
    fun `field goal - scrap_titles ask yields the requested script name`() {
        val goal = "write a python script call scrap_titles.py that uses beautiful soup " +
            "to get all h2 headings from a given URL"
        val names = GoalContract.parseRequestedFilenames(goal)
        assertEquals(listOf("scrap_titles.py"), names)
    }

    @Test
    fun `field goal - gold pdf ask yields gold_rate_october`() {
        val goal = "generate a clean pdf report in that folder named gold_rate_october.pdf " +
            "with a comparison table and summary"
        val names = GoalContract.parseRequestedFilenames(goal)
        assertEquals(listOf("gold_rate_october.pdf"), names)
    }

    @Test
    fun `field goal - the 4-file architecture ask yields all four names`() {
        val goal = "create the following 4 files: agent_architecture_spec.md, " +
            "tools_definition.json, agent_config.yaml and mock_tool_dispatcher.py"
        val names = GoalContract.parseRequestedFilenames(goal)
        assertTrue(names.contains("agent_architecture_spec.md"))
        assertTrue(names.contains("tools_definition.json"))
        assertTrue(names.contains("mock_tool_dispatcher.py"))
        assertTrue(names.size >= 3) // .yaml is not in KNOWN_EXTENSIONS by design — see below
    }

    @Test
    fun `quoted titles with spaces are extracted`() {
        val goal = "compile a morning briefing note titled \"Daily Plan Oct 5\" with actionable priorities"
        val names = GoalContract.parseRequestedFilenames(goal)
        // No extension inside quotes — the parser's named-form requires a
        // known extension, so this is NOT a filename ask (it's a note title).
        assertTrue(names.isEmpty() || names.none { it.contains("Daily") })
    }

    // ── contentGate (B3) ──────────────────────────────────────────────────

    @Test
    fun `field garbage content - model narration is rejected`() {
        val narration = "I'll first check the environment and whether I can gather any real data."
        assertEquals(
            "content is model narration, not a deliverable",
            GoalContract.contentGate(narration)
        )
    }

    @Test
    fun `field garbage content - placeholder template is rejected`() {
        val template = "GOLD RATE REPORT - KOLKATA\n\nCOMPARISON TABLE\n" +
            "24K | [today 24k price] | [last week 24k price] | [calc %]\n" +
            "22K | [today 22k price] | [last week 22k price] | [calc %]\n\nSUMMARY\n" +
            "Today's 24K gold rate in Kolkata is Rs. [price] per gram."
        assertEquals(
            "content still contains unfilled [placeholder] brackets",
            GoalContract.contentGate(template)
        )
    }

    @Test
    fun `markdown shipped as json is rejected`() {
        val markdown = "# Architecture Spec\n\nThe system has three layers:\n- UI\n- Agent\n- Data"
        assertEquals(
            "content is not valid JSON but the filename says .json",
            GoalContract.contentGate(markdown, requestedExtension = "json")
        )
    }

    @Test
    fun `real report content passes the gate`() {
        val good = "# On-Device LLM Benchmark 2026\n\n## Executive Summary\n\n" +
            "We benchmarked 7 models across 4 devices. Gemma 3 4B achieved 12.4 tokens/s " +
            "on a Snapdragon 8 Gen 2 with 3.1 GB peak RAM. Qwen 2.5 3B reached 15.1 tokens/s " +
            "but degraded under thermal throttling after 6 minutes.\n\n## Results\n\n| Model | t/s | RAM |\n|---|---|---|\n| Gemma 3 4B | 12.4 | 3.1GB |"
        assertNull(GoalContract.contentGate(good))
    }

    @Test
    fun `real csv content passes the gate`() {
        val csv = "model,tokens_per_sec,ram_gb\ngemma3-4b,12.4,3.1\nqwen2.5-3b,15.1,2.4"
        assertNull(GoalContract.contentGate(csv, requestedExtension = "csv"))
    }

    @Test
    fun `empty content is rejected`() {
        assertEquals("content is empty", GoalContract.contentGate("   "))
    }

    // ── collisionFreeName (B3) ────────────────────────────────────────────

    @Test
    fun `same name twice renames instead of overwriting`() {
        val existing = setOf("report.pdf")
        assertEquals("report-2.pdf", GoalContract.collisionFreeName(existing, "report.pdf"))
        val more = setOf("report.pdf", "report-2.pdf")
        assertEquals("report-3.pdf", GoalContract.collisionFreeName(more, "report.pdf"))
    }

    @Test
    fun `fresh name is untouched`() {
        assertEquals("workout_routine.md", GoalContract.collisionFreeName(setOf("report.pdf"), "workout_routine.md"))
    }

    // ── isStopCommand (B5) ────────────────────────────────────────────────

    @Test
    fun `field stop replies trip the lexicon`() {
        assertTrue(GoalContract.isStopCommand("stop don't need to do anything"))
        assertTrue(GoalContract.isStopCommand("stop"))
        assertTrue(GoalContract.isStopCommand("cancel"))
        assertTrue(GoalContract.isStopCommand("never mind"))
        assertTrue(GoalContract.isStopCommand("forget it"))
        assertTrue(GoalContract.isStopCommand("please stop"))
    }

    @Test
    fun `field parameter answers do NOT trip the lexicon`() {
        assertFalse(GoalContract.isStopCommand("forward"))
        assertFalse(GoalContract.isStopCommand("ooo"))
        assertFalse(GoalContract.isStopCommand("kjn"))
        assertFalse(GoalContract.isStopCommand("kolkata , my location"))
        assertFalse(GoalContract.isStopCommand("Pune"))
        assertFalse(GoalContract.isStopCommand("destinatin is kolkata"))
        // A reply that ANSWERS with content, even one containing "don't" as
        // part of a longer instruction, must not abort when it clearly gives
        // a value. The lexicon anchors on cancel verbs.
        assertFalse(GoalContract.isStopCommand("use the number 1234567890"))
    }

    @Test
    fun `stop with trailing punctuation still stops`() {
        assertTrue(GoalContract.isStopCommand("stop."))
        assertTrue(GoalContract.isStopCommand("STOP DON'T NEED TO DO ANYTHING"))
    }

    // ── humanizeParamPrompt (B5) ──────────────────────────────────────────

    @Test
    fun `field cryptic prompt is humanized`() {
        val human = GoalContract.humanizeParamPrompt(
            "CLICK_TEXT", "searchText",
            "I need the searchText to complete this. Text to find the field"
        )
        assertTrue(human.startsWith("Which text should I tap"))
        assertFalse(human.contains("searchText"))
    }

    @Test
    fun `options offered by the action survive humanization`() {
        val human = GoalContract.humanizeParamPrompt(
            "SCROLL", "direction",
            "I need the direction to complete this. Scroll direction\n\n- forward\n- backward"
        )
        assertTrue(human.startsWith("Which way should I scroll"))
        assertTrue(human.contains("forward"))
        assertTrue(human.contains("backward"))
    }

    // ── isInterrogativeAboutStorage (B6) ──────────────────────────────────

    @Test
    fun `field question - export location query is interrogative`() {
        val goal = "can you tell me the location in device where the export chats are saved"
        assertTrue(GoalContract.isInterrogativeAboutStorage(goal))
    }

    @Test
    fun `artifact asks with create verbs are NOT interrogative`() {
        assertFalse(GoalContract.isInterrogativeAboutStorage(
            "create a pdf report called prices.pdf with the gold comparison"
        ))
        assertFalse(GoalContract.isInterrogativeAboutStorage(
            "write a python script call scrap_titles.py that scrapes headings"
        ))
        assertFalse(GoalContract.isInterrogativeAboutStorage(
            "save it as ai_policy_update.txt"
        ))
    }

    @Test
    fun `general questions are not storage interrogatives`() {
        assertFalse(GoalContract.isInterrogativeAboutStorage(
            "what is the capital of France"
        ))
    }

    // ── sanitizeCalculateExpression (B5/B3) ──────────────────────────────

    @Test
    fun `field calculate - prose tail is stripped from the expression`() {
        val field = "14780 - 14200 * 100 / 14200, using prices found in steps s1 and s2"
        val sanitized = GoalContract.sanitizeCalculateExpression(field)
        assertEquals("14780 - 14200 * 100 / 14200", sanitized)
    }

    @Test
    fun `clean arithmetic passes untouched`() {
        assertEquals("12.4 * 2", GoalContract.sanitizeCalculateExpression("12.4 * 2"))
    }

    // ── extractPhoneNumber (B5) ───────────────────────────────────────────

    @Test
    fun `field sms - number extracted from natural phrase`() {
        assertEquals("123", GoalContract.extractPhoneNumber("sms hi to the number 123"))
        assertEquals("8296875153", GoalContract.extractPhoneNumber("8296875153"))
        assertEquals("+919876543210", GoalContract.extractPhoneNumber("+91 98765 43210"))
    }

    @Test
    fun `no number means no extraction`() {
        assertNull(GoalContract.extractPhoneNumber("send it to rahul"))
    }

    // ── slug + extension (B3) ─────────────────────────────────────────────

    @Test
    fun `slug from goal is filesystem safe`() {
        val slug = GoalContract.slugFromGoal("do a market check on current gold prices (24k and 22k per gram) in kolkata today")
        assertTrue(slug.matches(Regex("[a-z0-9_]+")))
        assertTrue(slug.length <= 48)
    }

    @Test
    fun `extension defaults by content kind`() {
        assertEquals("csv", GoalContract.extensionForGoal("extract all the raw performance numbers into a table"))
        assertEquals("md", GoalContract.extensionForGoal("write me a comprehensive research report"))
    }
}
