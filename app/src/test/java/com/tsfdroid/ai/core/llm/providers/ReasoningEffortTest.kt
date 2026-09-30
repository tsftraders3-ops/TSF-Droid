package com.tsfdroid.ai.core.llm.providers

import com.tsfdroid.ai.core.llm.LLMRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.3.0 (Phase 14 WAVE C): direct coverage of the reasoning-effort gate
 * every OpenAI-compatible provider now shares. The selector must be real,
 * not cosmetic — the wire field appears ONLY when the user picked a level the
 * model's registry spec actually lists, and stays absent in every other case.
 *
 * Runs under Robolectric: the gate mutates android's org.json, whose methods
 * are stubs on the unmocked android.jar used by plain JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
class ReasoningEffortTest {

    /** Registry-shaped fake: three named variant levels. */
    private fun fakeSpec(levels: List<String>) = ZenModelSpec(
        id = "reasoning-model",
        name = "Reasoning Model",
        contextWindow = 100_000,
        maxOutput = 10_000,
        reasoning = true,
        toolCall = true,
        reasoningLevels = levels,
        free = false,
        chatCompletions = true,
        deprecated = false,
        inputModalities = listOf("text")
    )

    private fun request(effort: String?) = LLMRequest(
        systemPrompt = "you are a test",
        messages = emptyList(),
        model = "reasoning-model",
        reasoningEffort = effort
    )

    @Test
    fun `a requested level the spec lists is sent lowercased`() {
        val spec = fakeSpec(listOf("high", "low", "medium"))

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("HIGH"), spec)
        assertEquals("high", jsonBody.optString(ReasoningEffort.WIRE_FIELD))
        assertEquals("reasoning_effort", ReasoningEffort.WIRE_FIELD)

        val mapBody = mutableMapOf<String, Any>()
        ReasoningEffort.applyToBody(mapBody, request("Medium"), spec)
        assertEquals("medium", mapBody[ReasoningEffort.WIRE_FIELD])
    }

    @Test
    fun `a requested level the spec does not list is absent`() {
        val spec = fakeSpec(listOf("high", "low"))

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("ultra"), spec)
        assertFalse(jsonBody.has(ReasoningEffort.WIRE_FIELD))

        val mapBody = mutableMapOf<String, Any>()
        ReasoningEffort.applyToBody(mapBody, request("ultra"), spec)
        assertNull(mapBody[ReasoningEffort.WIRE_FIELD])
    }

    @Test
    fun `a null effort is absent`() {
        val spec = fakeSpec(listOf("high", "low"))

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request(null), spec)
        assertFalse(jsonBody.has(ReasoningEffort.WIRE_FIELD))

        val mapBody = mutableMapOf<String, Any>()
        ReasoningEffort.applyToBody(mapBody, request(null), spec)
        assertNull(mapBody[ReasoningEffort.WIRE_FIELD])
    }

    @Test
    fun `a null spec (unknown model) is absent`() {
        // Same conservative rule as the Zen provider: an unknown model never
        // receives a field its backend may reject.
        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("high"), null)
        assertFalse(jsonBody.has(ReasoningEffort.WIRE_FIELD))

        val mapBody = mutableMapOf<String, Any>()
        ReasoningEffort.applyToBody(mapBody, request("high"), null)
        assertNull(mapBody[ReasoningEffort.WIRE_FIELD])
    }

    @Test
    fun `a blank effort is absent`() {
        val spec = fakeSpec(listOf("high", "low"))

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("   "), spec)
        assertFalse(jsonBody.has(ReasoningEffort.WIRE_FIELD))

        val mapBody = mutableMapOf<String, Any>()
        ReasoningEffort.applyToBody(mapBody, request("   "), spec)
        assertNull(mapBody[ReasoningEffort.WIRE_FIELD])
    }

    @Test
    fun `spec levels match case-insensitively and the wire value keeps the contract casing`() {
        val spec = fakeSpec(listOf("High", "LOW"))

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("high"), spec)
        assertEquals("high", jsonBody.optString(ReasoningEffort.WIRE_FIELD))

        ReasoningEffort.applyToBody(jsonBody, request("low"), spec)
        assertEquals("low", jsonBody.optString(ReasoningEffort.WIRE_FIELD))
    }

    @Test
    fun `a spec with no reasoning levels never sends the field`() {
        val spec = fakeSpec(emptyList())

        val jsonBody = JSONObject()
        ReasoningEffort.applyToBody(jsonBody, request("high"), spec)
        assertFalse(jsonBody.has(ReasoningEffort.WIRE_FIELD))
    }
}
