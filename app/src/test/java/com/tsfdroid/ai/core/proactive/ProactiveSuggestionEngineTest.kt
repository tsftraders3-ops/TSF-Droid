package com.tsfdroid.ai.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * God-Tier Phase 2, ticket 5.4 — the local pattern analysis engine.
 *
 * The engine is DETERMINISTIC and purely local: no LLM, no network, no
 * dispatching — it looks at counters of local activity and drafts template
 * suggestions. These tests pin that it produces something useful from real
 * patterns, nothing from nothing, and that its suggestions are side-effect
 * free by construction.
 */
class ProactiveSuggestionEngineTest {

    private val engine: ProactiveSuggestionEngine = LocalPatternSuggestionEngine()

    @Test
    fun `no activity yields no suggestions`() {
        val suggestions = engine.generate(
            sessionCount = 0,
            recentSessionTitles = emptyList(),
            validMemoryCount = 0
        )
        assertTrue("nothing to analyze, nothing to suggest", suggestions.isEmpty())
    }

    @Test
    fun `recurring chat activity suggests a macro`() {
        val suggestions = engine.generate(
            sessionCount = 4,
            recentSessionTitles = listOf(
                "Gold price check", "Gold price check", "Weather in Mumbai", "Summarize news"
            ),
            validMemoryCount = 2
        )
        val macro = suggestions.firstOrNull { it.suggestedAction == "CREATE_MACRO" }
        assertTrue("4 recent chats should suggest creating a macro", macro != null)
        assertTrue(
            "the suggestion must reference the actual activity",
            macro!!.body.contains("4")
        )
    }

    @Test
    fun `learned memories suggest a review`() {
        val suggestions = engine.generate(
            sessionCount = 1,
            recentSessionTitles = listOf("one chat"),
            validMemoryCount = 6
        )
        assertTrue(
            "6 or more learned facts should suggest reviewing memory",
            suggestions.any { it.suggestedAction == null && it.body.contains("6") }
        )
    }

    @Test
    fun `engine output is side-effect free by construction`() {
        val all = engine.generate(
            sessionCount = 10,
            recentSessionTitles = List(10) { "chat $it" },
            validMemoryCount = 12
        )
        assertTrue("activity produces suggestions", all.isNotEmpty())
        assertTrue(
            "every engine suggestion must be side-effect free",
            all.all { it.isSideEffectFree() }
        )
    }

    @Test
    fun `a critical-action suggestion is not side-effect free`() {
        val critical = ProactiveSuggestion(
            title = "Delete it all",
            body = "nope",
            suggestedAction = "DELETE_FILE"
        )
        assertFalse(critical.isSideEffectFree())
    }
}
