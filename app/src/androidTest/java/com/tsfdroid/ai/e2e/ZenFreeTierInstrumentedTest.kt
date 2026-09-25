package com.tsfdroid.ai.e2e

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.providers.ModelsDevRegistry
import com.tsfdroid.ai.core.llm.providers.OpenCodeZenProvider
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.repository.SettingsRepository
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * THE end-to-end proof for the v1.0.2 keyless fix: a real chat completion
 * against https://opencode.ai/zen/v1 through the app's own transport —
 * [OpenCodeZenProvider], [com.tsfdroid.ai.core.llm.providers.ZenIdentity]
 * provenance headers, and Android's TLS stack — running on an emulator.
 *
 * This is the verification no unit test can substitute: the free-tier gate
 * rejected v1.0.1's fingerprint at the endpoint, so only a device-originated
 * request through the real stack proves the contract. Requires network.
 */
@RunWith(AndroidJUnit4::class)
class ZenFreeTierInstrumentedTest {

    private fun newProvider(): OpenCodeZenProvider {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File.createTempFile("e2e-zen-settings", ".preferences_pb") }
        )
        val settings = SettingsRepository(
            dataStore = dataStore,
            providerCredentialStore = EmptyCredentialStore(),
            runStartupMigration = false
        )
        return OpenCodeZenProvider(
            client = OkHttpClient(),
            settingsRepository = settings,
            registry = ModelsDevRegistry(OkHttpClient())
        )
    }

    private fun newRequest(model: String? = null) = LLMRequest(
        systemPrompt = "You are TSF Droid, a helpful Android assistant. Answer briefly.",
        messages = listOf(
            ChatMessage("e2e-1", "Reply with exactly: E2E_OK", ChatMessage.Sender.USER)
        ),
        model = model,
        maxTokens = 2000
    )

    @Test(timeout = 240_000)
    fun keylessZenChatCompletionSucceedsFromTheAndroidStack() = runBlocking {
        val provider = newProvider()

        // model=null: the full production path — hierarchy resolution, live
        // discovery, harness-tool body, SSE reassembly.
        val response = provider.complete(newRequest())

        assertEquals("OpenCode Zen", response.provider)
        assertTrue(
            "expected a verified free model, got ${response.model}",
            response.model.endsWith("-free")
        )
        assertTrue("expected non-empty answer, got blank", response.content.isNotBlank())
        // The instruction pins an exact token so a canned/malformed stream
        // cannot satisfy the assertion silently.
        assertTrue(
            "expected the pinned token in '${response.content.take(80)}'",
            response.content.uppercase().contains("E2E_OK")
        )
    }

    @Test(timeout = 240_000)
    fun liveModelPickerExposesFreeZenModelsWithRegistryMetadata() = runBlocking {
        val provider = newProvider()
        val models = provider.listPickerModels()

        assertTrue("picker must never be empty", models.isNotEmpty())
        assertTrue(
            "free models must lead the picker",
            models.first().isFree
        )
        // Registry metadata: verified free generalists publish context windows.
        models.first { it.id == "mimo-v2.6-flash-free" }.let { head ->
            assertNotEquals("context window must come from models.dev", null, head.contextWindow)
            assertTrue(head.contextWindow!! > 100_000)
        }
    }

    private class EmptyCredentialStore : ProviderCredentialStore {
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
