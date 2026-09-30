package com.tsfdroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.JsonParser
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ProviderRequestConfig
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.data.models.ChatMessage
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.3.0 (Phase 14 WAVE C) wire proof: an OpenAI-compatible provider (not the
 * Zen provider, which already had this) must actually put
 * `reasoning_effort` in the chat-completions JSON body when the model's
 * models.dev registry spec lists the selected level — and must omit it (and
 * leave the request otherwise untouched) when it does not.
 *
 * [OpenRouterProvider] is the provider under test via the same test seam the
 * Zen provider uses (`endpoint` redirect); the models.dev registry is pointed
 * at the mock server so the whole chain is hermetic.
 *
 * Runs under Robolectric because a SUCCESSFUL registry fetch parses through
 * android's org.json, whose methods are stubs on the unmocked android.jar used
 * by plain JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
class OpenRouterEffortWireTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val registry = ModelsDevRegistry(OkHttpClient()).apply {
        registryUrl = server.url("/registry").toString()
    }
    private val provider = OpenRouterProvider(
        OkHttpClient(),
        newSettingsRepository(),
        registry
    ).apply { endpoint = server.url("/v1/chat/completions").toString() }

    private val apiKey = "sk-or-test-0123456789abcdef"
    private val prompt = "the-user-prompt"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a level the registry spec lists reaches the wire`() = runBlocking {
        server.enqueue(registryFixture())
        server.enqueue(successBody())

        val response = provider.complete(newRequest(effort = "high", model = "reasoning-model"))

        assertEquals("pong", response.content)
        server.takeRequest() // /registry
        val posted = server.takeRequest() // /v1/chat/completions
        val body = posted.body!!.utf8()
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("reasoning-model", json.get("model").asString)
        assertEquals("high", json.get("reasoning_effort").asString)
    }

    @Test
    fun `a level the spec does not list is omitted from the wire`() = runBlocking {
        server.enqueue(registryFixture())
        server.enqueue(successBody())

        provider.complete(newRequest(effort = "ultra", model = "reasoning-model"))

        server.takeRequest() // /registry
        val posted = server.takeRequest() // /v1/chat/completions
        assertFalse(posted.body!!.utf8().contains("reasoning_effort"))
    }

    @Test
    fun `a model without reasoning levels never receives the field`() = runBlocking {
        server.enqueue(registryFixture())
        server.enqueue(successBody())

        provider.complete(newRequest(effort = "high", model = "plain-model"))

        server.takeRequest() // /registry
        val posted = server.takeRequest() // /v1/chat/completions
        assertFalse(posted.body!!.utf8().contains("reasoning_effort"))
    }

    @Test
    fun `no selection skips the registry entirely and leaves the body unchanged`() = runBlocking {
        // Zero-regression proof for the no-effort path: exactly ONE request
        // (the completion itself) — the registry is never even consulted, so
        // the request shape is byte-identical to v1.2.x.
        server.enqueue(successBody())

        provider.complete(newRequest(effort = null, model = "reasoning-model"))

        assertEquals(1, server.requestCount)
        val posted = server.takeRequest()
        assertFalse(posted.body!!.utf8().contains("reasoning_effort"))
    }

    private fun newRequest(effort: String?, model: String) = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", prompt, ChatMessage.Sender.USER)),
        model = model,
        reasoningEffort = effort,
        providerConfig = ProviderRequestConfig(
            apiKey = apiKey,
            endpoint = server.url("/v1").toString()
        )
    )

    /**
     * models.dev shape (mirrors the live api.json "opencode" entry the
     * registry parses): one model with named variant levels, one without any.
     */
    private fun registryFixture() = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """
            {
              "opencode": {
                "id": "opencode",
                "npm": "@ai-sdk/openai-compatible",
                "models": {
                  "reasoning-model": {
                    "id": "reasoning-model",
                    "name": "Reasoning Model",
                    "reasoning": true,
                    "tool_call": true,
                    "limit": {"context": 100000, "output": 10000},
                    "cost": {"input": 1, "output": 2},
                    "variants": {"high": {}, "low": {}}
                  },
                  "plain-model": {
                    "id": "plain-model",
                    "name": "Plain Model",
                    "limit": {"context": 50000, "output": 5000},
                    "cost": {"input": 0, "output": 0}
                  }
                }
              }
            }
            """.trimIndent()
        )
        .build()

    private fun successBody() = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """
            {
              "choices": [{"message": {"content": "pong"}}],
              "usage": {"total_tokens": 7}
            }
            """.trimIndent()
        )
        .build()

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-effort-wire-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyProviderCredentialStore(),
        runStartupMigration = false
    )

    /** The request under test carries its own credential, so nothing is stored. */
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
