package com.tsfdroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tsfdroid.ai.core.llm.AIModel
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.ModelListParsers
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.error.LLMError
import com.tsfdroid.ai.core.llm.error.LLMException
import com.tsfdroid.ai.core.llm.error.ProviderErrorDetail
import com.tsfdroid.ai.core.llm.error.RedactedDetail
import com.tsfdroid.ai.core.llm.error.toSafeProviderException
import com.tsfdroid.ai.core.llm.network.OpenCodeZenInterceptor
import com.tsfdroid.ai.core.llm.toOpenAIMessages
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.annotation.VisibleForTesting

/**
 * Keyless cloud provider backed by the OpenCode Zen free tier
 * (https://opencode.ai/zen).
 *
 * Design notes (verified against the open-source OpenCode client and the
 * live endpoint):
 *  - Anonymous access authenticates with the literal key "public" plus the
 *    CLI's identity headers; see [ZenIdentity] and [OpenCodeZenInterceptor].
 *    A user-supplied Zen key (Settings -> Provider API Keys) replaces it and
 *    unlocks paid models.
 *  - Model hierarchy: when the caller does not pin a model, requests start on
 *    [DEFAULT_MODEL_CHAIN] head and walk the chain when an endpoint reports a
 *    model-level rejection, so a retired free model never bricks the agent.
 *  - Capabilities (context window, reasoning, tool-call, free/paid) come from
 *    the models.dev registry — the same catalog the OpenCode client uses —
 *    via [ModelsDevRegistry]; nothing is hardcoded per model.
 *  - Dynamic /models discovery: single-flight + 1h TTL + 10min failure
 *    cooldown (probe suppression guard - no https endpoint polling loops).
 *  - Anonymous requests only offer free models (input AND output cost 0),
 *    mirroring the official client, which deletes paid entries when no key
 *    is configured.
 */
@Singleton
class OpenCodeZenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val registry: ModelsDevRegistry
) : LLMProvider {

    override val name: String = "OpenCode Zen"

    /** Static default hierarchy served until live discovery resolves. */
    override val availableModels: List<String> = DEFAULT_MODEL_CHAIN

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Stable per-process project identifier for Zen provenance headers. */
    private val projectId: String = ZenIdentity.projectId()

    /** One session identifier per provider instance (per app run). */
    private val sessionId: String = ZenIdentity.sessionId()

    /**
     * Volatile snapshot of the credential the interceptor stamps. Interceptors
     * run on OkHttp threads outside any coroutine, so the suspend key lookup
     * refreshes this value at the start of every provider entry point instead.
     */
    @Volatile
    private var apiKeySnapshot: String = ZenIdentity.ANONYMOUS_KEY

    /** Zen-shaped client: identity + auth headers stamped on every call. */
    private val zenClient: OkHttpClient = client.newBuilder()
        .addInterceptor(
            OpenCodeZenInterceptor {
                OpenCodeZenInterceptor.Snapshot(
                    project = projectId,
                    session = sessionId,
                    request = ZenIdentity.requestId(),
                    apiKey = apiKeySnapshot
                )
            }
        )
        .build()

    // --- Probe suppression state (discovery cache + single-flight + cooldown) ---
    private val discoveryMutex = Mutex()
    private var cachedModels: List<String>? = null
    private var lastDiscoveryAtMs: Long = 0L
    private var lastFailureAtMs: Long = 0L

    /** Test seam: network tests redirect the endpoint to a local MockWebServer. */
    @VisibleForTesting
    internal var endpoint: String = BASE_URL

    private suspend fun currentApiKey(): String {
        val configured = settingsRepository.llmConfig.first().apiKeys[name]?.takeIf { it.isNotBlank() }
        return configured ?: ZenIdentity.ANONYMOUS_KEY
    }

    /** Refreshes the interceptor-visible credential snapshot. */
    private suspend fun refreshApiKeySnapshot() {
        apiKeySnapshot = currentApiKey()
    }

    private suspend fun hasUserKey(): Boolean =
        settingsRepository.llmConfig.first().apiKeys[name]?.takeIf { it.isNotBlank() } != null

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        refreshApiKeySnapshot()

        val chain = resolveModelChain(request)
        var lastError: Throwable = IOException("OpenCode Zen: no model attempted")

        for ((index, model) in chain.withIndex()) {
            try {
                return executeCompletion(request, model, startTime)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                lastError = throwable
                // Free-tier gating is not model-specific: walking the chain only
                // helps when THIS model is retired, so bail out immediately on
                // provenance/authorization failures.
                if (throwable is LLMException && throwable.error == LLMError.FreeTierBlocked) throw throwable
                if (!isModelLevelRejection(throwable) || index == chain.lastIndex) throw throwable
                // Walk the hierarchy: this model is gone/unknown, try the next.
            }
        }
        throw lastError
    }

    private suspend fun executeCompletion(
        request: LLMRequest,
        selectedModel: String,
        startTime: Long
    ): LLMResponse = withContext(Dispatchers.IO) {
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
        }

        val httpRequest = Request.Builder()
            .url("$endpoint/chat/completions")
            .post(gson.toJson(requestBodyMap).toRequestBody(mediaType))
            .build()

        zenClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403) {
                    val body = response.body.string()
                    if (body.contains("FreeTierError")) {
                        throw LLMException(
                            LLMError.FreeTierBlocked,
                            name,
                            selectedModel,
                            status = 403,
                            detail = RedactedDetail.fromProviderDetail(
                                ProviderErrorDetail.fromHttpFailure(
                                    ProviderErrorDetail.Provider.OPENCODE_ZEN,
                                    httpStatus = 403,
                                    rawBody = body,
                                    knownSecrets = emptyList(),
                                    forbiddenText = emptyList()
                                )
                            )
                        )
                    }
                }
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OPENCODE_ZEN,
                    request = request.copy(model = selectedModel),
                    knownSecrets = emptyList()
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from OpenCode Zen")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val choices = jsonResponse.getAsJsonArray("choices")
                ?: throw IOException("OpenCode Zen response missing choices array")
            if (choices.size() == 0) throw IOException("OpenCode Zen returned zero choices")
            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            val content = messageObj.get("content")?.asString
                ?: throw IOException("OpenCode Zen response missing message content")

            val usage = jsonResponse.getAsJsonObject("usage")
            val tokensUsed = usage?.get("total_tokens")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = tokensUsed,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val response = complete(request)
        val words = response.content.split(" ")
        for (word in words) {
            emit("$word ")
            kotlinx.coroutines.delay(50)
        }
    }

    /** Keyless tier: nothing to configure, so the provider is always selectable. */
    override suspend fun isAvailable(): Boolean = true

    /**
     * Probe-suppressed dynamic discovery. Returns the cached list within
     * [DISCOVERY_TTL], de-duplicates concurrent callers through
     * [discoveryMutex], and backs off for [FAILURE_COOLDOWN] after failures.
     */
    suspend fun discoverModels(): List<String> {
        val now = System.currentTimeMillis()
        cachedModels?.let { cached ->
            if (now - lastDiscoveryAtMs < DISCOVERY_TTL) return cached
        }
        if (now - lastFailureAtMs < FAILURE_COOLDOWN) {
            return cachedModels ?: DEFAULT_MODEL_CHAIN
        }

        return discoveryMutex.withLock {
            // Re-check under the lock: another caller may have refreshed.
            val recheck = System.currentTimeMillis()
            cachedModels?.takeIf { recheck - lastDiscoveryAtMs < DISCOVERY_TTL }
                ?.let { return it }
            if (recheck - lastFailureAtMs < FAILURE_COOLDOWN) {
                return cachedModels ?: DEFAULT_MODEL_CHAIN
            }

            val outcome = withContext(Dispatchers.IO) {
                runCatching { fetchEndpointModels() }
            }
            val live = outcome.getOrElse {
                lastFailureAtMs = System.currentTimeMillis()
                return cachedModels ?: DEFAULT_MODEL_CHAIN
            }
            val merged = (live + DEFAULT_MODEL_CHAIN).distinct()
            cachedModels = merged
            lastDiscoveryAtMs = System.currentTimeMillis()
            merged
        }
    }

    /** Primary source: the endpoint's own /models listing. */
    private fun fetchEndpointModels(): List<String> {
        val httpRequest = Request.Builder()
            .url("$endpoint/models")
            .get()
            .build()
        zenClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenCode Zen /models HTTP ${response.code}")
            val body = response.body.string()
            if (body.isBlank()) throw IOException("OpenCode Zen /models empty body")
            val json = gson.fromJson(body, JsonObject::class.java)
            val data = json.getAsJsonArray("data") ?: return emptyList()
            return data.mapNotNull { entry ->
                runCatching { entry.asJsonObject.get("id").asString }.getOrNull()
            }.filter { it.isNotBlank() }
        }
    }

    /**
     * Picker list: the registry (models.dev) annotated with the app's picker
     * metadata — context window, reasoning capability, free tier — merged with
     * the endpoint's live /models listing, executable chat-completions models
     * only. Never empty: the static chain is the floor.
     */
    suspend fun listPickerModels(): List<AIModel> {
        refreshApiKeySnapshot()
        val specs = runCatching { registry.specs() }.getOrDefault(emptyMap())
        val live = runCatching { discoverModels() }.getOrDefault(DEFAULT_MODEL_CHAIN)
        val anonymous = !hasUserKey()

        val ids = (DEFAULT_MODEL_CHAIN + live + specs.keys).distinct().filter { id ->
            val spec = specs[id]
            // Registry-known models must be executable by our transport;
            // unknown ids (live /models entries) stay listed — the chain
            // guarantees they were offered by the endpoint itself.
            spec == null || spec.chatCompletions
        }
        return ModelListParsers.opCodeZen(
            ids.filter { id ->
                val spec = specs[id] ?: return@filter true
                !anonymous || spec.free
            },
            specs
        )
    }

    private suspend fun resolveModelChain(request: LLMRequest): List<String> {
        val pinned = request.model?.takeIf { it.isNotBlank() && it != "custom-model" }
        if (pinned != null) return listOf(pinned)
        val specs = runCatching { registry.specs() }.getOrDefault(emptyMap())
        val anonymous = !hasUserKey()
        val discovered = runCatching { discoverModels() }.getOrDefault(DEFAULT_MODEL_CHAIN)
        // Requested free hierarchy first, then other live free models.
        val ordered = DEFAULT_MODEL_CHAIN.filter { discovered.contains(it) } +
            discovered.filter { candidate ->
                candidate.endsWith("-free") && !DEFAULT_MODEL_CHAIN.contains(candidate)
            } +
            specs.values.filter { it.free && it.chatCompletions && it.id !in discovered }.map { it.id }
        val executable = ordered.distinct().filter { id ->
            val spec = specs[id]
            spec == null || spec.chatCompletions
        }.filter { id ->
            val spec = specs[id]
            !anonymous || spec == null || spec.free
        }
        return executable.ifEmpty { DEFAULT_MODEL_CHAIN }
    }

    private fun isModelLevelRejection(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return message.contains("model") && (
            message.contains("not found") ||
                message.contains("unknown") ||
                message.contains("invalid") ||
                message.contains("does not exist") ||
                message.contains("no longer")
            )
    }

    companion object {
        const val BASE_URL = "https://opencode.ai/zen/v1"

        /** Directive-specified dynamic model hierarchy (head first). */
        val DEFAULT_MODEL_CHAIN = listOf(
            "x-preview-f-free",
            "muse-spark-1.2-contributor-free",
            "hy3-free",
            "mimo-v2.5-free"
        )

        private const val DISCOVERY_TTL = 60 * 60 * 1000L      // 1 hour
        private const val FAILURE_COOLDOWN = 10 * 60 * 1000L   // 10 minutes

        /**
         * Secondary discovery source: the community model registry. Parsed
         * defensively — any shape change degrades to the primary source.
         */
        fun parseModelsDevRegistry(body: String): List<String> {
            return ModelsDevRegistry.parse(body).keys.toList()
        }
    }
}
