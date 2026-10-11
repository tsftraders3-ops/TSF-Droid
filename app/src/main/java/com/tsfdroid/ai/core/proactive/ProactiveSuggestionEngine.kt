package com.tsfdroid.ai.core.proactive

/**
 * God-Tier Phase 2, ticket 5.4 — the seam between the worker and whatever
 * drafts its suggestions.
 *
 * The input is a SNAPSHOT of local activity counters (no I/O of its own —
 * the worker gathers the data); the output is a list of suggestions. Pure
 * and deterministic, so tests substitute fakes freely and the production
 * engine stays verifiable in isolation.
 */
interface ProactiveSuggestionEngine {
    fun generate(
        sessionCount: Int,
        recentSessionTitles: List<String>,
        validMemoryCount: Int
    ): List<ProactiveSuggestion>
}

/**
 * The production engine: deterministic local heuristics, no LLM, no
 * network, no dispatching — pattern analysis over activity counters that
 * drafts template advice. Deliberately boring and honest: it can only
 * suggest what it can actually see (chat activity, learned memories).
 *
 *  - >= 3 recent chat sessions -> suggest turning the recurring ones into a
 *    macro (the single highest-leverage automation the app offers);
 *  - >= 5 valid learned memories -> suggest reviewing what the agent
 *    learned (memory hygiene is the user's only control surface);
 *  - otherwise -> nothing (an agent that always has a suggestion is noise).
 */
class LocalPatternSuggestionEngine : ProactiveSuggestionEngine {

    override fun generate(
        sessionCount: Int,
        recentSessionTitles: List<String>,
        validMemoryCount: Int
    ): List<ProactiveSuggestion> {
        val suggestions = mutableListOf<ProactiveSuggestion>()
        if (sessionCount >= CHAT_ACTIVITY_THRESHOLD) {
            suggestions += ProactiveSuggestion(
                title = "Turn your recurring chats into a macro",
                body = "You have $sessionCount recent chats (${recentSessionTitles.take(3).joinToString()}...). " +
                    "A macro could run the recurring ones in one step.",
                suggestedAction = "CREATE_MACRO"
            )
        }
        if (validMemoryCount >= MEMORY_REVIEW_THRESHOLD) {
            suggestions += ProactiveSuggestion(
                title = "Review what the agent remembers",
                body = "The agent has learned $validMemoryCount facts from your conversations. " +
                    "Skim them in the Memory tab and forget anything stale.",
                suggestedAction = null
            )
        }
        return suggestions
    }

    private companion object {
        const val CHAT_ACTIVITY_THRESHOLD = 3
        const val MEMORY_REVIEW_THRESHOLD = 5
    }
}
