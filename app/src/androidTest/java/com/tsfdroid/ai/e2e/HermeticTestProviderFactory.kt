package com.tsfdroid.ai.e2e

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.security.CredentialStoreResult
import com.tsfdroid.ai.core.security.ProviderCredentialId
import com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The factory used by hermetic E2E tests: every provider slot throws if it is
 * ever resolved, because the tests intercept all provider access through the
 * engine seams ([SubAgentRouter.providerAccess], [VisionEngine.providerAccess])
 * and hand out [FakeLLMProvider] instead. A resolved slot means a test lost
 * control of its LLM path — it fails loudly rather than touching a network.
 */
object HermeticTestProviderFactory {

    fun create(context: android.content.Context): LLMProviderFactory {
        fun <T : Any> unused(): javax.inject.Provider<T> =
            javax.inject.Provider { throw AssertionError("hermetic test resolved a real provider slot") }
        fun <T : Any> unusedLazy(): dagger.Lazy<T> =
            dagger.Lazy { throw AssertionError("hermetic test resolved a real lazy slot") }

        val settings = SettingsRepository(
            dataStore = NoopDataStore,
            providerCredentialStore = UnusedCredentialStore,
            runStartupMigration = false
        )
        return LLMProviderFactory(
            claudeProvider = unused(),
            openAIProvider = unused(),
            geminiProvider = unused(),
            mistralProvider = unused(),
            groqProvider = unused(),
            ollamaProvider = unused(),
            openRouterProvider = unused(),
            togetherAIProvider = unused(),
            cohereProvider = unused(),
            deepSeekProvider = unused(),
            openCodeZenProvider = unused(),
            copilotProvider = unused(),
            customOpenAIProvider = unused(),
            gemmaProvider = unused(),
            liteRTLMProvider = unused(),
            hybridOnDeviceProvider = unused(),
            settingsRepository = settings,
            onDeviceLatencyTracker = com.tsfdroid.ai.core.llm.OnDeviceLatencyTracker(settings),
            actionDispatcher = unusedLazy(),
            intentClassifier = unusedLazy(),
            deviceStateProvider = com.tsfdroid.ai.core.agent.DeviceStateProvider(context)
        )
    }

    private object NoopDataStore : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = throw UnsupportedOperationException("hermetic tests never write settings")
    }

    private object UnusedCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)
        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.StorageUnavailable
        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.StorageUnavailable
        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.StorageUnavailable
        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.StorageUnavailable
        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.StorageUnavailable
        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.StorageUnavailable
    }
}
