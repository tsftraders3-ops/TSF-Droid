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
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the Zen wire contract against a real socket: the
 * provenance headers the official OpenCode client sends, the anonymous
 * "public" credential, and the FreeTierError mapping users saw in v1.0.1.
 */
class OpenCodeZenNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val provider = OpenCodeZenProvider(
        OkHttpClient(),
        newSettingsRepository(),
        ModelsDevRegistry(OkHttpClient())
    ).apply { endpoint = server.url("/v1").toString() }

    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `requests carry the official client provenance and anonymous credential`() = runBlocking {
        server.enqueue(successBody())

        provider.complete(newRequest())

        val recorded = server.takeRequest()
        assertEquals("Bearer public", recorded.headers["Authorization"])
        assertEquals("cli", recorded.headers["x-opencode-client"])
        assertEquals(ZenIdentity.UA, recorded.headers["User-Agent"])

        val project = recorded.headers["x-opencode-project"]
        assertNotNull(project)
        assertEquals(26, project!!.length)
        assertTrue(recorded.headers["x-opencode-session"]!!.startsWith("ses_"))
        assertTrue(recorded.headers["x-opencode-request"]!!.startsWith("msg_"))
    }

    @Test
    fun `a 403 FreeTierError maps to the actionable free-tier error, not a bad-key error`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode"}}""")
                .build()
        )

        val thrown = runCatching { provider.complete(newRequest()) }
            .exceptionOrNull()

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
    fun `the model picker never empties when discovery fails`() = runBlocking {
        // /models fails; the static chain must bridge the outage.
        server.enqueue(MockResponse.Builder().code(503).body("down").build())

        val models = provider.listPickerModels()

        assertEquals(OpenCodeZenProvider.DEFAULT_MODEL_CHAIN, models.map { it.id })
    }

    private fun successBody() = MockResponse.Builder()
        .code(200)
        .body(
            """{"id":"1","object":"chat.completion","created":1,"model":"x-preview-f-free",
               "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
               "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".trimIndent()
        )
        .build()

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", prompt, ChatMessage.Sender.USER)),
        model = "x-preview-f-free"
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
    }
}
