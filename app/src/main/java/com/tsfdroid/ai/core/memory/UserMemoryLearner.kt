package com.tsfdroid.ai.core.memory

import android.util.Log
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.Memory
import com.tsfdroid.ai.data.models.MemoryType
import com.tsfdroid.ai.data.repository.MemoryRepository
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.2.0: the Hermes-style personal memory loop — the app LEARNS the user.
 *
 * After every chat exchange a tiny, cheap, read-mostly LLM call inspects the
 * turn and extracts DURABLE facts and preferences about the user ("prefers
 * dark mode", "cat is called Luna", "works as a nurse in Pune", "dislikes
 * emoji in messages"). Extracted items are stored in the semantic memory
 * store under stable keys (`learn_<slug>`), so:
 *
 *  - they are injected into every future conversation by
 *    [MemoryManager.getRelevantContext] (the read side),
 *  - a NEW value for the same key UPDATES the memory instead of duplicating
 *    it (the user changed their mind → the model follows),
 *  - the Memory tab lists them (browsable, deletable — the user owns them).
 *
 * Design guarantees:
 *  - NEVER blocks or fails the chat turn: every failure is swallowed and
 *    logged; learning is a background side effect.
 *  - Cheap: one call per completed exchange, maxTokens 350, temperature 0.
 *  - Honest: the extractor may return an empty list and usually should —
 *    small talk learns nothing.
 *  - Existing keys are passed INTO the extractor so it emits updates
 *    (`value` changed) rather than near-duplicate keys.
 */
@Singleton
class UserMemoryLearner @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Sentinel returned when the extractor sees nothing durable to learn. */
    internal data class LearnedItem(val key: String, val value: String)

    /**
     * Learns from one completed exchange. Fire-and-forget safe: call it in a
     * background scope after the assistant reply is persisted.
     */
    suspend fun learnFromExchange(userText: String, assistantText: String) {
        try {
            val enabled = settingsRepository.llmConfig.first().memoryLearningEnabled
            if (!enabled) return
            if (userText.length < MIN_USER_TEXT_CHARS) return

            val existing = memoryRepository.getMemoriesByKeyPrefix(KEY_PREFIX)
            val existingKeys = existing.joinToString("\n") { "- ${it.key.removePrefix(KEY_PREFIX)}: ${it.value}" }
                .ifBlank { "(none yet)" }

            val provider = llmProviderFactory.getActiveProvider()
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = buildExtractionPrompt(existingKeys),
                    messages = listOf(
                        ChatMessage(
                            id = "learn-user",
                            text = "USER MESSAGE:\n${userText.take(MAX_TURN_CHARS)}\n\nASSISTANT REPLY:\n${assistantText.take(MAX_TURN_CHARS)}",
                            sender = ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.0f,
                    maxTokens = EXTRACTION_MAX_TOKENS,
                    responseFormat = ResponseFormat.JSON
                )
            )
            val items = parseItems(response.content)
            for (item in items) {
                val previous = existing.firstOrNull {
                    it.key == KEY_PREFIX + item.key
                }
                if (previous != null && previous.value.equals(item.value, ignoreCase = true)) continue
                memoryRepository.saveMemory(
                    Memory(
                        key = KEY_PREFIX + item.key,
                        value = item.value,
                        type = MemoryType.SEMANTIC,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Log.i(TAG, "Memory learned: ${item.key} = ${item.value.take(60)}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Learning must never surface as a user-facing failure.
            Log.w(TAG, "Memory learning skipped: ${e.message}")
        }
    }

    private fun buildExtractionPrompt(existingKeys: String): String = """
        You are the personal-memory extractor inside an AI assistant (like ChatGPT's
        memory or Hermes). From the exchange below, extract ONLY durable facts and
        preferences about the USER that future conversations should remember.

        Learn things like: name, job, city, people/pets close to them, ongoing projects,
        preferences (style, tone, tools, formats), constraints, likes/dislikes.
        Do NOT learn: temporary states ("I'm hungry today"), the assistant's behavior,
        anything about the world at large, anything already known (list below) unless
        the user CHANGED it — in that case re-emit the same key with the new value.

        Already known memories:
        $existingKeys

        Reply with ONLY a JSON array (no markdown, no prose). Empty array when there is
        nothing durable to learn — that is the normal case for small talk. Max 3 items.
        Shape: [{"key":"short_snake_case_key","value":"one concise sentence"}]
    """.trimIndent()

    /** Visible for testing: the extractor's output-shape contract. */
    internal fun parseItems(content: String): List<LearnedItem> {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val arr = json.parseToJsonElement(cleaned).jsonArray
            arr.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val key = obj["key"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                LearnedItem(
                    key = key.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(48),
                    value = value.take(200)
                )
            }.take(MAX_ITEMS_PER_EXCHANGE)
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val TAG = "UserMemoryLearner"
        const val KEY_PREFIX = "learn_"
        private const val MIN_USER_TEXT_CHARS = 8
        private const val MAX_TURN_CHARS = 4000
        private const val EXTRACTION_MAX_TOKENS = 350
        private const val MAX_ITEMS_PER_EXCHANGE = 3
    }
}
