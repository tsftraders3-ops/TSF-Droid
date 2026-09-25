package com.tsfdroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tsfdroid.ai.core.llm.AIModel
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.LLMStreamEvent
import com.tsfdroid.ai.core.llm.ModelListParsers
import com.tsfdroid.ai.core.llm.PromptBudget
import com.tsfdroid.ai.core.llm.error.LLMError
import com.tsfdroid.ai.core.llm.error.LLMErrorMapper
import com.tsfdroid.ai.core.llm.error.LLMException
import com.tsfdroid.ai.core.llm.error.ProviderErrorDetail
import com.tsfdroid.ai.core.llm.error.RedactedDetail
import com.tsfdroid.ai.core.llm.error.consumeBoundedErrorBody
import com.tsfdroid.ai.core.llm.error.toSafeProviderException
import com.tsfdroid.ai.core.llm.network.OpenCodeZenInterceptor
import com.tsfdroid.ai.core.llm.toOpenAIMessages
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import androidx.annotation.VisibleForTesting

/**
 * Keyless cloud provider backed by the OpenCode Zen free tier
 * (https://opencode.ai/zen).
 *
 * Transport contract, verified against the live endpoint by replaying the
 * exact bytes the official OpenCode CLI (v2.0.16) sends:
 *
 *  1. Provenance headers: `Authorization: Bearer public` (the literal key
 *     "public" is the anonymous credential), a 4-segment
 *     `opencode/<channel>/<version>/<client>` User-Agent, and a
 *     `ses_`-prefixed session identifier. See [ZenIdentity] and
 *     [OpenCodeZenInterceptor].
 *  2. Harness tool contract: the free tier only serves requests that carry
 *     a `tools` array containing function tools **named `read` and
 *     `shell`** — the official client's harness always includes them.
 *     Requests without them are rejected with `403 FreeTierError`
 *     ("free tier can only be used from within OpenCode") no matter how
 *     perfect the headers are; this was the v1.0.1 keyless failure.
 *  3. Streaming: the endpoint answers only `stream: true` requests on the
 *     anonymous tier (`stream: false` is also gated behind FreeTierError),
 *     so every completion is transported as an SSE chat-completion stream
 *     and reassembled — [complete] aggregates the deltas into one
 *     [LLMResponse], [streamComplete] forwards them as they arrive.
 *  4. Model hierarchy: when the caller does not pin a model, requests
 *     start on the [DEFAULT_MODEL_CHAIN] head and walk the chain when the
 *     endpoint reports a model-level rejection (`401 type=ModelError` =
 *     retired/disabled model — NOT an authentication problem), so a
 *     renamed free model never bricks the agent.
 *  5. Capabilities (context window, reasoning, tool-call, free/paid) come
 *     from the models.dev registry — the same catalog the OpenCode client
 *     uses — via [ModelsDevRegistry]; nothing is hardcoded per model.
 *  6. Dynamic /models discovery: single-flight + 1h TTL + 10min failure
 *     cooldown (probe suppression guard — no endpoint polling loops).
 *  7. Anonymous requests only offer free models (input AND output cost 0),
 *     mirroring the official client, which deletes paid entries when no
 *     key is configured.
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
                return executeStreamingCompletion(request, model, startTime, onDelta = null, onReasoning = null)
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

    override fun streamComplete(request: LLMRequest): Flow<String> = channelFlow {
        streamChain(
            request,
            onContent = { delta ->
                // channelFlow.send is coroutine-context safe: deltas are
                // pumped from the OkHttp IO dispatcher.
                send(delta)
            },
            onReasoning = null
        )
    }

    /**
     * v1.0.5: content deltas as [LLMStreamEvent.Content] plus reasoning-model
     * thinking deltas as [LLMStreamEvent.Reasoning], so the chat UI can show
     * what the agent is thinking while it answers. Same chain-walk and
     * partial-emission semantics as [streamComplete].
     */
    override fun streamCompleteDetailed(request: LLMRequest): Flow<LLMStreamEvent> = channelFlow {
        streamChain(
            request,
            onContent = { delta -> send(LLMStreamEvent.Content(delta)) },
            onReasoning = { piece -> send(LLMStreamEvent.Reasoning(piece)) }
        )
    }

    /**
     * Shared model-chain walk for both streaming surfaces: try each model in
     * the resolved chain; once any CONTENT delta has reached the caller a
     * failure is terminal (walking would duplicate text — reasoning deltas
     * are disposable and do not pin the walk).
     */
    private suspend fun streamChain(
        request: LLMRequest,
        onContent: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ) {
        val startTime = System.currentTimeMillis()
        refreshApiKeySnapshot()

        val chain = resolveModelChain(request)
        var lastError: Throwable = IOException("OpenCode Zen: no model attempted")
        var emittedAny = false

        for ((index, model) in chain.withIndex()) {
            try {
                executeStreamingCompletion(
                    request,
                    model,
                    startTime,
                    onDelta = onContent?.let { callback ->
                        { delta: String ->
                            emittedAny = true
                            callback(delta)
                        }
                    },
                    onReasoning = onReasoning
                )
                return
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                lastError = throwable
                if (throwable is LLMException && throwable.error == LLMError.FreeTierBlocked) throw throwable
                // Walking after a partial stream would duplicate text: once
                // deltas reached the UI, a failure is terminal.
                if (emittedAny || !isModelLevelRejection(throwable) || index == chain.lastIndex) {
                    throw throwable
                }
            }
        }
        throw lastError
    }

    /**
     * The one Zen transport: POST a streaming chat-completion request with
     * the harness tool contract and pump the SSE body.
     *
     * When [onDelta] is null the stream is aggregated and a single
     * [LLMResponse] is returned; otherwise every content delta is forwarded
     * as it arrives (real streaming — not the v1.0.1 word-by-word replay)
     * and the returned response carries the assembled text.
     *
     * Bounded self-healing attempts (v1.0.4), each observed against
     * reasoning models on the free tier — never a loop, at most 4 posts:
     *
     *  1. gated body with `tool_choice: "none"` (live-verified accepted);
     *  2. if the gate or a model backend rejects that field
     *     (FreeTierError / 400), the exact official-client body once;
     *  3. if the model still answers the harness contract (`tool_calls`)
     *     instead of the user, one re-ask with an explicit no-tools
     *     instruction;
     *  4. if that re-ask itself hits a body-rejection, the plain body once.
     */
    private suspend fun executeStreamingCompletion(
        request: LLMRequest,
        selectedModel: String,
        startTime: Long,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ): LLMResponse = withContext(Dispatchers.IO) {
        val gated = runStreamAttempt(request, selectedModel, onDelta, onReasoning, sendToolChoice = true, appendNoToolGuard = false)
        val first = if (gated.bodyRejected) {
            runStreamAttempt(request, selectedModel, onDelta, onReasoning, sendToolChoice = false, appendNoToolGuard = false)
        } else {
            gated
        }

        var pump = first
        var answeredWithTools = first.answeredWithToolCalls
        if (first.answeredWithToolCalls) {
            pump = runStreamAttempt(request, selectedModel, onDelta, onReasoning, sendToolChoice = true, appendNoToolGuard = true)
            if (pump.bodyRejected) {
                pump = runStreamAttempt(request, selectedModel, onDelta, onReasoning, sendToolChoice = false, appendNoToolGuard = true)
            }
            answeredWithTools = answeredWithTools || pump.sawToolCall
        }

        val assembled = pump.content
        if (assembled.isBlank()) {
            if (answeredWithTools) {
                throw LLMErrorMapper.malformed(name, selectedModel)
            }
            throw IOException("OpenCode Zen stream completed without content")
        }
        LLMResponse(
            content = assembled,
            tokensUsed = pump.tokensUsed,
            model = selectedModel,
            provider = name,
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    /** Aggregated result of one SSE stream pump. */
    private class StreamPump(
        val content: String,
        val tokensUsed: Int,
        val sawToolCall: Boolean,
        val bodyRejected: Boolean,
        val reasoning: String = ""
    ) {
        /** Tool-call answer with zero prose: the corrective-retry trigger. */
        val answeredWithToolCalls: Boolean
            get() = sawToolCall && content.isBlank() && !bodyRejected
    }

    private suspend fun runStreamAttempt(
        request: LLMRequest,
        selectedModel: String,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?,
        sendToolChoice: Boolean,
        appendNoToolGuard: Boolean
    ): StreamPump = withContext(Dispatchers.IO) {
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt).toMutableList()
        if (appendNoToolGuard) {
            // Trailing user-role guard keeps the wire shape identical to a
            // normal turn; a system message after user turns is normalized
            // differently across compatible backends.
            messagesList.add(mapOf("role" to "user", "content" to NO_TOOLS_GUARD))
        }

        // Context-window-aware output clamp: models.dev publishes each
        // model's limit.context/limit.output — the same numbers the OpenCode
        // client uses — so the requested output budget is clamped to what
        // actually fits alongside the prompt instead of relying on the
        // endpoint to reject oversize requests.
        val spec = runCatching { registry.specs()[selectedModel] }.getOrNull()
        val effectiveMaxTokens = clampOutputBudget(request, spec)

        val requestBodyMap = linkedMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "temperature" to request.temperature,
            "max_tokens" to effectiveMaxTokens,
            // The anonymous tier only answers streaming requests.
            "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
            // The harness tool contract the free tier gates on. App-defined
            // tools ride along after it (verified: extra tools are accepted).
            "tools" to buildToolsPayload(request)
        )
        if (sendToolChoice) {
            // The app never executes OpenAI tool calls, so the model must
            // answer in prose/JSON even with tools present. Live-verified
            // (2026-09-25): the free-tier gate accepts `tool_choice: "none"`
            // with the mandatory read+shell harness tools.
            requestBodyMap["tool_choice"] = "none"
        }
        // Note: response_format is deliberately NOT sent — the official
        // client never does, and free-tier models reject the field.

        val httpRequest = Request.Builder()
            .url("$endpoint/chat/completions")
            .post(gson.toJson(requestBodyMap).toRequestBody(mediaType))
            .build()

        zenClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                // Single bounded read: the body is consumed here and handed
                // to both the FreeTierError check and the classifier. A
                // second read would hit a closed source (IllegalStateException,
                // not IOException) and replace the classified error.
                val errorBody = runCatching { response.consumeBoundedErrorBody() }.getOrNull()
                if (response.code == 403 && errorBody?.contains("FreeTierError") == true) {
                    if (sendToolChoice) {
                        // Signal the caller to retry without tool_choice
                        // before this becomes a user-facing failure.
                        return@withContext StreamPump(
                            content = "",
                            tokensUsed = 0,
                            sawToolCall = false,
                            bodyRejected = true
                        )
                    }
                    throw LLMException(
                        LLMError.FreeTierBlocked,
                        name,
                        selectedModel,
                        status = 403,
                        detail = RedactedDetail.fromProviderDetail(
                            ProviderErrorDetail.fromHttpFailure(
                                ProviderErrorDetail.Provider.OPENCODE_ZEN,
                                httpStatus = 403,
                                rawBody = errorBody,
                                knownSecrets = emptyList(),
                                // Same scrub scope as toSafeProviderException:
                                // endpoint-controlled text must never carry
                                // prompt content into diagnostics.
                                forbiddenText = buildList {
                                    add(response.request.url.toString())
                                    add(request.systemPrompt)
                                    request.messages.forEach { message ->
                                        add(message.text)
                                        message.imageBase64?.let(::add)
                                    }
                                }
                            )
                        )
                    )
                }
                if (response.code == 400 && sendToolChoice) {
                    // A per-model backend may not accept tool_choice even when
                    // the gate does: degrade to the official-client body once.
                    // A genuine bad request (context overflow, malformed
                    // messages) reproduces on the retry and is rethrown then.
                    return@withContext StreamPump(
                        content = "",
                        tokensUsed = 0,
                        sawToolCall = false,
                        bodyRejected = true
                    )
                }
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OPENCODE_ZEN,
                    request = request.copy(model = selectedModel),
                    knownSecrets = emptyList(),
                    preReadBody = errorBody
                )
            }

            val source = response.body.source()
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var tokensUsed = 0
            var sawToolCall = false

            BufferedReader(InputStreamReader(source.inputStream(), StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue
                    val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
                    if (payload == SSE_DONE) break
                    val chunk = runCatching { gson.fromJson(payload, JsonObject::class.java) }
                        .getOrNull() ?: continue

                    // Null-safe accessors: OpenAI-compatible streams carry
                    // explicit `"usage": null` on every non-final chunk when
                    // include_usage is on, and gson's getAsJsonObject throws
                    // ClassCastException on JsonNull.
                    val usage = chunk.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (usage != null) {
                        tokensUsed = usage.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: tokensUsed
                    }

                    val choices = chunk.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    if (choices.size() == 0) continue
                    val first = choices[0]
                    if (!first.isJsonObject) continue
                    val delta = first.asJsonObject.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: continue
                    // Reasoning deltas (`reasoning` / `reasoning_content`) are
                    // model thinking, not answer content: v1.0.5 forwards them
                    // to [onReasoning] for the THINKING UI, but they are never
                    // emitted as chat text.
                    // Tool-call deltas mean the model answered the harness
                    // contract instead of writing prose; the caller retries
                    // once with a no-tools instruction before surfacing this
                    // as a malformed response.
                    if (delta.get("tool_calls")?.isJsonArray == true) sawToolCall = true
                    val thinkingPiece = delta.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: delta.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!thinkingPiece.isNullOrEmpty()) {
                        reasoning.append(thinkingPiece)
                        onReasoning?.invoke(thinkingPiece)
                    }
                    val piece = delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!piece.isNullOrEmpty()) {
                        content.append(piece)
                        onDelta?.invoke(piece)
                    }
                }
            }

            StreamPump(
                content = content.toString(),
                tokensUsed = tokensUsed,
                sawToolCall = sawToolCall,
                bodyRejected = false,
                reasoning = reasoning.toString()
            )
        }
    }

    /**
     * Clamps the requested output budget to the model's registry-published
     * context window and output ceiling. When the registry is unavailable or
     * the prompt already overflows the window, the request passes through
     * unclamped and the endpoint's own context-length error surfaces.
     */
    private fun clampOutputBudget(request: LLMRequest, spec: ZenModelSpec?): Int {
        if (spec == null) return request.maxTokens
        val promptText = request.systemPrompt + "\n" + request.messages.joinToString("\n") { it.text }
        val fitsInContext = PromptBudget.outputBudget(promptText, spec.contextWindow, request.maxTokens)
            ?: return request.maxTokens
        val outputCeiling = spec.maxOutput.takeIf { it > 0 } ?: fitsInContext
        return minOf(fitsInContext, outputCeiling).coerceAtLeast(PromptBudget.MIN_OUTPUT_TOKENS)
    }

    /**
     * OpenAI function-tool payload carrying the two harness names the free
     * tier requires (`read`, `shell`). Definitions mirror the official
     * client's shape but are minimal — the endpoint validates the names,
     * not the schemas.
     */
    private fun buildToolsPayload(request: LLMRequest): List<Map<String, Any>> {
        val tools = mutableListOf(HARNESS_READ_TOOL, HARNESS_SHELL_TOOL)
        for (tool in request.tools.orEmpty()) {
            tools.add(
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to (runCatching {
                            gson.fromJson(tool.parameters, JsonObject::class.java)
                        }.getOrNull() ?: JsonObject())
                    )
                )
            )
        }
        return tools
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
            // Registry-known models must be executable by our transport and
            // not registry-flagged deprecated; unknown ids (live /models
            // entries) stay listed — the chain guarantees they were offered
            // by the endpoint itself.
            spec == null || (spec.chatCompletions && !spec.deprecated)
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
        val hierarchy = resolveHierarchy()
        if (pinned == null) return hierarchy
        // A pinned model LEADS the chain; it does not die alone. A persisted
        // selection can go stale between releases (the v1.0.1 failure: the
        // default pin retired server-side), so keyless requests fall back to
        // the verified free hierarchy when the pinned model is rejected as
        // retired/region-blocked. With a user key the pinned model is final —
        // the paid tier can serve whatever the picker offered.
        return if (hasUserKey()) {
            listOf(pinned)
        } else {
            (listOf(pinned) + hierarchy.filter { it != pinned }).distinct()
        }
    }

    private suspend fun resolveHierarchy(): List<String> {
        val specs = runCatching { registry.specs() }.getOrDefault(emptyMap())
        val anonymous = !hasUserKey()
        val discovered = runCatching { discoverModels() }.getOrDefault(DEFAULT_MODEL_CHAIN)
        // Requested free hierarchy first, then other live free models.
        // Registry-flagged deprecated models never lead the hierarchy: the
        // official client drops them, and a retired id is how v1.0.1's
        // chain bricked itself.
        val ordered = DEFAULT_MODEL_CHAIN.filter { discovered.contains(it) && specs[it]?.deprecated != true } +
            discovered.filter { candidate ->
                candidate.endsWith("-free") && !DEFAULT_MODEL_CHAIN.contains(candidate)
            } +
            specs.values.filter {
                it.free && it.chatCompletions && !it.deprecated && it.id !in discovered
            }.map { it.id }
        val executable = ordered.distinct().filter { id ->
            val spec = specs[id]
            (spec == null || (spec.chatCompletions && !spec.deprecated))
        }.filter { id ->
            val spec = specs[id]
            !anonymous || spec == null || spec.free
        }
        return executable.ifEmpty { DEFAULT_MODEL_CHAIN }
    }

    /**
     * True when the endpoint blamed THIS model rather than the caller: the
     * request may succeed against the next link in the hierarchy.
     *
     * The Zen dialect reports retired/disabled models as
     * `401 {"error":{"type":"ModelError","message":"Model x is not
     * supported"}}` (and "trial ended", "no provider available", "model
     * disabled" variants), so the message check includes those phrasings —
     * v1.0.1 only matched "not found"-style text and let one stale chain
     * entry brick every request.
     */
    private fun isModelLevelRejection(throwable: Throwable): Boolean {
        if (throwable is LLMException) {
            when (throwable.error) {
                LLMError.ModelUnavailable -> return true
                LLMError.AuthInvalid, LLMError.AuthMissing, LLMError.FreeTierBlocked -> return false
                else -> Unit
            }
        }
        val message = throwable.message?.lowercase() ?: return false
        return message.contains("model") && (
            message.contains("not supported") ||
                message.contains("not found") ||
                message.contains("unknown") ||
                message.contains("does not exist") ||
                message.contains("no longer") ||
                message.contains("unavailable") ||
                message.contains("not available in your region") ||
                message.contains("model disabled") ||
                message.contains("no provider available") ||
                message.contains("trial ended")
            )
    }

    companion object {
        const val BASE_URL = "https://opencode.ai/zen/v1"

        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"

        /** Trailed onto the corrective re-ask when a model answers with tool_calls. */
        private const val NO_TOOLS_GUARD =
            "Tool calls are not available in this session. Do not call any tools. " +
                "Answer the user's request directly in plain text or JSON."

        /**
         * Verified-live free hierarchy (2026-09-25 /zen/v1/models + per-model
         * live chat-completions probes). Ordered for agent quality: verified
         * 200-responding long-context generalists first; the two entries at
         * the tail answered transient upstream errors at probe time and stay
         * as late links since discovery reshuffles the rest. The muse-spark
         * contributor models are excluded from the static chain (region-
         * gated for many users, and responses-only at the registry) but
         * remain reachable through live discovery.
         */
        val DEFAULT_MODEL_CHAIN = listOf(
            "mimo-v2.6-flash-free",
            "nemotron-3.5-lightning-free",
            "space-bunny-free",
            "ling-3.0-flash-fin-free",
            "nemotron-3-ultra-free",
            "mimo-v2.5-free",
            "deepseek-v4-flash-free",
            "jev-1.13-free"
        )

        private const val DISCOVERY_TTL = 60 * 60 * 1000L      // 1 hour
        private const val FAILURE_COOLDOWN = 10 * 60 * 1000L   // 10 minutes

        /** Minimal harness `read` tool — name is what the free tier gates on. */
        private val HARNESS_READ_TOOL: Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "read",
                "description" to "Read the contents of a file.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "File path to read")
                    ),
                    "required" to listOf("path")
                )
            )
        )

        /** Minimal harness `shell` tool — name is what the free tier gates on. */
        private val HARNESS_SHELL_TOOL: Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "shell",
                "description" to "Run a shell command and return its output.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf("type" to "string", "description" to "Command to execute")
                    ),
                    "required" to listOf("command")
                )
            )
        )

        /**
         * Secondary discovery source: the community model registry. Parsed
         * defensively — any shape change degrades to the primary source.
         */
        fun parseModelsDevRegistry(body: String): List<String> {
            return ModelsDevRegistry.parse(body).keys.toList()
        }
    }
}
