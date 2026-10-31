package com.tsfdroid.ai.core.storage

/**
 * Milestone 1 (EGM v2.0.0 arc) — shared test factory for constructing
 * engine collaborators that must never be resolved in file-action-only
 * tests (spec File 17). Mirrors the neverFactory pattern of
 * VisionDrivingLoopTest / VisionTargetTapTest.
 */
object TestFactories {

    fun neverProviderFactory(): com.tsfdroid.ai.core.llm.LLMProviderFactory {
        fun <T : Any> unused(): javax.inject.Provider<T> =
            javax.inject.Provider { throw AssertionError("test provider must never be resolved") }
        fun <T : Any> unusedLazy(): dagger.Lazy<T> =
            dagger.Lazy { throw AssertionError("test lazy must never be resolved") }
        val unusedSettings = com.tsfdroid.ai.data.repository.SettingsRepository(
            dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = kotlinx.coroutines.flow.flowOf(
                    androidx.datastore.preferences.core.emptyPreferences()
                )
                override suspend fun updateData(
                    transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
                ) = throw UnsupportedOperationException()
            },
            providerCredentialStore = com.tsfdroid.ai.core.agent.UnusedCredentialStore,
            runStartupMigration = false
        )
        return com.tsfdroid.ai.core.llm.LLMProviderFactory(
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
            settingsRepository = unusedSettings,
            onDeviceLatencyTracker = com.tsfdroid.ai.core.llm.OnDeviceLatencyTracker(unusedSettings),
            actionDispatcher = unusedLazy(),
            intentClassifier = unusedLazy(),
            deviceStateProvider = com.tsfdroid.ai.core.agent.DeviceStateProvider(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            )
        )
    }
}
