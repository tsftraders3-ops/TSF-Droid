package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.harness.ActivityStep
import com.tsfdroid.ai.core.llm.providers.ZenModelSpec
import com.tsfdroid.ai.core.memory.UserMemoryLearner
import com.tsfdroid.ai.data.models.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 Phase 14 unit coverage for the pure pieces of the wave:
 *
 *  - [AssistantIdentity][UserMemoryLearner.detectAssistantIdentity] — the
 *    Farhan fix: "naming you X" is the ASSISTANT'S name, "my name is X" is
 *    the user's, and "call you back" is not a name at all.
 *  - [HarnessLoop.parseAskUserArguments] — the ask_user tool's tolerant
 *    argument contract (plain strings, object arrays, headers).
 *  - [thinkingDurationStep] — the Claude-style "Thought for Xs" step.
 *  - [trimHistoryToTokenBudget] / [historyBudgetFor] — the token-aware chat
 *    history window that replaces the fixed 30-message cap.
 */
class Phase14CoreExperienceTest {

    // ── Farhan fix: deterministic assistant-identity detection ──────────

    @Test
    fun `naming you Farhan is the assistant's name`() {
        val items = UserMemoryLearner.detectAssistantIdentity("I am naming you Farhan")
        assertEquals(1, items.size)
        assertEquals("assistant_name", items.first().key)
        assertEquals("The user calls the assistant 'Farhan'", items.first().value)
    }

    @Test
    fun `ill call you JARVIS is the assistant's name`() {
        val items = UserMemoryLearner.detectAssistantIdentity("Ok, I'll call you JARVIS from now on")
        assertEquals(1, items.size)
        assertTrue(items.first().value.contains("JARVIS"))
    }

    @Test
    fun `your name is Buddy is the assistant's name`() {
        val items = UserMemoryLearner.detectAssistantIdentity("Your name is Buddy, got it?")
        assertEquals(1, items.size)
        assertTrue(items.first().value.contains("Buddy"))
    }

    @Test
    fun `call yourself Alpha maps to assistant name`() {
        val items = UserMemoryLearner.detectAssistantIdentity("Please call yourself Alpha")
        assertEquals(1, items.size)
        assertTrue(items.first().value.contains("Alpha"))
    }

    @Test
    fun `call you in the morning is not a name`() {
        // Critic-proven misfire: lowercase prepositional continuation.
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("I will call you in the morning").size)
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("I'll call you when I land").size)
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("Your name is on the list").size)
    }

    @Test
    fun `user's own name is NOT the assistant's name`() {
        // "my name is Aisha" says nothing about the assistant — the detector
        // must stay silent; the LLM extractor learns it as the USER's name.
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("Hey, my name is Aisha").size)
    }

    @Test
    fun `call you back is not a name`() {
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("I'll call you back after lunch").size)
    }

    @Test
    fun `call you later is not a name`() {
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("Call you later!").size)
    }

    @Test
    fun `plain chat produces no assistant identity`() {
        assertEquals(0, UserMemoryLearner.detectAssistantIdentity("What's the weather in Pune today?").size)
    }

    @Test
    fun `first mention wins when the user names twice`() {
        val items = UserMemoryLearner.detectAssistantIdentity("I'm naming you Nova. Actually wait, naming you Zeta instead.")
        assertEquals(1, items.size)
        assertTrue(items.first().value.contains("Nova"))
    }

    @Test
    fun `quoted names are extracted from stored values`() {
        assertEquals("Farhan", UserMemoryLearner.extractQuotedName("The user calls the assistant 'Farhan'"))
        assertNull(UserMemoryLearner.extractQuotedName("no quotes here"))
    }

    // ── ask_user tool argument contract ──────────────────────────────────

    @Test
    fun `ask_user parses question and options`() {
        val (question, options) = HarnessLoop.parseAskUserArguments(
            """{"question":"Which city?","options":["Pune","Mumbai","Delhi"]}"""
        )
        assertEquals("Which city?", question)
        assertEquals(listOf("Pune", "Mumbai", "Delhi"), options)
    }

    @Test
    fun `ask_user tolerates opencode object options`() {
        val (question, options) = HarnessLoop.parseAskUserArguments(
            """{"question":"Which one?","options":[{"label":"A"},{"label":"B"}]}"""
        )
        assertEquals("Which one?", question)
        assertEquals(listOf("A", "B"), options)
    }

    @Test
    fun `ask_user header is folded into the question`() {
        val (question, _) = HarnessLoop.parseAskUserArguments(
            """{"header":"City","question":"Where do you live?"}"""
        )
        assertEquals("City: Where do you live?", question)
    }

    @Test
    fun `ask_user with no options yields empty list`() {
        val (question, options) = HarnessLoop.parseAskUserArguments(
            """{"question":"What is your favorite color?"}"""
        )
        assertEquals("What is your favorite color?", question)
        assertTrue(options.isEmpty())
    }

    @Test
    fun `ask_user caps options at five`() {
        val (_, options) = HarnessLoop.parseAskUserArguments(
            """{"question":"Pick","options":["1","2","3","4","5","6","7"]}"""
        )
        assertEquals(5, options.size)
    }

    @Test
    fun `ask_user garbage arguments degrade to empty`() {
        val (question, options) = HarnessLoop.parseAskUserArguments("not json at all")
        assertEquals("", question)
        assertTrue(options.isEmpty())
    }

    @Test
    fun `ask_user duplicate options are deduped`() {
        val (_, options) = HarnessLoop.parseAskUserArguments(
            """{"question":"Pick","options":["A","A","B"]}"""
        )
        assertEquals(listOf("A", "B"), options)
    }

    // ── Claude-style thinking duration step ─────────────────────────────

    @Test
    fun `thinking duration renders seconds`() {
        val step = thinkingDurationStep(12_300)!!
        assertEquals(ActivityStep.KIND_THINKING, step.kind)
        assertEquals("Thought for 12s", step.label)
    }

    @Test
    fun `thinking duration renders minutes and seconds`() {
        assertEquals("Thought for 1m 40s", thinkingDurationStep(100_000)!!.label)
    }

    @Test
    fun `thinking duration rounds to nearest second`() {
        assertEquals("Thought for 2s", thinkingDurationStep(1_700)!!.label)
    }

    @Test
    fun `no reasoning means no thinking step`() {
        assertNull(thinkingDurationStep(0))
        assertNull(thinkingDurationStep(-5))
    }

    // ── token-aware history window ───────────────────────────────────────

    private fun msg(text: String, sender: ChatMessage.Sender = ChatMessage.Sender.USER) =
        ChatMessage(id = text, text = text, sender = sender)

    @Test
    fun `history trim keeps the newest messages within budget`() {
        val history = (1..10).map { msg("message $it".padEnd(100, '.')) }
        val trimmed = trimHistoryToTokenBudget(history, 75)
        // Each message: ceil(100/4)=25 tokens + 12 overhead = 37; budget 75
        // keeps the newest 2 (74 used; a third would hit 111).
        assertEquals(2, trimmed.size)
        // The NEWEST survive, and order stays chronological.
        assertEquals(history.last().id, trimmed.last().id)
        assertTrue(trimmed.first().id.startsWith("message 9"))
    }

    @Test
    fun `history trim never returns empty for non-empty input`() {
        val history = listOf(msg("x".repeat(10_000)))
        val trimmed = trimHistoryToTokenBudget(history, 100)
        assertEquals(1, trimmed.size)
    }

    @Test
    fun `history trim counts images at token weight`() {
        val withImage = ChatMessage(
            id = "img", text = "short", sender = ChatMessage.Sender.USER,
            imageBase64 = "pretend-jpeg-bytes"
        )
        val plain = msg("y".repeat(100))
        val trimmed = trimHistoryToTokenBudget(listOf(plain, withImage), 100)
        // withImage costs 1 (text) + 12 + 1100 (image) ≈ 1113 tokens — far
        // over 100, so only the newest (withImage) survives the rule.
        assertEquals(listOf(withImage.id), trimmed.map { it.id })
    }

    @Test
    fun `budget scales with the model context window`() {
        val big = ZenModelSpec(
            id = "big", name = "Big", contextWindow = 200_000,
            maxOutput = 32_000, reasoning = false, toolCall = true,
            reasoningLevels = emptyList(),
            free = true, chatCompletions = true, deprecated = false,
            inputModalities = listOf("text")
        )
        val small = big.copy(id = "small", contextWindow = 8_000)
        val unknown: ZenModelSpec? = null
        assertTrue(historyBudgetFor(big, false) > historyBudgetFor(small, false))
        assertEquals(16_000, historyBudgetFor(unknown, false))
        // Small model: 8000 * 0.60 = 4800 → clamped to the 6k floor.
        assertEquals(6_000, historyBudgetFor(small, false))
        // Big model: 200000 * 0.60 = 120000 → clamped to the 120k ceiling.
        assertEquals(120_000, historyBudgetFor(big, false))
        // On-device models are hard-capped (4k-token windows must not overflow).
        assertEquals(2_500, historyBudgetFor(big, true))
        assertEquals(2_500, historyBudgetFor(unknown, true))
    }
}
