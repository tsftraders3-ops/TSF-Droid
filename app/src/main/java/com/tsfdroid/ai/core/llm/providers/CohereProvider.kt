package com.tsfdroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tsfdroid.ai.core.llm.*
import com.tsfdroid.ai.core.llm.error.ProviderErrorDetail
import com.tsfdroid.ai.core.llm.error.toSafeProviderException
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
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

@Singleton
class CohereProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Cohere"
    override val availableModels: List<String> = listOf("command-r-plus", "command-r")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "command-r-plus"

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList
        )

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.cohere.ai/v2/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.COHERE,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Cohere")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val messageObj = jsonResponse.getAsJsonObject("message")
            val contentArray = messageObj.getAsJsonArray("content")
            val content = contentArray[0].asJsonObject.get("text").asString

            val usage = jsonResponse.getAsJsonObject("usage")
            val tokensUsed = usage?.getAsJsonObject("tokens")?.get("total_tokens")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = tokensUsed,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    // v1.3.0: REAL SSE streaming via /v2/chat with stream:true — the old
    // implementation replayed a finished complete() word-by-word after the
    // full model latency had already elapsed. Cohere v2 deltas are
    // {"type":"content-delta","delta":{"message":{"content":{"text":"..."}}}}.
    // Same per-line ensureActive discipline as the Zen pump.
    override fun streamComplete(request: LLMRequest): Flow<String> = channelFlow {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "command-r-plus"

        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "stream" to true
        )

        val httpRequest = Request.Builder()
            .url("https://api.cohere.ai/v2/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(gson.toJson(requestBodyMap).toRequestBody(mediaType))
            .build()

        withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw response.toSafeProviderException(
                        provider = ProviderErrorDetail.Provider.COHERE,
                        request = request,
                        knownSecrets = listOf(apiKey)
                    )
                }
                val pumpContext = currentCoroutineContext()
                val source = response.body.source()
                BufferedReader(
                    InputStreamReader(source.inputStream(), StandardCharsets.UTF_8)
                ).useLines { lines ->
                    for (line in lines) {
                        pumpContext.ensureActive()
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        val chunk = runCatching {
                            gson.fromJson(payload, JsonObject::class.java)
                        }.getOrNull() ?: continue
                        if (chunk.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                            != "content-delta"
                        ) continue
                        val text = chunk.getAsJsonObject("delta")
                            ?.getAsJsonObject("message")
                            ?.getAsJsonObject("content")
                            ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: continue
                        if (text.isNotEmpty()) send(text)
                    }
                }
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
