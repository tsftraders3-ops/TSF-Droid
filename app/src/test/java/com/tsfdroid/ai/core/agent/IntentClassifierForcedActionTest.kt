package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMProviderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6 loop-16: artifact and live-data asks MUST route to planning even
 * when the LLM intent router answers "CONVERSATIONAL". The loop-15 field
 * evidence: the website and gold-price asks degraded into chat promises
 * because only the LLM router judged them.
 *
 * These tests exercise the deterministic forced-action layer by calling
 * [IntentClassifier.requiresAction] against a provider factory the model
 * path can never reach (forced patterns return before the LLM call; the
 * queries below all match forced patterns, so no provider is constructed).
 */
class IntentClassifierForcedActionTest {

    private val neverFactory = object : dagger.Lazy<LLMProviderFactory> {
        override fun get(): LLMProviderFactory =
            throw AssertionError("forced-action patterns must not reach the LLM router")
    }

    private fun classifier(): IntentClassifier = IntentClassifier(neverFactory)

    private fun assertForcedAction(query: String) {
        assertTrue(
            "\"$query\" must force the planning path",
            runBlocking { classifier().requiresAction(query) }
        )
    }

    @Test
    fun `the user's exact website ask forces action`() {
        assertForcedAction("can u create a award winning website in html")
    }

    @Test
    fun `the user's exact gold price ask forces action`() {
        assertForcedAction("cna u fetch the price of gold now")
    }

    @Test
    fun `pdf report ask forces action`() {
        assertForcedAction("cna u create s pdf report of gold price")
    }

    @Test
    fun `search ask forces action`() {
        assertForcedAction("ok search for latest iphone price")
    }

    @Test
    fun `html artifact ask forces action`() {
        assertForcedAction("write an html page for my portfolio")
    }
}
