package com.tsfdroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ProviderRequestConfig
import com.tsfdroid.ai.core.llm.error.LLMError
import com.tsfdroid.ai.core.llm.error.LLMException
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.repository.SettingsRepository
import com.tsfdroid.ai.di.AppModule
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0: proves the OpenAI-compatible family REALLY streams — the shared
 * [OpenAICompatSSE] pump posts `"stream": true`, forwards every SSE delta as
 * the endpoint emits it (not a word-by-word replay of a finished completion),
 * terminates on `[DONE]`, and still routes HTTP failures through the
 * sanitized provider-error boundary.
 *
 * [CustomOpenAIProvider] is the carrier because its endpoint is
 * caller-supplied; every other OpenAI-compatible provider reaches the same
 * pump with a hard-coded host.
 */
class OpenAICompatSSEStreamingTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    private val registry = ModelsDevRegistry(AppModule.provideOkHttpClient()).apply {
        registryUrl = server.url("/registry").toString()
    }

    private val provider = CustomOpenAIProvider(
        AppModule.provideOkHttpClient(),
        newSettingsRepository(),
        registry
    )

    private val apiKey = "sk-test-0123456789abcdef"
    private val prompt = "stream-this"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sse deltas stream in order and the request asks for a stream`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"lo \"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}],\"usage\":{\"total_tokens\":7}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(body)
                .build()
        )

        val deltas = withTimeout(10_000) {
            provider.streamComplete(newRequest()).toList()
        }

        // Content deltas only — the role-only first chunk and [DONE] produce
        // nothing, exactly like the real OpenAI wire format.
        assertEquals(listOf("Hel", "lo ", "world"), deltas)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer $apiKey", recorded.headers["Authorization"])
        val sentBody = recorded.body?.utf8().orEmpty()
        assertTrue("the request must ask for a stream", sentBody.contains("\"stream\":true"))
        assertFalse(
            "the request must not replay a finished completion",
            sentBody.contains("\"stream\":false")
        )
    }

    @Test
    fun `a mid-stream malformed json chunk is skipped, not fatal`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"part-1\"}}]}\n\n")
            append("data: not-json-at-all\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"-part-2\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(body)
                .build()
        )

        val deltas = withTimeout(10_000) {
            provider.streamComplete(newRequest()).toList()
        }
        assertEquals(listOf("part-1", "-part-2"), deltas)
    }

    @Test
    fun `an http failure surfaces the sanitized classification, never the key`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/json")
                .body(
                    "{\"error\":{\"type\":\"invalid_request_error\"," +
                        "\"message\":\"Incorrect API key provided: ${apiKey.take(8)}...\"}}"
                )
                .build()
        )

        val failure = runCatching {
            withTimeout(10_000) { provider.streamComplete(newRequest()).toList() }
        }.exceptionOrNull()

        assertTrue("a 401 must fail the stream", failure is LLMException)
        val llm = failure as LLMException
        assertEquals(LLMError.AuthInvalid, llm.error)
        // The sanitized boundary: the key (or any fragment of it) must never
        // reach the exception's user-facing message or redacted detail.
        val keyFragment = apiKey.take(8)
        assertFalse(llm.message.orEmpty().contains(keyFragment))
        assertFalse(llm.detail?.toString().orEmpty().contains(keyFragment))
    }

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", prompt, ChatMessage.Sender.USER)),
        model = "gpt-4o-mini",
        providerConfig = ProviderRequestConfig(
            apiKey = apiKey,
            endpoint = server.url("/v1").toString()
        )
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("tsf-sse-test")
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
