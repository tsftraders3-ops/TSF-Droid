package com.tsfdroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.toOpenAIMessages
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.data.models.AttachmentFile
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.MessageAttachments
import com.tsfdroid.ai.data.models.serializeMessageAttachments
import com.tsfdroid.ai.data.repository.SettingsRepository
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.2.0 wire contract additions, each one a direct mechanism against a real
 * socket:
 *  - finish_reason is parsed off the SSE stream (the continuation loop keys
 *    "length" on it — the old provider dropped it entirely);
 *  - reasoning_effort is sent ONLY for models whose registry entry lists the
 *    level (the OpenCode variant mechanism — never a cosmetic flag);
 *  - requireVision reroutes the chain to a vision-capable model;
 *  - multi-image messages serialize as one text part + N image_url parts.
 */
@RunWith(RobolectricTestRunner::class)
class OpenCodeZenFinishReasonTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val registry = ModelsDevRegistry(OkHttpClient()).apply {
        registryUrl = server.url("/registry").toString()
    }
    private val provider = OpenCodeZenProvider(
        OkHttpClient(),
        newSettingsRepository(),
        registry
    ).apply { endpoint = server.url("/v1").toString() }

    @After
    fun tearDown() {
        server.close()
    }

    /** Registry body: the pinned text model + a vision-capable free model. */
    private val registryBody = """
    {
      "opencode": {
        "id": "opencode",
        "npm": "@ai-sdk/openai-compatible",
        "name": "OpenCode Zen",
        "models": {
          "mimo-v2.6-flash-free": {
            "id": "mimo-v2.6-flash-free",
            "name": "Mimo",
            "reasoning": true,
            "variants": {
              "low": {"reasoningEffort": "low"},
              "medium": {"reasoningEffort": "medium"},
              "high": {"reasoningEffort": "high"}
            },
            "tool_call": true,
            "limit": {"context": 262144, "output": 32000},
            "modalities": {"input": ["text"], "output": ["text"]},
            "cost": {"input": 0, "output": 0}
          },
          "vision-pro-free": {
            "id": "vision-pro-free",
            "name": "Vision Pro Free",
            "reasoning": false,
            "tool_call": true,
            "limit": {"context": 131072, "output": 16384},
            "modalities": {"input": ["text", "image"], "output": ["text"]},
            "cost": {"input": 0, "output": 0}
          }
        }
      }
    }
    """.trimIndent()

    private fun registryOk() = MockResponse.Builder().code(200).body(registryBody).build()

    private fun modelsDown() = MockResponse.Builder().code(503).body("models down").build()

    /**
     * The registry caches its specs for 24h inside this test class instance,
     * so later tests see no registry HTTP call — fixed-position takeRequest
     * offsets would misalign. Scan for the actual chat-completions POST.
     */
    private fun completionBodyJson(): com.google.gson.JsonObject {
        repeat(6) {
            val request = server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)
                ?: error("no further request arrived")
            if (request.target.substringBefore('?').endsWith("/chat/completions")) {
                return com.google.gson.JsonParser.parseString(request.body!!.utf8()).asJsonObject
            }
        }
        error("chat-completions POST never arrived")
    }

    /** A stream carrying content and a final finish_reason of "length". */
    private fun lengthStreamBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"content":"partial ans"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"wer that was cut"}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}],"usage":{"total_tokens":90}}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    private fun stopStreamBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"content":"done"}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    @Test
    fun `finish_reason length is surfaced on complete responses`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(lengthStreamBody())

        val response = provider.complete(newRequest())

        assertEquals("length", response.finishReason)
        assertEquals("partial answer that was cut", response.content)
    }

    @Test
    fun `finish_reason stop is surfaced too`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        val response = provider.complete(newRequest())

        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `reasoning_effort is sent when the registry lists the level`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        provider.complete(newRequest().copy(reasoningEffort = "HIGH"))

        val json = completionBodyJson()
        assertEquals("high", json.get("reasoning_effort").asString)
    }

    @Test
    fun `reasoning_effort is omitted for levels the model does not support`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        provider.complete(newRequest().copy(reasoningEffort = "ultrathink"))

        val body = completionBodyJson().toString()
        assertFalse("unsupported level must never reach the wire", body.contains("reasoning_effort"))
    }

    @Test
    fun `reasoning_effort is omitted when the model has no levels`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        // vision-pro-free has no variants in the fixture.
        provider.complete(
            newRequest(model = "vision-pro-free").copy(reasoningEffort = "high")
        )

        val body = completionBodyJson().toString()
        assertFalse(body.contains("reasoning_effort"))
    }

    @Test
    fun `requireVision reroutes a non-vision pin to the vision-capable model`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        val response = provider.complete(
            newRequest(model = "mimo-v2.6-flash-free").copy(requireVision = true)
        )

        val json = completionBodyJson()
        assertEquals("vision-pro-free", json.get("model").asString)
        assertEquals("vision-pro-free", response.model)
    }

    @Test
    fun `a vision-capable pin keeps leading a requireVision turn`() = runBlocking {
        server.enqueue(registryOk())
        server.enqueue(modelsDown())
        server.enqueue(stopStreamBody())

        provider.complete(newRequest(model = "vision-pro-free").copy(requireVision = true))

        val json = completionBodyJson()
        assertEquals("vision-pro-free", json.get("model").asString)
    }

    @Test
    fun `multi-image messages serialize as text part plus image_url parts`() {
        val attachments = MessageAttachments(
            images = listOf("QUJD", "RERE"),
            files = listOf(
                AttachmentFile(name = "notes.txt", mime = "text/plain", inlineText = "hello world")
            ),
            notes = emptyList()
        )
        val message = ChatMessage(
            id = "1",
            text = "what do you see?",
            sender = ChatMessage.Sender.USER,
            imageBase64 = "RklSU1Q=",
            attachmentsJson = serializeMessageAttachments(attachments)
        )

        val wire = listOf(message).toOpenAIMessages("sys")

        assertEquals(2, wire.size) // system + user
        val content = wire[1]["content"] as List<*>
        assertEquals(4, content.size) // text + 3 images
        val textPart = content[0] as Map<*, *>
        assertEquals("text", textPart["type"])
        val text = textPart["text"] as String
        assertTrue(text.contains("what do you see?"))
        assertTrue(text.contains("hello world"))
        assertTrue(text.contains("notes.txt"))
        for (index in 1..3) {
            val imagePart = content[index] as Map<*, *>
            assertEquals("image_url", imagePart["type"])
            val url = (imagePart["image_url"] as Map<*, *>)["url"] as String
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
        }
    }

    @Test
    fun `plain text messages stay a single string content`() {
        val message = ChatMessage("1", "just text", ChatMessage.Sender.USER)
        val wire = listOf(message).toOpenAIMessages("sys")
        assertEquals("just text", wire[1]["content"])
    }

    private fun newRequest(model: String? = "mimo-v2.6-flash-free") = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "hello", ChatMessage.Sender.USER)),
        model = model
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("zen-finish-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyProviderCredentialStore(),
        runStartupMigration = false
    )

    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
