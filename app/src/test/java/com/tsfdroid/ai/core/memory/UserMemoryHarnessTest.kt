package com.tsfdroid.ai.core.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.1: the memory-learning extractor's output-shape contract. Mirrors
 * [UserMemoryLearner.parseItems] (which needs Hilt dependencies to
 * instantiate); key sanitizing, value truncation, the 3-item cap, and
 * garbage-tolerance are the contract the learner must honor.
 */
class MemoryLearnerParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(content: String): List<Pair<String, String>> {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return runCatching {
            val arr = json.parseToJsonElement(cleaned).jsonArray
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val key = obj["key"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                key.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(48) to value.take(200)
            }.take(3)
        }.getOrElse { emptyList() }
    }

    @Test
    fun `parses a plain json array`() {
        val items = parse("""[{"key":"cat_name","value":"Luna is the user's cat"}]""".replace("\\", "\\"))
        assertEquals(1, items.size)
        assertEquals("cat_name", items[0].first)
        assertEquals("Luna is the user's cat", items[0].second)
    }

    @Test
    fun `parses fenced json`() {
        val items = parse("```json\n[{\"key\":\"job\",\"value\":\"Works as a nurse in Pune\"}]\n```")
        assertEquals(1, items.size)
        assertEquals("job", items[0].first)
    }

    @Test
    fun `sanitizes hostile keys and truncates values`() {
        val items = parse("""[{"key":"My Key With Spaces!","value":"${"v".repeat(500)}"}]""")
        assertEquals("my_key_with_spaces", items[0].first)
        assertEquals(200, items[0].second.length)
    }

    @Test
    fun `caps at three items per exchange`() {
        val four = (1..4).joinToString(",") { i -> """{"key":"k$i","value":"v$i"}""" }
        assertEquals(3, parse("[$four]").size)
    }

    @Test
    fun `garbage returns empty - never throws`() {
        assertTrue(parse("I will not learn anything today").isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("[{broken").isEmpty())
    }

    @Test
    fun `blank or missing fields are dropped`() {
        val items = parse("""[{"key":"","value":"v"},{"key":"ok","value":""},{"key":"good","value":"kept"}]""")
        assertEquals(1, items.size)
        assertEquals("good", items[0].first)
    }
}
