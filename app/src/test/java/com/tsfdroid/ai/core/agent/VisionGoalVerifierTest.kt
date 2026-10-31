package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Milestone 1 (EGM v2.0.0 arc) — the production goal verifier (spec File
 * 11, Checkpoint 2). Deterministic-first, vision-fallback-only-on-empty-tree,
 * never a guessed success.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionGoalVerifierTest {

    private lateinit var verifier: VisionGoalVerifier
    private val providerCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastRequest: LLMRequest? = null

    /** Hermetic scripted provider: returns [json], throws when asked to. */
    private inner class ScriptedProvider(
        private val json: String? = null,
        private val throwOnCall: Boolean = false
    ) : LLMProvider {
        override val name: String = "ScriptedVerifierProvider"
        override val availableModels: List<String> = listOf("fake-verifier")
        override suspend fun complete(request: LLMRequest): LLMResponse {
            providerCalls.incrementAndGet()
            lastRequest = request
            if (throwOnCall) throw RuntimeException("provider down")
            return LLMResponse(
                content = json ?: "",
                tokensUsed = 5,
                model = "fake-verifier",
                provider = name,
                latencyMs = 1
            )
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("{}")
        override suspend fun isAvailable(): Boolean = true
    }

    @Before
    fun setUp() {
        verifier = VisionGoalVerifier(
            visionEngine = VisionEngine(neverFactory()),
            llmProviderFactory = neverFactory()
        )
    }

    private fun fakeFactory(): com.tsfdroid.ai.core.llm.LLMProviderFactory = neverFactory()

    // ------------------------------------------------------------------
    // Deterministic tier: the accessibility text tree decides, no LLM
    // ------------------------------------------------------------------

    @Test
    fun `expectedText foundInTree isReachedTrue`() = runTest {
        verifier.screenTextProvider = { "Settings\nNetwork & internet\nAbout phone" }
        verifier.providerAccess = { ScriptedProvider(json = """{"goal_reached": true}""") }

        val reached = verifier.isReached(
            goal = "open the network settings",
            expectedScreenText = "Network & internet",
            expectedAbsentFromBaseline = true
        )

        assertTrue(reached)
        assertEquals("the deterministic tier never calls the provider", 0, providerCalls.get())
    }

    @Test
    fun `expectedText missingFromTree isReachedFalse`() = runTest {
        verifier.screenTextProvider = { "Settings\nBattery\nAbout phone" }

        val reached = verifier.isReached(
            goal = "open the network settings",
            expectedScreenText = "Network & internet",
            expectedAbsentFromBaseline = true
        )

        assertFalse(reached)
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun `expectedText caseInsensitiveMatch`() = runTest {
        verifier.screenTextProvider = { "settings\nnetwork & INTERNET\nabout phone" }

        val reached = verifier.isReached(
            goal = "open the network settings",
            expectedScreenText = "Network & Internet",
            expectedAbsentFromBaseline = true
        )

        assertTrue(reached)
    }

    @Test
    fun `expectedText baselineAbsentFlagFalse isReachedFalse`() = runTest {
        // The baseline guard: the text IS on screen now, but it was already
        // there before the drive — that is not evidence the goal was reached.
        verifier.screenTextProvider = { "Network & internet" }

        val reached = verifier.isReached(
            goal = "open the network settings",
            expectedScreenText = "Network & internet",
            expectedAbsentFromBaseline = false
        )

        assertFalse(reached)
    }

    // ------------------------------------------------------------------
    // Vision fallback: ONLY on an empty tree, strict contract
    // ------------------------------------------------------------------

    @Test
    fun `blankTree fallsBackToVisionCheck`() = runTest {
        verifier.screenTextProvider = { null }
        verifier.screenCapture = { ScreenCaptureResult(base64 = "aGk=", blindSpot = null) }
        verifier.windowContextProvider = { "com.android.settings" }

        verifier.providerAccess = { ScriptedProvider(json = """{"goal_reached": true}""") }
        val reachedTrue = verifier.isReached("open the network settings", "Network & internet", true)
        assertTrue(reachedTrue)

        providerCalls.set(0)
        verifier.providerAccess = { ScriptedProvider(json = """{"goal_reached": false}""") }
        val reachedFalse = verifier.isReached("open the network settings", "Network & internet", true)
        assertFalse(reachedFalse)

        // The judgment carried the captured frame and the window context.
        val request = lastRequest!!
        val image = request.messages.first().imageBase64
        assertEquals("aGk=", image)
        assertTrue(request.systemPrompt.contains("com.android.settings"))
    }

    @Test
    fun `visionCheck unparsableAnswer isFalse`() = runTest {
        verifier.screenTextProvider = { null }
        verifier.screenCapture = { ScreenCaptureResult(base64 = "aGk=", blindSpot = null) }
        verifier.providerAccess = { ScriptedProvider(json = "The goal seems to have been achieved.") }

        val reached = verifier.isReached("open the network settings", "Network & internet", true)

        assertFalse(reached)
    }

    @Test
    fun `visionCheck providerThrows isFalse`() = runTest {
        verifier.screenTextProvider = { null }
        verifier.screenCapture = { ScreenCaptureResult(base64 = "aGk=", blindSpot = null) }
        verifier.providerAccess = { ScriptedProvider(throwOnCall = true) }

        val reached = verifier.isReached("open the network settings", "Network & internet", true)

        assertFalse(reached)
    }

    @Test
    fun `visionCheck blackFrameCapture isFalse`() = runTest {
        // FLAG_SECURE: the capture yields a black frame → null base64 → the
        // verifier refuses to guess, and the provider is never consulted.
        verifier.screenTextProvider = { null }
        verifier.screenCapture = { ScreenCaptureResult(base64 = null, blindSpot = BlindSpot.SECURE_FLAG_BLACK_FRAME) }
        verifier.providerAccess = { ScriptedProvider(json = """{"goal_reached": true}""") }

        val reached = verifier.isReached("open the network settings", "Network & internet", true)

        assertFalse(reached)
        assertEquals("a null capture never reaches the provider", 0, providerCalls.get())
    }

    // ------------------------------------------------------------------
    // Baseline read-through (the action's pre-check surface)
    // ------------------------------------------------------------------

    @Test
    fun `currentScreenText readsThroughTheScreenTextSeam`() {
        verifier.screenTextProvider = { "Some screen content" }
        assertEquals("Some screen content", verifier.currentScreenText())

        verifier.screenTextProvider = { null }
        assertNull(verifier.currentScreenText())
    }

    // ------------------------------------------------------------------
    // test factory (never resolved: seam-driven tests)
    // ------------------------------------------------------------------

    private fun neverFactory(): com.tsfdroid.ai.core.llm.LLMProviderFactory {
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
            providerCredentialStore = UnusedCredentialStore,
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
            deviceStateProvider = DeviceStateProvider(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            )
        )
    }
}
