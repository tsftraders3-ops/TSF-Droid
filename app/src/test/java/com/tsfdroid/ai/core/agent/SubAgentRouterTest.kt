package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * M-01 remediation (audit fc9ea97): the CEO must be able to delegate to
 * bounded specialist sub-agents and survive their failures.
 *
 * The contract under test:
 *  - delegation runs a specialist with a hard 60s deadline and a 4k token
 *    budget (the research specialist's context cap);
 *  - a sub-agent that exceeds the deadline is KILLED (its coroutine is
 *    cancelled, observable via the provider's cancellation flag) and the
 *    router reports TimedOut — never an exception;
 *  - a response over the token budget is discarded (OverBudget);
 *  - any failure degrades to researchSafely()/draftSafely() returning null,
 *    so the CEO (AgentLoop) proceeds without the sub-agent's work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubAgentRouterTest {

    private lateinit var router: SubAgentRouter
    private val llmCalls = AtomicInteger(0)
    private val lastRequest = AtomicReference<LLMRequest?> (null)
    private val providerCancelled = AtomicBoolean(false)

    /** Test-controllable provider behavior. */
    private var responseDelayMs: Long = 0
    private var responseContent: String = "research findings: tsf droid is an android agent"
    private var responseTokens: Int = 120

    @Before
    fun setUp() {
        router = SubAgentRouter(neverFactory())
        router.budget = SubAgentBudget(timeoutMs = 500, maxTokens = 4_000)
        router.providerAccess = {
            llmCalls.incrementAndGet()
            ControllableProvider()
        }
    }

    private inner class ControllableProvider : LLMProvider {
        override val name: String = "ControllableProvider"
        override val availableModels: List<String> = listOf("fake")

        override suspend fun complete(request: LLMRequest): LLMResponse {
            lastRequest.set(request)
            try {
                delay(responseDelayMs)
                return LLMResponse(
                    content = responseContent,
                    tokensUsed = responseTokens,
                    model = "fake",
                    provider = name,
                    latencyMs = 5
                )
            } finally {
                // Observable proof the circuit breaker KILLED the coroutine:
                // this runs when the delay is cancelled.
                if (responseDelayMs > 0) providerCancelled.set(true)
            }
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flowOf(responseContent)
        override suspend fun isAvailable(): Boolean = true
    }

    @Test
    fun `research delegation returns the specialist output`() = runBlocking {
        val result = router.research("What affects the gold price today?")

        assertTrue("expected Success but was $result", result is SubAgentResult.Success)
        assertEquals("research findings: tsf droid is an android agent", (result as SubAgentResult.Success).output)
        assertEquals(1, llmCalls.get())
    }

    @Test
    fun `research agent carries the researcher prompt and the 4k token budget`() = runBlocking {
        router.research("What affects the gold price today?")

        val request = lastRequest.get()
        assertNotNull("provider was consulted", request)
        assertTrue(
            "research specialist prompt expected",
            request!!.systemPrompt.contains("research", ignoreCase = true)
        )
        assertEquals("4k token budget rides on the request", 4_000, request.maxTokens)
    }

    @Test
    fun `a sub-agent that exceeds its deadline is killed and reported, not thrown`() = runBlocking {
        responseDelayMs = 10_000
        router.budget = SubAgentBudget(timeoutMs = 200, maxTokens = 4_000)
        val started = System.currentTimeMillis()

        val result = router.research("slow research")

        val elapsed = System.currentTimeMillis() - started
        assertTrue("expected TimedOut but was $result", result is SubAgentResult.TimedOut)
        assertTrue("router must return promptly after the kill, took ${elapsed}ms", elapsed < 5_000)
        assertTrue("the sub-agent coroutine must actually be cancelled", providerCancelled.get())
        assertEquals("CEO-facing safe call maps timeout to null", null, router.researchSafely("slow research"))
    }

    @Test
    fun `a response over the token budget is discarded`() = runBlocking {
        responseTokens = 9_500

        val result = router.research("greedy research")

        assertTrue("expected OverBudget but was $result", result is SubAgentResult.OverBudget)
        val over = result as SubAgentResult.OverBudget
        assertEquals(9_500, over.tokensUsed)
        assertEquals(4_000, over.tokenBudget)
    }

    @Test
    fun `provider failure is reported as Failed and the CEO-safe call returns null`() = runBlocking {
        router.providerAccess = {
            llmCalls.incrementAndGet()
            ThrowingProvider()
        }

        val result = router.research("doomed research")

        assertTrue("expected Failed but was $result", result is SubAgentResult.Failed)
        assertNull("CEO-safe call degrades to null", router.researchSafely("doomed research"))
    }

    @Test
    fun `executor delegation drafts a steps fragment under the consent boundary`() = runBlocking {
        responseContent = """{"steps":[{"action":"WEB_SEARCH","description":"look up gold price"}]}"""

        val result = router.draftExecution("check the gold price", "research: gold reacts to rates")

        assertTrue("expected Success but was $result", result is SubAgentResult.Success)
        val request = lastRequest.get()
        assertNotNull(request)
        assertTrue(
            "executor prompt must forbid critical actions (consent boundary)",
            request!!.systemPrompt.contains("critical", ignoreCase = true)
        )
    }

    @Test
    fun `the default budget is the 60 second circuit breaker`() {
        assertEquals(60_000L, SubAgentBudget().timeoutMs)
        assertEquals(4_000, SubAgentBudget().maxTokens)
    }

    @Test
    fun `CEO-safe executor call degrades to null on timeout`() = runBlocking {
        responseDelayMs = 10_000
        router.budget = SubAgentBudget(timeoutMs = 200, maxTokens = 4_000)

        assertNull(router.draftSafely("check the gold price", "research"))
    }

    @Test
    fun `unreadable or empty specialist output is never a Success`() = runBlocking {
        responseContent = "   "

        val result = router.research("blank research")

        assertTrue("blank output must not be a Success", result !is SubAgentResult.Success)
        assertNull(router.researchSafely("blank research"))
    }

    private class ThrowingProvider : LLMProvider {
        override val name: String = "ThrowingProvider"
        override val availableModels: List<String> = emptyList()
        override suspend fun complete(request: LLMRequest): LLMResponse =
            throw IllegalStateException("provider exploded")
        override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("")
        override suspend fun isAvailable(): Boolean = false
    }

    private fun neverFactory(): LLMProviderFactory {
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
