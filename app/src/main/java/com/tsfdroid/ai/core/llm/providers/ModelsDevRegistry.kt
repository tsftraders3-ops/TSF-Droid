package com.tsfdroid.ai.core.llm.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.annotation.VisibleForTesting

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
    /**
     * Named reasoning effort levels the model exposes, verbatim from the
     * registry — `variants` keys when present (e.g. "high", "low"), or
     * ["toggle"] when the model exposes a simple reasoning on/off switch.
     * Empty when the model has no levelled reasoning control.
     */
    val reasoningLevels: List<String>,
    /** True when the model costs nothing at the registry (input AND output are 0). */
    val free: Boolean,
    /**
     * Registry-flagged retired model (`status: "deprecated"`). Deprecated
     * models are kept parseable but excluded from the picker and the model
     * hierarchy, mirroring the official client's own filtering.
     */
    val deprecated: Boolean,
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
    private val mutex = Mutex()
    private var cached: Map<String, ZenModelSpec>? = null
    private var lastFetchAtMs: Long = 0L
    private var lastFailureAtMs: Long = 0L

    /**
     * v1.3.0: ALL-provider model metadata (context windows, reasoning levels,
     * input modalities) keyed by bare model id — populated from the same
     * models.dev fetch as the Zen spec map. Used for capability lookups on
     * NON-Zen providers (effort levels, history budgets, vision routing)
     * where the old spec map — deliberately restricted to the "opencode"
     * section so the Zen model chain can never leak a foreign model — was
     * always empty, making every capability lookup outside Zen a silent lie.
     */
    private var allProviderCache: Map<String, ZenModelSpec>? = null

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

            val outcome = withContext(Dispatchers.IO) { runCatching { fetchBody() } }
            outcome.getOrElse {
                lastFailureAtMs = System.currentTimeMillis()
                Log.w(TAG, "models.dev registry unavailable: ${it.message}")
                return cached.orEmpty()
            }.let { body ->
                val zenSpecs = parse(body)
                cached = zenSpecs
                allProviderCache = parseAllProviders(body)
                lastFetchAtMs = System.currentTimeMillis()
            }
            cached.orEmpty()
        }
    }

    /**
     * v1.3.0: capability metadata for ANY known model id across ALL
     * providers (not just the Zen section). Warms the shared cache on first
     * call; returns whatever is available offline.
     */
    suspend fun modelInfo(): Map<String, ZenModelSpec> {
        allProviderCache?.let { return it }
        specs() // single shared fetch populates both caches
        return allProviderCache.orEmpty()
    }

    private fun fetchRegistry(): Map<String, ZenModelSpec> = parse(fetchBody())

    private fun fetchBody(): String {
        val request = Request.Builder().url(registryUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("models.dev HTTP ${response.code}")
            val body = response.body.string()
            if (body.isBlank()) throw IOException("models.dev empty body")
            return body
        }
    }

    /** Test seam: registry tests redirect the URL to a local MockWebServer. */
    @VisibleForTesting
    internal var registryUrl: String = REGISTRY_URL

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
                val root = JSONObject(body)
                val provider = root.optJSONObject(ZEN_PROVIDER_ID) ?: return emptyMap()
                val models = provider.optJSONObject("models") ?: return emptyMap()
                val providerLevelNpm = provider.optString("npm").takeIf { it.isNotBlank() }
                models.keys().asSequence().mapNotNull { id ->
                    val obj = models.optJSONObject(id) ?: return@mapNotNull null
                    val specId = obj.optString("id").takeIf { it.isNotBlank() } ?: id
                    val limit = obj.optJSONObject("limit")
                    val context = limit?.optInt("context")?.takeIf { it > 0 } ?: return@mapNotNull null
                    val cost = obj.optJSONObject("cost")
                    val inputCost = cost?.optDouble("input") ?: Double.MAX_VALUE
                    val outputCost = cost?.optDouble("output") ?: Double.MAX_VALUE
                    val npm = obj.optJSONObject("provider")?.optString("npm")
                        ?.takeIf { it.isNotBlank() }
                        ?: providerLevelNpm
                    ZenModelSpec(
                        id = specId,
                        name = obj.optString("name").takeIf { it.isNotBlank() } ?: specId,
                        contextWindow = context,
                        maxOutput = limit.optInt("output").takeIf { it > 0 } ?: 0,
                        reasoning = obj.optBoolean("reasoning"),
                        toolCall = obj.optBoolean("tool_call"),
                        reasoningLevels = parseReasoningLevels(obj),
                        free = inputCost == 0.0 && outputCost == 0.0,
                        chatCompletions = npm == null || npm == CHAT_COMPLETIONS_SDK,
                        deprecated = obj.optString("status") == STATUS_DEPRECATED,
                        inputModalities = obj.optJSONObject("modalities")
                            ?.optJSONArray("input")
                            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it) } }
                            .orEmpty(),
                    )
                }.associateBy { it.id }
            }.getOrElse { emptyMap() }
        }

        private const val CHAT_COMPLETIONS_SDK = "@ai-sdk/openai-compatible"
        private const val STATUS_DEPRECATED = "deprecated"

        /**
         * v1.3.0: parses EVERY provider section of models.dev into one
         * metadata map keyed by bare model id (first provider wins on id
         * collisions — deterministic, and collisions across providers are
         * rare). Same per-model field extraction as [parse], but with no
         * Zen-section restriction: this map is for CAPABILITY LOOKUPS ONLY
         * (context windows, reasoning levels, modalities) — never for model
         * chain selection, which stays exclusively on the Zen [specs] map.
         */
        internal fun parseAllProviders(body: String): Map<String, ZenModelSpec> {
            return runCatching {
                val root = JSONObject(body)
                val out = LinkedHashMap<String, ZenModelSpec>()
                for (providerKey in root.keys().asSequence()) {
                    val provider = root.optJSONObject(providerKey) ?: continue
                    val models = provider.optJSONObject("models") ?: continue
                    val providerLevelNpm = provider.optString("npm").takeIf { it.isNotBlank() }
                    for (id in models.keys().asSequence()) {
                        if (out.containsKey(id)) continue
                        val obj = models.optJSONObject(id) ?: continue
                        parseModelEntry(obj, id, providerLevelNpm)?.let { spec ->
                            out[id] = spec
                        }
                    }
                }
                out
            }.getOrElse { emptyMap() }
        }

        /** Shared per-model spec extraction used by [parse] and [parseAllProviders]. */
        private fun parseModelEntry(
            obj: JSONObject,
            id: String,
            providerLevelNpm: String?
        ): ZenModelSpec? {
            val limit = obj.optJSONObject("limit")
            val context = limit?.optInt("context")?.takeIf { it > 0 } ?: return null
            val cost = obj.optJSONObject("cost")
            val inputCost = cost?.optDouble("input") ?: Double.MAX_VALUE
            val outputCost = cost?.optDouble("output") ?: Double.MAX_VALUE
            val npm = obj.optJSONObject("provider")?.optString("npm")
                ?.takeIf { it.isNotBlank() }
                ?: providerLevelNpm
            return ZenModelSpec(
                id = id,
                name = obj.optString("name").takeIf { it.isNotBlank() } ?: id,
                contextWindow = context,
                maxOutput = limit.optInt("output").takeIf { it > 0 } ?: 0,
                reasoning = obj.optBoolean("reasoning"),
                toolCall = obj.optBoolean("tool_call"),
                reasoningLevels = parseReasoningLevels(obj),
                free = inputCost == 0.0 && outputCost == 0.0,
                chatCompletions = npm == null || npm == CHAT_COMPLETIONS_SDK,
                deprecated = obj.optString("status") == STATUS_DEPRECATED,
                inputModalities = obj.optJSONObject("modalities")
                    ?.optJSONArray("input")
                    ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it) } }
                    .orEmpty(),
            )
        }

        /**
         * Reasoning-level extraction, mirroring how the OpenCode client
         * surfaces per-model reasoning controls: named `variants` win
         * (each key is an effort level), otherwise a `reasoning_options`
         * entry of type "toggle" collapses to the single "toggle" level.
         * Anything else means the model has no levelled control.
         */
        internal fun parseReasoningLevels(model: JSONObject): List<String> {
            val variants = model.optJSONObject("variants")
            if (variants != null) {
                val keys = variants.keys().asSequence().toList()
                if (keys.isNotEmpty()) return keys.sorted()
            }
            val options = model.optJSONArray("reasoning_options")
            if (options != null) {
                for (i in 0 until options.length()) {
                    val option = options.optJSONObject(i) ?: continue
                    if (option.optString("type") == "toggle") return listOf("toggle")
                }
            }
            return emptyList()
        }
    }
}
