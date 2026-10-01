package com.tsfdroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.error.ProviderErrorDetail
import com.tsfdroid.ai.core.llm.error.toSafeProviderException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"

/**
 * v1.3.0: REAL SSE streaming for the OpenAI wire-format family — OpenAI,
 * Groq, Mistral, OpenRouter, DeepSeek, Together AI, Copilot and the
 * user-configured Custom OpenAI-compatible endpoint.
 *
 * The old `streamComplete` implementations replayed a FINISHED `complete()`
 * word-by-word with a 50ms sleep: the user stared at an empty bubble for the
 * model's full latency, then watched a fake typewriter that was slower than
 * reading. This helper posts the exact same chat/completions body with
 * `"stream": true` added and forwards every `choices[0].delta.content`
 * fragment the moment the endpoint emits it — the honest surface, and the
 * one the in-app streaming UI was built for.
 *
 * The pump follows the OpenCode Zen transport discipline (the one proven on
 * the harness critical path since v1.2.0):
 *  - the whole pump runs on [Dispatchers.IO]; `channelFlow.send` is
 *    coroutine-context safe from there;
 *  - the pump context is captured ONCE and `ensureActive()` is checked on
 *    EVERY SSE line, so caller timeouts/cancellations land within one
 *    inter-line gap however slowly the model trickles;
 *  - error responses go through [toSafeProviderException] — the sanitized
 *    classification boundary that cannot leak bodies, keys or prompts.
 */
internal object OpenAICompatSSE {

    /**
     * Posts [bodyMap] (plus `"stream": true`) as chat/completions SSE and
     * forwards each content delta to [onDelta] as it arrives. Returns the
     * assembled content (handy for tests and for callers that need the whole
     * text after streaming).
     *
     * @param apiKey sent as `Authorization: Bearer <key>`; null sends no
     *   auth header (the Copilot proxy accepts keyless local calls).
     * @param extraHeaders provider-specific headers (OpenRouter attribution).
     */
    suspend fun pump(
        client: OkHttpClient,
        url: String,
        bodyMap: Map<String, Any>,
        gson: Gson,
        mediaType: MediaType,
        provider: ProviderErrorDetail.Provider,
        request: LLMRequest,
        model: String,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        knownSecrets: List<String> = apiKey?.let { listOf(it) } ?: emptyList(),
        onDelta: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val streamBody = bodyMap.toMutableMap()
        streamBody["stream"] = true

        val requestBuilder = Request.Builder()
            .url(url)
            .post(gson.toJson(streamBody).toRequestBody(mediaType))
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            requestBuilder.header("Authorization", "Bearer $key")
        }
        extraHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = provider,
                    request = request,
                    knownSecrets = knownSecrets
                )
            }

            val source = response.body.source()
            val content = StringBuilder()
            var tokensUsed = 0
            // v1.2.1 round-13 discipline (from the Zen pump): the read loop
            // is pure blocking IO with no suspension points — a caller's
            // timeout can only be OBSERVED when the loop looks. Check
            // EVERY line so cancellation lands within one inter-line gap.
            val pumpContext = currentCoroutineContext()
            BufferedReader(
                InputStreamReader(source.inputStream(), StandardCharsets.UTF_8)
            ).useLines { lines ->
                for (line in lines) {
                    pumpContext.ensureActive()
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue
                    val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
                    if (payload == SSE_DONE) break
                    val chunk = runCatching { gson.fromJson(payload, JsonObject::class.java) }
                        .getOrNull() ?: continue

                    // choices[0].delta.content — the OpenAI-compatible
                    // fragment shape. Some endpoints send an initial chunk
                    // with only `role`; a missing/blank delta is not an error.
                    val delta = chunk.getAsJsonArray("choices")
                        ?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")?.takeIf { it.isJsonPrimitive }
                        ?.asString.orEmpty()
                    if (delta.isNotEmpty()) {
                        content.append(delta)
                        onDelta(delta)
                    }
                    // With stream_options.include_usage the usage object rides
                    // on a final choices-less chunk; without it this stays 0.
                    chunk.getAsJsonObject("usage")?.get("total_tokens")
                        ?.takeIf { it.isJsonPrimitive }?.asInt?.let { tokensUsed = it }
                }
            }
            content.toString().also {
                android.util.Log.i(
                    "OpenAICompatSSE",
                    "stream done provider=${provider.displayName} model=$model chars=${it.length} tokens=$tokensUsed"
                )
            }
        }
    }
}
