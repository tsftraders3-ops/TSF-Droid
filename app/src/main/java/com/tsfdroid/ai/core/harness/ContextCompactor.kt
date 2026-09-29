package com.tsfdroid.ai.core.harness

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.PromptBudget
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import java.util.UUID

/**
 * v1.2.0: OpenCode-style automatic context compaction.
 *
 * OpenCode watches the conversation's token usage and, when it crosses ~75%
 * of the model's context window, compacts the whole history into a summary
 * and keeps going — so a user can chat or work on a huge project for hours
 * and the model never "forgets" or hits a hard wall mid-task.
 *
 * This class implements the same rule for TSF Droid:
 *
 *  1. Before every chat turn the harness measures the assembled prompt
 *     (system prompt + full history) with the same chars-per-token heuristic
 *     the output clamp already uses ([PromptBudget.estimateTokens]).
 *  2. When the estimate crosses [COMPACTION_THRESHOLD] (75%) of the model's
 *     registry-published context window, the history is compacted: the most
 *     recent [KEEP_RECENT] messages stay verbatim, everything older is
 *     replaced by ONE summarized context message produced by a cheap LLM call.
 *  3. The compaction is reported as a visible [ActivityStep] — the user sees
 *     "Compacted conversation history" instead of the agent mysteriously
 *     changing behavior.
 *
 * Compaction is deliberately conservative: it never runs when the history is
 * already short (nothing older than the kept window), it never touches the
 * just-added user message, and when the summary call fails the ORIGINAL
 * history is returned (degrade to today's behavior, never break the turn).
 */
class ContextCompactor(private val provider: LLMProvider) {

    data class Result(
        val messages: List<ChatMessage>,
        /** Non-null when compaction actually ran. */
        val summary: String?,
        /** Estimated tokens of the prompt BEFORE compaction. */
        val tokensBefore: Int,
        /** Estimated tokens of the prompt AFTER compaction. */
        val tokensAfter: Int
    ) {
        val compacted: Boolean get() = summary != null
    }

    /**
     * Decides whether compaction is needed and performs it when it is.
     *
     * @param systemPrompt the system prompt that will precede the history.
     * @param history the full conversation history (chronological, the user's
     *                new message already included as the last entry).
     * @param contextWindow the active model's registry context window; null
     *                (unknown model) disables compaction entirely — without a
     *                window size there is no meaningful 75%.
     */
    suspend fun compactIfNeeded(
        systemPrompt: String,
        history: List<ChatMessage>,
        contextWindow: Int?
    ): Result {
        val promptTokens = PromptBudget.estimateTokens(
            systemPrompt + "\n" + history.joinToString("\n") { it.text }
        )
        val window = contextWindow?.takeIf { it > 0 }
            ?: return Result(history, null, promptTokens, promptTokens)
        val threshold = (window * COMPACTION_THRESHOLD).toInt()
        if (promptTokens < threshold) {
            return Result(history, null, promptTokens, promptTokens)
        }
        // Nothing meaningful to compact: the recent window covers (almost)
        // everything. Compacting here would eat the whole conversation.
        if (history.size <= KEEP_RECENT + 2) {
            return Result(history, null, promptTokens, promptTokens)
        }

        val summary = runCatching { summarize(systemPrompt, history) }.getOrNull()
            ?: return Result(history, null, promptTokens, promptTokens)

        val kept = history.takeLast(KEEP_RECENT)
        val summaryMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "[conversation so far — compacted to keep the context window healthy]\n" +
                "Summary of everything before the messages below:\n$summary",
            sender = ChatMessage.Sender.USER
        )
        val compacted = listOf(summaryMessage) + kept
        val afterTokens = PromptBudget.estimateTokens(
            systemPrompt + "\n" + compacted.joinToString("\n") { it.text }
        )
        return Result(compacted, summary, promptTokens, afterTokens)
    }

    /**
     * One cheap, deterministic summary call. Deliberately small budget and
     * temperature 0 — this is a compression step, not a creative one. The
     * transcript is truncated to [MAX_TRANSCRIPT_CHARS] so the summarizer
     * itself can never overflow the window it is trying to rescue.
     */
    private suspend fun summarize(systemPrompt: String, history: List<ChatMessage>): String {
        val transcript = history.dropLast(1) // the fresh user message is kept verbatim anyway
            .joinToString("\n") { "${it.sender.name.lowercase()}: ${it.text}" }
            .take(MAX_TRANSCRIPT_CHARS)
        val response = provider.complete(
            LLMRequest(
                systemPrompt = SUMMARY_SYSTEM_PROMPT,
                messages = listOf(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = transcript,
                        sender = ChatMessage.Sender.USER
                    )
                ),
                temperature = 0.2f,
                maxTokens = SUMMARY_MAX_TOKENS,
                responseFormat = ResponseFormat.TEXT
            )
        )
        return response.content.trim()
    }

    companion object {
        /** OpenCode's rule: compact when ~75% of the context window is used. */
        const val COMPACTION_THRESHOLD = 0.75

        /** Recent messages that always stay verbatim after compaction. */
        const val KEEP_RECENT = 8

        const val SUMMARY_MAX_TOKENS = 700

        const val MAX_TRANSCRIPT_CHARS = 24_000

        const val SUMMARY_SYSTEM_PROMPT =
            "You are a conversation compactor for an AI assistant. Summarize the transcript " +
                "into a dense context note the assistant will use to continue the conversation " +
                "as if it remembered everything. Keep: every fact the user stated about " +
                "themselves, decisions made, tasks requested and their outcomes, file names, " +
                "urls, numbers, and any open question. Drop small talk. Write 120-220 words " +
                "of plain prose. Output ONLY the summary."
    }
}
