package com.tsfdroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.repository.SettingsRepository
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the Zen wire contract against a real socket:
 * the provenance headers the official OpenCode client sends, the anonymous
 * "public" credential, the free-tier body contract (stream + harness tools)
 * whose absence caused the v1.0.1 keyless `403 FreeTierError`, and the
 * model-level fallback whose absence caused the `401 ModelError`
 * ("API key rejected" on a retired chain model).
 *
 * The models.dev registry is pointed at the mock server so every test is
 * hermetic; tests that don't need registry data enqueue a failure for the
 * registry fetch and rely on the static chain.
 */
class OpenCodeZenNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val registry = ModelsDevRegistry(OkHttpClient()).apply {
        registryUrl = server.url("/registry").toString()
    }
    private val provider = OpenCodeZenProvider(
        OkHttpClient(),
        newSettingsRepository(),
        registry
    ).apply { endpoint = server.url("/v1").toString() }

    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `requests carry the official client provenance and anonymous credential`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(successBody())

        provider.complete(newRequest())

        server.takeRequest() // registry
        server.takeRequest() // /models
        val posted = server.takeRequest()
        assertEquals("Bearer public", posted.headers["Authorization"])
        assertEquals("cli", posted.headers["x-opencode-client"])
        assertEquals(ZenIdentity.UA, posted.headers["User-Agent"])

        val project = posted.headers["x-opencode-project"]
        assertNotNull(project)
        assertEquals(26, project!!.length)
        assertTrue(posted.headers["x-opencode-session"]!!.startsWith("ses_"))
        assertTrue(posted.headers["x-opencode-request"]!!.startsWith("msg_"))
    }

    @Test
    fun `the free-tier body contract is satisfied - streaming plus read and shell harness tools`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(successBody())

        provider.complete(newRequest())

        server.takeRequest() // registry
        server.takeRequest() // /models
        val posted = server.takeRequest()
        val body = posted.body!!.utf8()
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        // The endpoint answers streaming requests only on the anonymous tier.
        assertEquals(true, json.get("stream").asBoolean)
        // The harness tool names the free tier gates on.
        val toolNames = json.getAsJsonArray("tools")
            .map { it.asJsonObject.getAsJsonObject("function").get("name").asString }
        assertTrue("tools must include 'read': $toolNames", "read" in toolNames)
        assertTrue("tools must include 'shell': $toolNames", "shell" in toolNames)
        // response_format is never sent: the official client does not, and
        // free-tier models reject it.
        assertFalse(body.contains("response_format"))
    }

    @Test
    fun `chunks with explicit null usage and choices do not break the stream`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}],"usage":null}

                    data: {"choices":null}

                    data: {"choices":[{"index":0,"delta":{"content":"!"}}]}

                    data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                    data: [DONE]

                    """.trimIndent()
                )
                .build()
        )

        val response = provider.complete(newRequest())

        assertEquals("Hi!", response.content)
        assertEquals(2, response.tokensUsed)
    }

    @Test
    fun `a 403 FreeTierError maps to the actionable free-tier error, not a bad-key error`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        // v1.0.4: the first attempt carries tool_choice:"none". A FreeTierError
        // on it triggers exactly one bounded fallback to the official-client
        // body (no tool_choice) before the block is surfaced.
        server.enqueue(freeTier403())
        server.enqueue(freeTier403())

        val thrown = runCatching { provider.complete(newRequest()) }
            .exceptionOrNull()

        server.takeRequest() // registry
        server.takeRequest() // /models
        val firstPosted = server.takeRequest()
        assertTrue(
            "the primary attempt should carry tool_choice",
            firstPosted.body!!.utf8().contains("tool_choice")
        )
        val secondPosted = server.takeRequest()
        assertFalse(
            "the fallback attempt must match the official-client body",
            secondPosted.body!!.utf8().contains("tool_choice")
        )

        assertNotNull(thrown)
        val exception = thrown!!
        assertTrue("got ${exception::class.simpleName}", exception is com.tsfdroid.ai.core.llm.error.LLMException)
        val llm = exception as com.tsfdroid.ai.core.llm.error.LLMException
        assertEquals(com.tsfdroid.ai.core.llm.error.LLMError.FreeTierBlocked, llm.error)
        assertEquals(403, llm.status)
        // The redacted detail must carry the vendor type for diagnostics.
        assertTrue(llm.detail?.toString()?.contains("FreeTierError") == true)
        // The user prompt must never leak into the redacted detail.
        assertTrue(llm.detail?.toString()?.contains(prompt) != true)
    }

    @Test
    fun `a retired model walks the hierarchy instead of failing the request`() = runBlocking {
        // 1. Registry fetch fails -> empty specs.
        server.enqueue(registryDown())
        // 2. /models discovery fails -> the static free chain bridges it.
        server.enqueue(MockResponse.Builder().code(503).body("down").build())
        // 3. The chain head is retired: Zen reports 401 type=ModelError.
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"type":"error","error":{"type":"ModelError","message":"Model ${OpenCodeZenProvider.DEFAULT_MODEL_CHAIN[0]} is not supported"}}""")
                .build()
        )
        // 4. The next link in the chain answers.
        server.enqueue(successBody())

        val response = provider.complete(newRequest(model = null))

        assertEquals(OpenCodeZenProvider.DEFAULT_MODEL_CHAIN[1], response.model)
        assertEquals("ok", response.content)
        // The successful POST must be the fourth recorded request.
        server.takeRequest() // registry
        server.takeRequest() // /models
        server.takeRequest() // failed head
        val success = server.takeRequest()
        val body = success.body!!.utf8()
        assertTrue("the retry must target the next chain model", body.contains(OpenCodeZenProvider.DEFAULT_MODEL_CHAIN[1]))
    }

    @Test
    fun `a region-blocked model walks the hierarchy instead of demanding an API key`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(MockResponse.Builder().code(503).body("down").build())
        // RegionError rides on 403 — historically misread as a key problem.
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"type":"error","error":{"type":"RegionError","message":"This model is not available in your region"}}""")
                .build()
        )
        server.enqueue(successBody())

        val response = provider.complete(newRequest(model = null))

        assertEquals(OpenCodeZenProvider.DEFAULT_MODEL_CHAIN[1], response.model)
        assertEquals("ok", response.content)
    }

    @Test
    fun `streamed deltas are aggregated with usage from the final chunk`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(streamBody())

        val response = provider.complete(newRequest())

        assertEquals("Hello world", response.content)
        assertEquals(42, response.tokensUsed)
    }

    @Test
    fun `streamComplete forwards content deltas as they arrive`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(streamBody())

        val deltas = provider.streamComplete(newRequest()).toList()

        assertEquals(listOf("Hello ", "world"), deltas)
    }

    @Test
    fun `reasoning deltas are never emitted as answer content`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(reasoningStreamBody())

        val deltas = provider.streamComplete(newRequest()).toList()

        assertEquals(listOf("Answer"), deltas)
    }

    @Test
    fun `a tool-call answer triggers one corrective re-ask that recovers the turn`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(toolCallsOnlyBody())
        server.enqueue(successBody())

        val response = provider.complete(newRequest())

        // v1.0.4: a tool-call answer is retried once with an explicit
        // no-tools instruction instead of failing the turn immediately.
        assertEquals("ok", response.content)
        server.takeRequest() // registry
        server.takeRequest() // /models
        server.takeRequest() // first attempt: tool-call answer
        val retry = server.takeRequest()
        val retryBody = retry.body!!.utf8()
        assertTrue(
            "the re-ask must carry the no-tools instruction",
            retryBody.contains("Do not call any tools")
        )
        assertTrue(
            "the re-ask stays on the gated wire shape",
            retryBody.contains("tool_choice")
        )
    }

    @Test
    fun `a persistent tool-call answer surfaces as a malformed response after the retry`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(toolCallsOnlyBody())
        server.enqueue(toolCallsOnlyBody())

        val exception = requireNotNull(
            runCatching { provider.complete(newRequest()) }.exceptionOrNull()
        ) { "a persistent tool-call answer must not succeed" }

        val llm = exception as com.tsfdroid.ai.core.llm.error.LLMException
        assertEquals(com.tsfdroid.ai.core.llm.error.LLMError.MalformedResponse, llm.error)
        assertFalse("a tool-call answer is not a network problem", llm.retryable)
        // Exactly two completion POSTs: the original + one corrective re-ask.
        server.takeRequest() // registry
        server.takeRequest() // /models
        server.takeRequest()
        server.takeRequest()
    }

    @Test
    fun `a free-tier request carries tool_choice none so models answer in prose`() = runBlocking {
        server.enqueue(registryDown())
        server.enqueue(modelsDown())
        server.enqueue(successBody())

        provider.complete(newRequest())

        server.takeRequest() // registry
        server.takeRequest() // /models
        val posted = server.takeRequest()
        val json = com.google.gson.JsonParser.parseString(posted.body!!.utf8()).asJsonObject
        // Live-verified (2026-09-25) that the gate accepts tool_choice:"none"
        // alongside the harness tools; it prevents reasoning models from
        // answering the harness contract with tool calls the app cannot run.
        assertEquals("none", json.get("tool_choice")?.asString)
    }

    @Test
    fun `the model picker never empties when discovery fails`() = runBlocking {
        // Registry + /models fail; the static chain must bridge the outage.
        server.enqueue(registryDown())
        server.enqueue(MockResponse.Builder().code(503).body("down").build())

        val models = provider.listPickerModels()

        assertEquals(OpenCodeZenProvider.DEFAULT_MODEL_CHAIN, models.map { it.id })
    }

    private fun registryDown() = MockResponse.Builder().code(503).body("registry down").build()

    private fun modelsDown() = MockResponse.Builder().code(503).body("models down").build()

    private fun freeTier403() = MockResponse.Builder()
        .code(403)
        .body("""{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode"}}""")
        .build()

    /** A stream where the model answers the harness contract with tool calls. */
    private fun toolCallsOnlyBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"shell","arguments":"{\"command\":\"ls\"}"}}]}}]}

            data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    /** Verified wire shape: SSE chat-completion stream, usage in the final chunk. */
    private fun successBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":30,"completion_tokens":2,"total_tokens":32}}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    /** Multi-delta stream with usage in the final chunk. */
    private fun streamBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello "}}]}

            data: {"choices":[{"index":0,"delta":{"content":"world"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":12,"total_tokens":42}}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    /** A stream where the model reasons before answering; reasoning is not content. */
    private fun reasoningStreamBody() = MockResponse.Builder()
        .code(200)
        .body(
            """
            data: {"choices":[{"index":0,"delta":{"reasoning":"thinking hard"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"Answer"}}]}

            data: [DONE]

            """.trimIndent()
        )
        .build()

    private fun newRequest(model: String? = "x-preview-f-free") = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", prompt, ChatMessage.Sender.USER)),
        model = model
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("zen-network-test")
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
