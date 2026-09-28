package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.LLMStreamEvent
import com.tsfdroid.ai.core.llm.LLMToolCall
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.Tool
import com.tsfdroid.ai.core.llm.providers.ModelsDevRegistry
import com.tsfdroid.ai.data.models.ChatMessage
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.2.0 harness semantics: the model is called REPEATEDLY until the turn is
 * done — tool rounds feed real results back, finish_reason "length" triggers
 * automatic continuation, the doom guard stops identical tool loops, and the
 * Chat-mode gate refuses every mutating action at execution level.
 *
 * Runs under Robolectric: the models.dev registry parser uses android's
 * org.json (stubs on the plain JVM android.jar) and TurnConfig needs a real
 * Context shape.
 */
@RunWith(RobolectricTestRunner::class)
class HarnessLoopTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val registry = ModelsDevRegistry(OkHttpClient()).apply {
        registryUrl = server.url("/registry").toString()
    }
    private val harness = HarnessLoop(FakeExecutor(), registry)

    private val context: android.content.Context by lazy {
        RuntimeEnvironment.getApplication()
    }

    private class FakeExecutor : HarnessToolExecutor {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun execute(
            actionName: String,
            params: Map<String, String>,
            context: android.content.Context
        ): ActionResult {
            calls.add(actionName to params)
            return ActionResult.Success(mapOf("message" to "result of $actionName"))
        }
    }

    /** Scripted provider: pops the next queued response per complete() call. */
    private class FakeProvider(vararg script: LLMResponse) : LLMProvider {
        val responses = script.toMutableList()
        val requests = mutableListOf<LLMRequest>()

        override val name = "fake"
        override val availableModels = listOf("fake-model")

        override suspend fun complete(request: LLMRequest): LLMResponse {
            requests.add(request)
            return responses.removeFirstOrNull() ?: error("script exhausted")
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flow {
            emit(complete(request).content)
        }

        override fun streamCompleteDetailed(request: LLMRequest): Flow<LLMStreamEvent> = flow {
            val response = complete(request)
            if (response.content.isNotEmpty()) emit(LLMStreamEvent.Content(response.content))
            response.finishReason?.let { emit(LLMStreamEvent.Finished(it)) }
        }

        override suspend fun isAvailable() = true
    }

    @Before
    fun primeRegistryFailure() {
        // Registry down -> empty specs (hasVisionSupport false, no network).
        server.enqueue(
            MockResponse.Builder().code(503).body("registry down").build()
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(
        content: String,
        finishReason: String? = "stop",
        toolCalls: List<LLMToolCall> = emptyList()
    ) = LLMResponse(
        content = content,
        tokensUsed = 10,
        model = "fake-model",
        provider = "fake",
        latencyMs = 1,
        toolCalls = toolCalls,
        finishReason = finishReason
    )

    private fun config(
        readOnly: Boolean = false,
        tool: Tool? = null
    ) = HarnessLoop.TurnConfig(
        systemPrompt = "test prompt",
        history = listOf(ChatMessage("1", "user ask", ChatMessage.Sender.USER)),
        tools = listOfNotNull(tool),
        context = context,
        readOnly = readOnly
    )

    @Test
    fun `tool round executes calls, feeds results back, and lands the final answer`() = runBlocking {
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(LLMToolCall("web_search", """{"query":"tsf droid"}"""))
            ),
            answer("Here is the synthesized answer with facts.")
        )

        val result = harness.runTurn(
            provider,
            config(
                tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")
            )
        )

        assertEquals("Here is the synthesized answer with facts.", result!!.content)
        assertEquals(1, result.toolCallsExecuted)
        assertEquals(2, provider.requests.size)
        // The second call's history carries the executed tool result.
        val secondCallMessages = provider.requests[1].messages
        assertTrue(
            secondCallMessages.any {
                it.text.contains("TOOL RESULT web_search") && it.text.contains("status: OK")
            }
        )
    }

    @Test
    fun `finish_reason length triggers automatic continuation and joins segments`() = runBlocking {
        val provider = FakeProvider(
            answer("The answer starts with facts.", finishReason = "length"),
            answer("Then it concludes cleanly.", finishReason = "stop")
        )

        val result = harness.runTurn(provider, config())

        // Clean stop (terminal punctuation) joins on a newline.
        assertEquals(
            "The answer starts with facts.\nThen it concludes cleanly.",
            result!!.content
        )
        assertEquals(1, result.continuationSegments)
        assertTrue(!result.stillTruncated)
        // The continuation request contains the partial + CONTINUE instruction.
        val continuationMessages = provider.requests[1].messages
        assertTrue(continuationMessages.any { it.text.startsWith("CONTINUE") })
        assertTrue(continuationMessages.any { it.text == "The answer starts with facts." })
    }

    @Test
    fun `continuation respects the segment budget and reports residual truncation`() = runBlocking {
        val provider = FakeProvider(
            answer("part0", finishReason = "length"),
            answer("part1", finishReason = "length"),
            answer("part2", finishReason = "length"),
            answer("part3", finishReason = "length") // beyond the budget: not fetched
        )

        val result = harness.runTurn(provider, config())

        // MAX_CONTINUATIONS = 3: part0 + 3 appended segments, still flagged.
        assertEquals(3, result!!.continuationSegments)
        assertTrue(result.stillTruncated)
        assertEquals(4, provider.requests.size) // initial + 3 continuation calls
    }

    @Test
    fun `read-only gate refuses write_file before execution and the loop recovers`() = runBlocking {
        val executor = FakeExecutor()
        val readOnlyHarness = HarnessLoop(executor, registry)
        val provider = FakeProvider(
            answer("", toolCalls = listOf(LLMToolCall("write_file", """{"path":"a.txt","content":"hi"}"""))),
            answer("I cannot write files in Chat mode.")
        )

        val result = readOnlyHarness.runTurn(provider, config(readOnly = true))

        assertEquals("I cannot write files in Chat mode.", result!!.content)
        // THE gate: execution never happened.
        assertTrue(executor.calls.isEmpty())
        assertTrue(
            provider.requests[1].messages.any { it.text.contains("REFUSED") }
        )
    }

    @Test
    fun `agent mode executes the same write call`() = runBlocking {
        val executor = FakeExecutor()
        val agentHarness = HarnessLoop(executor, registry)
        val provider = FakeProvider(
            answer("", toolCalls = listOf(LLMToolCall("write_file", """{"path":"a.txt","content":"hi"}"""))),
            answer("File written.")
        )

        val result = agentHarness.runTurn(provider, config(readOnly = false))

        assertEquals("File written.", result!!.content)
        assertEquals(listOf("WRITE_FILE"), executor.calls.map { it.first })
    }

    @Test
    fun `doom guard stops the loop after repeated identical tool calls`() = runBlocking {
        val executor = FakeExecutor()
        val doomHarness = HarnessLoop(executor, registry)
        // The model keeps issuing the SAME web_search call forever.
        val provider = FakeProvider(
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"same"}"""))),
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"same"}"""))),
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"same"}"""))),
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"same"}"""))),
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"same"}""")))
        )

        val result = doomHarness.runTurn(provider, config(readOnly = false))

        assertNull(result)
        // Executes at most: warn round + one final identical round = 4 calls.
        assertTrue(executor.calls.size <= 4)
        // The doom warning was injected into the conversation.
        assertTrue(provider.requests.any { req -> req.messages.any { it.text.contains("identical tool call") } })
    }

    @Test
    fun `joinSegments glues mid-sentence cuts and separates clean stops`() {
        // Mid-sentence: previous ends with a letter, next starts lowercase.
        assertEquals(
            "The theory of everythin" + "g explains it",
            HarnessLoop.joinSegments("The theory of everythin", "g explains it")
        )
        // Clean stop: previous ends with terminal punctuation.
        assertEquals(
            "First paragraph done.\nSecond paragraph.",
            HarnessLoop.joinSegments("First paragraph done.", "Second paragraph.")
        )
        // Leading newlines in the continuation are dropped when gluing.
        assertEquals(
            "mid sentence continues",
            HarnessLoop.joinSegments("mid sentence con", "\ntinues")
        )
        // Empty edges.
        assertEquals("abc", HarnessLoop.joinSegments("", "abc"))
        assertEquals("abc", HarnessLoop.joinSegments("abc", ""))
    }

    @Test
    fun `streamFirstRound classifies complete tool and continuation outcomes`() = runBlocking {
        val completeProvider = FakeProvider(answer("all done"))
        val complete = harness.streamFirstRound(completeProvider, config(), null, null)
        assertEquals(HarnessLoop.FirstRoundStatus.COMPLETE, complete.status)

        val toolsProvider = FakeProvider(answer("", toolCalls = listOf(LLMToolCall("read", """{"path":"x"}"""))))
        val toolHandoff = harness.streamFirstRound(toolsProvider, config(), null, null)
        assertEquals(HarnessLoop.FirstRoundStatus.TOOL_HANDOFF, toolHandoff.status)

        val lengthProvider = FakeProvider(answer("truncated", finishReason = "length"))
        val continueHandoff = harness.streamFirstRound(lengthProvider, config(), null, null)
        assertEquals(HarnessLoop.FirstRoundStatus.CONTINUE_HANDOFF, continueHandoff.status)
        assertEquals("length", continueHandoff.finishReason)
    }
}
