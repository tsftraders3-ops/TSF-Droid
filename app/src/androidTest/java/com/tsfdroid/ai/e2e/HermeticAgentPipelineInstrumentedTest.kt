package com.tsfdroid.ai.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.agent.ActionSequenceExecutor
import com.tsfdroid.ai.core.agent.SubAgentBudget
import com.tsfdroid.ai.core.agent.SubAgentResult
import com.tsfdroid.ai.core.agent.SubAgentRouter
import com.tsfdroid.ai.core.agent.VisionEngine
import com.tsfdroid.ai.data.models.PlanStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-02/M-08 (audit fc9ea97): the HERMETIC E2E gate for the agent pipeline.
 *
 * These tests exercise the audit-remediation capabilities ON A DEVICE with
 * [FakeLLMProvider] — deterministic, local, quota-free:
 *   - the sub-agent circuit breaker (forced timeout kills the specialist and
 *     the CEO-safe call degrades to null instead of crashing),
 *   - the vision-grounded tap's confidence gate (low confidence never
 *     dispatches a gesture),
 *   - the background consent boundary (a macro containing SEND_SMS is refused
 *     before dispatch).
 *
 * They run in seconds and cannot be starved or poisoned by the live endpoint,
 * so the E2E job always has a meaningful green/failing signal that is not a
 * rate limit. The live OpenCode Zen contract remains covered separately by
 * [ZenFreeTierInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class HermeticAgentPipelineInstrumentedTest {

    private val appContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30_000)
    fun subAgentCircuitBreaker_killsTimeoutAndCEOProceeds() = runBlocking {
        val router = SubAgentRouter(hermeticFactory())
        router.budget = SubAgentBudget(timeoutMs = 1_000, maxTokens = 4_000)
        router.providerAccess = { FakeLLMProvider(cannedContent = "x", latencyMs = 60_000) }

        val started = System.currentTimeMillis()
        val result = router.research("anything at all")
        val elapsed = System.currentTimeMillis() - started

        assertTrue("expected TimedOut but was $result", result is SubAgentResult.TimedOut)
        assertTrue("breaker must return near its 1s budget, took ${elapsed}ms", elapsed < 15_000)
        // The CEO-safe surface: a killed specialist is a missing brief, never
        // a crash — the CEO proceeds without it.
        assertNull(router.researchSafely("anything at all"))
    }

    @Test(timeout = 30_000)
    fun subAgentTokenBudget_overLimitIsDiscarded() = runBlocking {
        val router = SubAgentRouter(hermeticFactory())
        router.providerAccess = { FakeLLMProvider(cannedContent = "findings", cannedTokens = 9_000) }

        val result = router.research("greedy research")

        assertTrue("expected OverBudget but was $result", result is SubAgentResult.OverBudget)
        assertNull("CEO degrades to null", router.researchSafely("greedy research"))
    }

    @Test(timeout = 30_000)
    fun visionTapGate_lowConfidenceNeverDispatches() = runBlocking {
        val engine = VisionEngine(hermeticFactory())
        engine.providerAccess = { FakeLLMProvider(cannedContent = FakeLLMProvider.LOCATOR_LOW_CONFIDENCE) }
        // A capture that cannot decode is NOT a black frame — the vision path
        // proceeds to the model, which is exactly what we want to verify here.
        engine.screenCapturer = { "not-a-real-bitmap-but-not-black" }
        engine.screenDimensions = { 1080 to 1920 }
        var tapDispatched = false
        engine.tapExecutor = { _, _ ->
            tapDispatched = true
            true
        }

        val outcome = engine.tapLocatedTarget("the Send button")

        assertFalse("low confidence must never tap", outcome.tapped)
        assertFalse("no gesture may be dispatched", tapDispatched)
    }

    @Test(timeout = 30_000)
    fun consentBoundary_backgroundMacroWithSendSmsIsRefused() = runBlocking {
        val dispatched = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                dispatched += action
                ActionResult.Success(mapOf("message" to "$action ok"))
            },
            hasAction = { true }
        )

        val result = executor.execute(
            steps = listOf(
                PlanStep(stepId = "s1", order = 1, description = "open", action = "OPEN_APP"),
                PlanStep(stepId = "s2", order = 2, description = "sms", action = "SEND_SMS")
            ),
            context = appContext
        )

        assertFalse("SEND_SMS macro must be refused", result.success)
        assertEquals("only the safe step may dispatch", listOf("OPEN_APP"), dispatched)
        assertTrue(
            "the refusal must name interactive confirmation",
            result.error!!.contains("interactive confirmation")
        )
    }

    @Test(timeout = 30_000)
    fun subAgentSuccess_hermeticResearchFlowsThroughTheRouter() = runBlocking {
        val router = SubAgentRouter(hermeticFactory())
        router.providerAccess = { FakeLLMProvider(cannedContent = FakeLLMProvider.RESEARCH_BRIEF) }

        val result = router.research("gather the context")

        assertTrue("expected Success but was $result", result is SubAgentResult.Success)
        assertEquals(FakeLLMProvider.RESEARCH_BRIEF, (result as SubAgentResult.Success).output)
    }

    private fun hermeticFactory(): com.tsfdroid.ai.core.llm.LLMProviderFactory =
        HermeticTestProviderFactory.create(appContext)
}
