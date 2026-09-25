package com.tsfdroid.ai.core.llm.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The models.dev registry is the source of automatically-maintained model
 * capabilities (context window, reasoning, tool-call, free/paid). The parser
 * must be defensive: a registry shape change degrades to fewer specs, never a
 * crash, and must never mark paid models as free.
 */
class ModelsDevRegistryParseTest {

    /** Shape mirrors the live https://models.dev/api.json opencode entry. */
    private val registryBody = """
    {
      "opencode": {
        "id": "opencode",
        "env": ["OPENCODE_API_KEY"],
        "npm": "@ai-sdk/openai-compatible",
        "api": "https://opencode.ai/zen/v1",
        "name": "OpenCode Zen",
        "models": {
          "ling-3.0-flash-fin-free": {
            "id": "ling-3.0-flash-fin-free",
            "name": "Ling 3.0 Flash Fin Free",
            "reasoning": true,
            "reasoning_options": [{"type": "toggle"}],
            "tool_call": true,
            "temperature": true,
            "attachment": false,
            "limit": {"context": 262144, "output": 32768},
            "modalities": {"input": ["text"], "output": ["text"]},
            "cost": {"input": 0, "output": 0, "cache_read": 0}
          },
          "gpt-5.4": {
            "id": "gpt-5.4",
            "name": "GPT 5.4",
            "reasoning": true,
            "tool_call": true,
            "limit": {"context": 1050000, "output": 128000},
            "cost": {"input": 2.5, "output": 10}
          },
          "muse-spark-1.3-contributor-free": {
            "id": "muse-spark-1.3-contributor-free",
            "name": "Muse Spark 1.3 Contributor Free",
            "reasoning": true,
            "tool_call": true,
            "provider": {"npm": "@ai-sdk/openai"},
            "limit": {"context": 1048576, "output": 65536},
            "cost": {"input": 0, "output": 0}
          },
          "broken-entry": {
            "id": "broken-entry",
            "name": "No Context Limit"
          }
        }
      },
      "another-provider": {"id": "x", "models": {}}
    }
    """.trimIndent()

    @Test
    fun `context windows and reasoning come from the registry, not a hardcoded table`() {
        val specs = ModelsDevRegistry.parse(registryBody)

        assertEquals(3, specs.size)
        val ling = specs.getValue("ling-3.0-flash-fin-free")
        assertEquals(262144, ling.contextWindow)
        assertEquals(32768, ling.maxOutput)
        assertEquals("Ling 3.0 Flash Fin Free", ling.name)
        assertTrue(ling.reasoning)
        assertTrue(ling.toolCall)
        assertTrue(ling.chatCompletions)
    }

    @Test
    fun `free tier is derived from zero input and output cost`() {
        val specs = ModelsDevRegistry.parse(registryBody)

        assertTrue(specs.getValue("ling-3.0-flash-fin-free").free)
        assertFalse("a paid model must never look free", specs.getValue("gpt-5.4").free)
    }

    @Test
    fun `responses-only models are excluded from the chat-completions picker`() {
        val specs = ModelsDevRegistry.parse(registryBody)

        // muse-spark-1.3 overrides the provider-level SDK with @ai-sdk/openai
        assertFalse(specs.getValue("muse-spark-1.3-contributor-free").chatCompletions)
        // the provider-level default (@ai-sdk/openai-compatible) applies otherwise
        assertTrue(specs.getValue("ling-3.0-flash-fin-free").chatCompletions)
    }

    @Test
    fun `entries without a usable context limit are dropped`() {
        val specs = ModelsDevRegistry.parse(registryBody)
        assertNull(specs["broken-entry"])
    }

    @Test
    fun `a shape change degrades to an empty registry instead of throwing`() {
        assertTrue(ModelsDevRegistry.parse("""{"unexpected": [1,2,3]}""").isEmpty())
        assertTrue(ModelsDevRegistry.parse("not json at all").isEmpty())
        assertTrue(ModelsDevRegistry.parse("").isEmpty())
    }
}
