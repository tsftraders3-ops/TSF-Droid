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
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Google Gemini"
    // v1.3.0: "gemini-nano" removed. The AICore on-device model was never
    // actually wired — the old code answered it with a canned mock (fake
    // data presented as the model's reply). A saved config still pinned to
    // gemini-nano now hits the real endpoint and gets the honest
    // ModelUnavailable classification (404) instead of fabricated text.
    override val availableModels: List<String> = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)

        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")
        val startTime = System.currentTimeMillis()

        // Map roles to user and model
        val contentsList = mutableListOf<Map<String, Any>>()
        request.messages.forEach { msg ->
            val role = if (msg.sender == com.tsfdroid.ai.data.models.ChatMessage.Sender.USER) "user" else "model"
            val partsList = mutableListOf<Map<String, Any>>()
            partsList.add(mapOf("text" to msg.text))
            if (msg.imageBase64 != null && role == "user") {
                partsList.add(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to "image/jpeg",
                            "data" to msg.imageBase64
                        )
                    )
                )
            }
            contentsList.add(
                mapOf(
                    "role" to role,
                    "parts" to partsList
                )
            )
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "contents" to contentsList,
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            )
        )

        val generationConfig = mutableMapOf<String, Any>(
            "temperature" to request.temperature,
            "maxOutputTokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            generationConfig["responseMimeType"] = "application/json"
        }
        requestBodyMap["generationConfig"] = generationConfig

        val bodyJson = gson.toJson(requestBodyMap)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.GEMINI,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from Gemini")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = jsonResponse.getAsJsonArray("candidates")
            val firstCandidate = candidates[0].asJsonObject
            val contentObj = firstCandidate.getAsJsonObject("content")
            val parts = contentObj.getAsJsonArray("parts")
            val text = parts[0].asJsonObject.get("text").asString

            val usageMetadata = jsonResponse.getAsJsonObject("usageMetadata")
            val totalTokens = usageMetadata?.get("totalTokenCount")?.asInt ?: 0

            LLMResponse(
                content = text,
                tokensUsed = totalTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    // v1.3.0: REAL SSE streaming via :streamGenerateContent?alt=sse — the
    // old implementation replayed a finished complete() word-by-word after
    // the full model latency had already elapsed. Gemini's SSE chunks carry
    // candidates[0].content.parts[].text fragments (no [DONE] marker — the
    // stream simply closes). Same per-line ensureActive discipline as the
    // Zen pump so caller timeouts land within one inter-line gap.
    override fun streamComplete(request: LLMRequest): Flow<String> = channelFlow {
        val config = settingsRepository.llmConfig.first()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val bodyJson = gson.toJson(buildRequestBody(request))
        val httpRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw response.toSafeProviderException(
                        provider = ProviderErrorDetail.Provider.GEMINI,
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
                        // A chunk can carry several parts; concatenate the text ones.
                        val parts = chunk.getAsJsonArray("candidates")
                            ?.firstOrNull()?.asJsonObject
                            ?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts") ?: continue
                        val text = parts.joinToString("") { part ->
                            part.asJsonObject.get("text")
                                ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        }
                        if (text.isNotEmpty()) send(text)
                    }
                }
            }
        }
    }

    /** The generateContent body shared by [complete] and [streamComplete]. */
    private fun buildRequestBody(request: LLMRequest): Map<String, Any> {
        val contentsList = mutableListOf<Map<String, Any>>()
        request.messages.forEach { msg ->
            val role = if (msg.sender == com.tsfdroid.ai.data.models.ChatMessage.Sender.USER) "user" else "model"
            val partsList = mutableListOf<Map<String, Any>>()
            partsList.add(mapOf("text" to msg.text))
            if (msg.imageBase64 != null && role == "user") {
                partsList.add(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to "image/jpeg",
                            "data" to msg.imageBase64
                        )
                    )
                )
            }
            contentsList.add(
                mapOf(
                    "role" to role,
                    "parts" to partsList
                )
            )
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "contents" to contentsList,
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            )
        )

        val generationConfig = mutableMapOf<String, Any>(
            "temperature" to request.temperature,
            "maxOutputTokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            generationConfig["responseMimeType"] = "application/json"
        }
        requestBodyMap["generationConfig"] = generationConfig
        return requestBodyMap
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
