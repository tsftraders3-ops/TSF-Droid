package com.tsfdroid.ai.e2e

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * B-02/M-08 (audit fc9ea97): the deterministic, LOCAL provider for hermetic
 * E2E. The suite used to depend on the live OpenCode Zen endpoint for every
 * agent-path test, so quota exhaustion (the 429 cascade) or endpoint latency
 * could burn the whole 90-minute instrumentation budget — exit 124, no green
 * run, and no way to tell a regression from a rate limit.
 *
 * This provider answers from a script: canned content, canned token counts,
 * optional per-call latency (to force the circuit breaker), and optional
 * failure. No network, no quota, deterministic JSON.
 */
class FakeLLMProvider(
    private val cannedContent: String = "{}",
    private val cannedTokens: Int = 100,
    private val latencyMs: Long = 0,
    private val failWith: Exception? = null
) : LLMProvider {

    override val name: String = "FakeLLMProvider"
    override val availableModels: List<String> = listOf("fake-hermetic")

    override suspend fun complete(request: LLMRequest): LLMResponse {
        failWith?.let { throw it }
        if (latencyMs > 0) delay(latencyMs)
        return LLMResponse(
            content = cannedContent,
            tokensUsed = cannedTokens,
            model = "fake-hermetic",
            provider = name,
            latencyMs = latencyMs
        )
    }

    override fun streamComplete(request: LLMRequest): Flow<String> =
        if (latencyMs > 0) flow { delay(latencyMs); emit(cannedContent) } else flowOf(cannedContent)

    override suspend fun isAvailable(): Boolean = true

    companion object {
        /** A high-confidence, in-range locator answer. */
        const val LOCATOR_HIGH_CONFIDENCE =
            """{"target_found": true, "x": 0.5, "y": 0.5, "confidence": 0.93}"""

        /** An in-range but under-the-floor locator answer. */
        const val LOCATOR_LOW_CONFIDENCE =
            """{"target_found": true, "x": 0.5, "y": 0.5, "confidence": 0.61}"""

        /** A research specialist brief. */
        const val RESEARCH_BRIEF = "1. fact one. 2. fact two. 3. fact three."
    }
}
