package com.tsfdroid.ai.core.llm.providers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One model's capabilities as published by the community registry
 * models.dev (the same source the OpenCode client uses to auto-populate
 * context windows, reasoning capability, tool-calling, and cost).
 */
data class ZenModelSpec(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val maxOutput: Int,
    val reasoning: Boolean,
    val toolCall: Boolean,
    /** True when the model costs nothing at the registry (input AND output are 0). */
    val free: Boolean,
    /**
     * True when the model speaks the OpenAI chat-completions dialect, which is
     * the protocol TSF Droid's Zen transport implements. Registry entries whose
     * npm package is `@ai-sdk/openai` are served by the Responses API instead
     * and are not offerable in the picker until that transport exists.
     */
    val chatCompletions: Boolean,
    val inputModalities: List<String>,
)

/**
 * Models.dev registry access for the OpenCode Zen provider.
 *
 * This is how model capabilities are "automatically set": instead of a
 * hardcoded table, the agent reads each model's context window, reasoning
 * flag, tool-calling support, and free/paid status from the registry the
 * OpenCode ecosystem itself maintains, with the static fallbacks in
 * [OpenCodeZenProvider.DEFAULT_MODEL_CHAIN] bridging registry outages.
 *
 * Fetching follows the same probe-suppression discipline as model
 * discovery: single-flight, a 24h success TTL, and a 30-minute failure
 * cooldown, so a registry outage never turns into a polling loop.
 */
@Singleton
class ModelsDevRegistry @Inject constructor(
    private val client: OkHttpClient
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private var cached: Map<String, ZenModelSpec>? = null
    private var lastFetchAtMs: Long = 0L
    private var lastFailureAtMs: Long = 0L

    suspend fun specs(): Map<String, ZenModelSpec> {
        val now = System.currentTimeMillis()
        cached?.let { map ->
            if (now - lastFetchAtMs < SUCCESS_TTL) return map
        }
        if (now - lastFailureAtMs < FAILURE_COOLDOWN) return cached.orEmpty()

        return mutex.withLock {
            val recheck = System.currentTimeMillis()
            cached?.takeIf { recheck - lastFetchAtMs < SUCCESS_TTL }?.let { return it }
            if (recheck - lastFailureAtMs < FAILURE_COOLDOWN) return cached.orEmpty()

            val outcome = withContext(Dispatchers.IO) { runCatching { fetchRegistry() } }
            outcome.getOrElse {
                lastFailureAtMs = System.currentTimeMillis()
                Log.w(TAG, "models.dev registry unavailable: ${it.message}")
                return cached.orEmpty()
            }.also { specs ->
                cached = specs
                lastFetchAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun fetchRegistry(): Map<String, ZenModelSpec> {
        val request = Request.Builder().url(REGISTRY_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("models.dev HTTP ${response.code}")
            val body = response.body.string()
            if (body.isBlank()) throw IOException("models.dev empty body")
            return parse(body)
        }
    }

    companion object {
        private const val TAG = "ModelsDevRegistry"
        const val REGISTRY_URL = "https://models.dev/api.json"
        const val ZEN_PROVIDER_ID = "opencode"

        private const val SUCCESS_TTL = 24 * 60 * 60 * 1000L
        private const val FAILURE_COOLDOWN = 30 * 60 * 1000L

        /**
         * Pure registry-to-spec translation. Defensive by design: any shape
         * drift degrades to fewer specs rather than throwing, and entries
         * missing a usable context limit are dropped.
         *
         * Protocol resolution mirrors models.dev's own layering: a per-model
         * `provider.npm` overrides the provider-level default, and only the
         * `@ai-sdk/openai-compatible` dialect is executable by this app's
         * chat-completions transport.
         */
        fun parse(body: String): Map<String, ZenModelSpec> {
            return runCatching {
                val gson = Gson()
                val root = gson.fromJson(body, JsonObject::class.java)
                val provider = root.getAsJsonObject(ZEN_PROVIDER_ID) ?: return emptyMap()
                val models = provider.getAsJsonObject("models") ?: return emptyMap()
                val providerLevelNpm = provider.optString("npm").takeIf { it.isNotBlank() }
                models.entrySet().mapNotNull { (id, element) ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val specId = obj.optString("id").takeIf { it.isNotBlank() } ?: id
                    val limit = obj.getAsJsonObject("limit")
                    val context = limit?.optInt("context")?.takeIf { it > 0 } ?: return@mapNotNull null
                    val cost = obj.getAsJsonObject("cost")
                    val inputCost = cost?.optDouble("input") ?: Double.MAX_VALUE
                    val outputCost = cost?.optDouble("output") ?: Double.MAX_VALUE
                    val npm = obj.optJsonObject("provider")?.optString("npm")
                        ?.takeIf { it.isNotBlank() }
                        ?: providerLevelNpm
                    ZenModelSpec(
                        id = specId,
                        name = obj.optString("name").takeIf { it.isNotBlank() } ?: specId,
                        contextWindow = context,
                        maxOutput = limit.optInt("output").takeIf { it > 0 } ?: 0,
                        reasoning = obj.optBoolean("reasoning"),
                        toolCall = obj.optBoolean("tool_call"),
                        free = inputCost == 0.0 && outputCost == 0.0,
                        chatCompletions = npm == null || npm == CHAT_COMPLETIONS_SDK,
                        inputModalities = obj.optJsonObject("modalities")
                            ?.optJsonArray("input")
                            ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                            .orEmpty(),
                    )
                }.associateBy { it.id }
            }.getOrElse { emptyMap() }
        }

        private const val CHAT_COMPLETIONS_SDK = "@ai-sdk/openai-compatible"
    }
}
