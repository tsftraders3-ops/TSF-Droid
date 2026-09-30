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
            val llmItems = parseItems(response.content)

            // v1.3.0: the deterministic assistant-identity pass. "I am naming
            // you Farhan" is about the ASSISTANT'S name, but the extractor's
            // job description says "facts about the user", so it kept filing
            // the name under the USER's facts (the exact field failure this
            // pass exists to make impossible). Regex-first, model-second: the
            // detector's items win their keys, and a same-value `name` item
            // from the model in the same turn is the misattribution — dropped.
            val detectorItems = detectAssistantIdentity(userText)
            val detectorNames = detectorItems.mapNotNull { extractQuotedName(it.value) }.toSet()
            val correctedItems = llmItems.filterNot { item ->
                (item.key == "name" || item.key == "user_name") &&
                    detectorNames.isNotEmpty() &&
                    detectorNames.any { item.value.contains(it, ignoreCase = true) }
            }
            // Detector items override the model's same-key extraction; every
            // other model item passes through untouched.
            val finalItems = correctedItems.filter { item ->
                detectorItems.none { it.key == item.key }
            } + detectorItems

            // v1.3.0 self-heal: if the device still carries a MISFILED
            // assistant name from before this fix (learn_name = "Farhan"
            // recorded when the user named the assistant), a fresh, explicit
            // naming statement is the moment to correct the record. The match
            // is EXACT (normalized), never a substring: "Sam" must not delete
            // the user's legitimate name "Samir" — a substring predicate here
            // would silently eat real user data (critic round 2).
            if (detectorItems.isNotEmpty()) {
                val detectedName = detectorItems.first().let { extractQuotedName(it.value) }
                if (detectedName != null) {
                    val normalizedDetected = detectedName.trim().lowercase()
                    val misfiled = existing.filter {
                        it.key == KEY_PREFIX + "name" &&
                            it.value.trim().trim('"', '\'', '.', '!', ',').lowercase() == normalizedDetected
                    }
                    for (stale in misfiled) {
                        memoryRepository.deleteMemory(stale.key)
                        Log.i(TAG, "Memory self-heal: removed misfiled assistant name from ${stale.key}")
                    }
                }
            }

            for (item in finalItems) {
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

        WHO IS WHO — the single most important rule:
        Facts about the USER ("my name is Aisha", "I work in Delhi") get normal keys
        (name, job, city).
        Facts about THE ASSISTANT ITSELF go under assistant_ keys. When the user
        NAMES the assistant or refers to it by a chosen name — "I am naming you
        Farhan", "I'll call you JARVIS", "your name is Buddy" — that is the
        ASSISTANT'S name, NOT the user's: emit
        {"key":"assistant_name","value":"The user calls the assistant 'Farhan'"}.
        NEVER store the assistant's name under the user's name key.

        CORRECTIONS — the user teaching the app what it got wrong:
        When the user corrects a previously learned fact ("no, I said I prefer X",
        "that's wrong, I live in Mumbai now", "actually don't do that"), re-emit the
        SAME key with the corrected value — the memory system updates in place.

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

        /** v1.3.0: the key that holds the assistant's own chosen name. */
        const val ASSISTANT_NAME_KEY = "assistant_name"

        /**
         * v1.3.0: words that follow "call you …" / "your name is …" but are
         * continuations, prepositions, or time words — not names.
         * "I'll call you back", "call you in the morning", "your name is on
         * the list" must all stay silent. The capitalized-first-letter rule
         * catches these too; this list is the second net (names written in
         * lowercase are deliberately sacrificed — a false "the user's name
         * is In The Morning" memory is far worse than a missed lowercase
         * nickname the LLM extractor still catches).
         */
        private val NOT_A_NAME = setOf(
            "back", "later", "soon", "again", "first", "now", "when", "if",
            "then", "okay", "ok", "alright", "maybe", "tomorrow", "today",
            "tonight", "home", "there", "here", "dad", "mom", "mum",
            "in", "at", "on", "for", "from", "with", "about", "after",
            "before", "until", "up", "over", "out", "the", "a", "an",
            // Critic round 2: capitalized TIME words fired as names —
            // "I'll call you Monday about the invoice" named the assistant
            // "Monday about the". Weekdays and clock words are never names.
            "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "noon", "midnight", "morning",
            "afternoon", "evening", "asap", "weekend", "weekdays"
        )

        /**
         * v1.3.0: deterministic assistant-naming detector — the Farhan fix.
         * Matches "(naming|name|call|I'll call|I will call) (you|yourself) X"
         * and "your name is X", where X is 1-3 plausible name words. Runs
         * BEFORE the model's extraction and overrides it on collision.
         */
        private val ASSISTANT_NAMING = Regex(
            "(?i)(?:\\b(?:naming|i(?:'?:?a?m)?\\s+name|name|call(?:ing)?|will\\s+call|'ll\\s+call)\\s+(?:you|yourself)\\s+|(?:your\\s+name(?:'?s|\\s+is)\\s+))" +
                "([\\p{L}][\\p{L}'\\u2019-]{0,24}(?:\\s+[\\p{L}][\\p{L}'\\u2019-]{0,24}){0,2})"
        )

        /** Visible for testing: the deterministic assistant-identity pass. */
        internal fun detectAssistantIdentity(userText: String): List<LearnedItem> {
            val found = ASSISTANT_NAMING.findAll(userText)
                .mapNotNull { m ->
                    val raw = m.groupValues[1].trim().trim('"', '\'', '\u201C', '\u201D', '.', '!', ',').trim()
                    if (raw.isEmpty()) return@mapNotNull null
                    val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (words.isEmpty() || words.size > 3) return@mapNotNull null
                    // The first word must read like a NAME: capitalized (names
                    // are proper nouns) and not a stopword. This is the net that
                    // kills "call you in the morning" / "your name is on the
                    // list" — prepositions and time words are never names.
                    val first = words.first()
                    val firstLower = first.lowercase()
                    if (firstLower in NOT_A_NAME) return@mapNotNull null
                    if (first.firstOrNull()?.isUpperCase() != true) return@mapNotNull null
                    if (words.size > 1 && words.last().lowercase() in setOf("too", "then", "ok")) return@mapNotNull null
                    LearnedItem(
                        key = ASSISTANT_NAME_KEY,
                        value = "The user calls the assistant '$raw'"
                    )
                }
                .toList()
            // A single assistant name per exchange — keep the first mention.
            return if (found.isEmpty()) emptyList() else listOf(found.first())
        }

        /** Visible for testing: pulls the quoted display name out of a saved value. */
        internal fun extractQuotedName(value: String): String? =
            Regex("'([^']{1,40})'").find(value)?.groupValues?.getOrNull(1)
    }
}
