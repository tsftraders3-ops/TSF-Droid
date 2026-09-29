package com.tsfdroid.ai.core.harness

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0: the 75%-rule context compactor. Uses a fake provider so the
 * summarize call is deterministic; a tiny fake context window triggers the
 * compaction path without any network.
 */
class ContextCompactorTest {

    private class FakeProvider(
        private val summaryText: String = "User is planning a birthday party for Luna on Saturday."
    ) : LLMProvider {
        override val name: String = "Fake"
        override val availableModels: List<String> = emptyList()
        var lastRequest: LLMRequest? = null
            private set

        override suspend fun complete(request: LLMRequest): LLMResponse {
            lastRequest = request
            return LLMResponse(summaryText, 10, "fake", name, 1)
        }

        override fun streamComplete(request: LLMRequest): Flow<String> =
            flow { emit(complete(request).content) }

        override suspend fun isAvailable(): Boolean = true
    }

    private fun messages(count: Int, filler: Int = 900): List<ChatMessage> =
        (1..count).map { i ->
            ChatMessage(
                id = "m$i",
                text = if (i % 2 == 1) "user message $i: ${"x".repeat(filler)}" else "agent message $i: ${"y".repeat(filler)}",
                sender = if (i % 2 == 1) ChatMessage.Sender.USER else ChatMessage.Sender.AGENT
            )
        }

    private val smallWindow = 4000 // ~16k chars; filler above crosses 75% fast

    @Test
    fun `compacts when the prompt crosses 75 percent of the window`() = runBlocking {
        val provider = FakeProvider()
        val compactor = ContextCompactor(provider)
        val history = messages(count = 14)

        val result = compactor.compactIfNeeded("system prompt", history, smallWindow)

        assertTrue(result.compacted)
        assertEquals(
            "the summary from the provider becomes the compaction note",
            "User is planning a birthday party for Luna on Saturday.",
            result.summary
        )
        // Recent window kept verbatim.
        assertEquals(ContextCompactor.KEEP_RECENT, result.messages.size - 1)
        assertEquals(
            "the newest message survives compaction",
            history.last().id,
            result.messages.last().id
        )
        assertTrue(
            "compaction must shrink the prompt",
            result.tokensAfter < result.tokensBefore
        )
        assertTrue(result.messages.first().text.contains("[conversation so far"))
    }

    @Test
    fun `no compaction under the threshold`() = runBlocking {
        val provider = FakeProvider()
        val compactor = ContextCompactor(provider)
        val history = messages(count = 4, filler = 40)

        val result = compactor.compactIfNeeded("system prompt", history, smallWindow)

        assertFalse(result.compacted)
        assertEquals(history, result.messages)
        assertEquals(null, provider.lastRequest)
    }

    @Test
    fun `no compaction when context window is unknown`() = runBlocking {
        val provider = FakeProvider()
        val compactor = ContextCompactor(provider)
        val history = messages(count = 14)

        val result = compactor.compactIfNeeded("system prompt", history, null)

        assertFalse(result.compacted)
        assertEquals(history, result.messages)
    }

    @Test
    fun `no compaction when history barely exceeds the kept window`() = runBlocking {
        val provider = FakeProvider()
        val compactor = ContextCompactor(provider)
        // KEEP_RECENT + 2 = 10 messages: the "nothing meaningful to compact" guard.
        val history = messages(count = 10)

        val result = compactor.compactIfNeeded("system prompt", history, smallWindow)

        assertFalse(result.compacted)
        assertEquals(history, result.messages)
    }

    @Test
    fun `summary failure degrades to the original history`() = runBlocking {
        val history = messages(count = 14)

        // Force the summarize path to fail by using a provider that throws.
        val failing = object : LLMProvider {
            override val name = "Failing"
            override val availableModels = emptyList<String>()
            override suspend fun complete(request: LLMRequest): LLMResponse =
                throw IllegalStateException("boom")
            override fun streamComplete(request: LLMRequest): Flow<String> =
                flow { throw IllegalStateException("boom") }
            override suspend fun isAvailable() = true
        }

        val result = ContextCompactor(failing).compactIfNeeded("system prompt", history, smallWindow)
        assertFalse(result.compacted)
        assertEquals(history, result.messages)
    }
}
