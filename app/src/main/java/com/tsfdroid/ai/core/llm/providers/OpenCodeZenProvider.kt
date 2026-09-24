package com.tsfdroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.error.ProviderErrorDetail
import com.tsfdroid.ai.core.llm.error.toSafeProviderException
import com.tsfdroid.ai.core.llm.network.OpenCodeZenInterceptor
import com.tsfdroid.ai.core.llm.toOpenAIMessages
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

/**
 * Keyless cloud provider backed by the OpenCode Zen free tier
 * (https://opencode.ai/zen).
 *
 * Design notes:
 *  - No API key: [OpenCodeZenInterceptor] strips Authorization and stamps the
 *    client identification the endpoint expects.
 *  - Model hierarchy: when the caller does not pin a model, requests start on
 *    [DEFAULT_MODEL_CHAIN] head and walk the chain when an endpoint reports a
 *    model-level rejection, so a retired free model never bricks the agent.
 *  - Dynamic discovery: /models is consulted at most once per [DISCOVERY_TTL]
 *    with single-flight deduplication, and a failed discovery enters a
 *    [FAILURE_COOLDOWN] before the next attempt. This is the probe
 *    suppression guard: the https endpoint is never polled in a loop, unlike
 *    chatty local backends (e.g. Ollama's /api/tags).
 */
@Singleton
class OpenCodeZenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "OpenCode Zen"

    /** Static default hierarchy served until live discovery resolves. */
    override val availableModels: List<String> = DEFAULT_MODEL_CHAIN

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Shared client re-shaped for the keyless endpoint. */
    private val zenClient: OkHttpClient = client.newBuilder()
        .addInterceptor(OpenCodeZenInterceptor())
        .build()

    // --- Probe suppression state (discovery cache + single-flight + cooldown) ---
    private val discoveryMutex = Mutex()
    private var cachedModels: List<String>? = null
    private var lastDiscoveryAtMs: Long = 0L
    private var lastFailureAtMs: Long = 0L

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()

        val chain = resolveModelChain(request)
        var lastError: Throwable = IOException("OpenCode Zen: no model attempted")

        for ((index, model) in chain.withIndex()) {
            try {
                return executeCompletion(request, model, startTime)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                lastError = throwable
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
            .url("$BASE_URL/chat/completions")
            .post(gson.toJson(requestBodyMap).toRequestBody(mediaType))
            .build()

        zenClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
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

            val outcome: Result<List<String>> = withContext(Dispatchers.IO) {
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
            .url("$BASE_URL/models")
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

    private suspend fun resolveModelChain(request: LLMRequest): List<String> {
        val pinned = request.model?.takeIf { it.isNotBlank() && it != "custom-model" }
        if (pinned != null) return listOf(pinned)
        val discovered = runCatching { discoverModels() }.getOrDefault(DEFAULT_MODEL_CHAIN)
        // Requested free hierarchy first, then any other live free models.
        val ordered = DEFAULT_MODEL_CHAIN.filter { discovered.contains(it) } +
            discovered.filter { candidate ->
                candidate.endsWith("-free") && !DEFAULT_MODEL_CHAIN.contains(candidate)
            }
        return ordered.ifEmpty { DEFAULT_MODEL_CHAIN }
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
            return runCatching {
                val json = Gson().fromJson(body, JsonObject::class.java)
                val opencode = json.getAsJsonObject("opencode") ?: return emptyList()
                val models = opencode.getAsJsonObject("models") ?: return emptyList()
                models.keySet().filter { it.endsWith("-free") }
            }.getOrDefault(emptyList())
        }
    }
}
