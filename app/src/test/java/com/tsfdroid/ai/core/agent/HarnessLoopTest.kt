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

    // ---------- v1.2.1 length contract: the bounded expansion pass ----------

    @Test
    fun `expandShortAnswer re-asks for the full requested length`() = runBlocking {
        // The FIRST response the fake pops IS the expansion response — the
        // caller already holds the lazy first-pass reply.
        val provider = FakeProvider(
            answer("A full multi-paragraph essay that actually covers every requested topic in the depth asked for.", finishReason = "stop")
        )

        val result = harness.expandShortAnswer(
            provider,
            config(),
            history = listOf(ChatMessage("1", "user ask", ChatMessage.Sender.USER)),
            userQuery = "Write a thorough essay of at least 600 words on packet switching.",
            currentReply = "Two lazy sentences only."
        )

        assertTrue(result!!.content.startsWith("A full multi-paragraph essay"))
        assertEquals(1, provider.requests.size)
        // The expansion request carries the previous reply as an assistant
        // turn plus the expansion instruction; tools are off for the pass.
        val expansionRequest = provider.requests[0]
        assertTrue(!expansionRequest.allowToolCalls)
        assertTrue(
            expansionRequest.messages.any {
                it.sender == ChatMessage.Sender.AGENT && it.text == "Two lazy sentences only."
            }
        )
        assertTrue(
            expansionRequest.messages.any {
                it.text.contains("Deliver the COMPLETE answer") && it.text.contains("600 words")
            }
        )
    }

    @Test
    fun `expandShortAnswer flows continuations when the expansion itself hits the budget`() = runBlocking {
        val provider = FakeProvider(
            // Ends on a period -> joinSegments glues with a newline.
            answer("The expanded first half.", finishReason = "length"),
            answer("and the flowing second half.", finishReason = "stop")
        )

        val result = harness.expandShortAnswer(
            provider,
            config(),
            history = listOf(ChatMessage("1", "user ask", ChatMessage.Sender.USER)),
            userQuery = "Write a detailed report with full paragraphs.",
            currentReply = "too short"
        )

        assertEquals(
            "The expanded first half.\nand the flowing second half.",
            result!!.content
        )
    }

    // ---------- v1.2.1 chat-path guarantees: fresh data + long-form asks ----------

    @Test
    fun `fresh-data detection matches current-info asks and not casual chat`() {
        assertTrue(requiresFreshData("What is the current price of Bitcoin in USD right now?"))
        assertTrue(requiresFreshData("ok search for latest iphone price"))
        assertTrue(requiresFreshData("what's the weather today"))
        assertTrue(!requiresFreshData("hi there"))
        assertTrue(!requiresFreshData("explain how the internet works"))
        assertTrue(!requiresFreshData("What is my cat's name? Answer with just the name."))
    }

    @Test
    fun `long-form detection matches explicit length asks and not short asks`() {
        assertTrue(asksForLongForm("Write a thorough essay of at least 600 words explaining how the internet works."))
        assertTrue(asksForLongForm("Explain in detail how DNS resolution works end to end"))
        assertTrue(!asksForLongForm("hi"))
        assertTrue(!asksForLongForm("What is my cat's name? Answer with just the name."))
        assertTrue(!asksForLongForm("Write a file at Documents/chatmode_proof.txt containing the text chat-mode-write."))
    }

    // ---------- v1.2.1 round-7: monologue guard + forced search ----------

    @Test
    fun `monologue detection catches announcement shapes and not real answers`() {
        // The exact cap8 field leak:
        assertTrue(isMonologueShaped("Need to search for CBSE class 10 board exam dates 2026. Let me search."))
        assertTrue(isMonologueShaped("Let me search for this."))
        assertTrue(isMonologueShaped("I'll look that up right away..."))
        assertTrue(isMonologueShaped("Searching for the current BTC price now"))
        // The exact cap7 round-8 field leak: the announcement sits MID-text:
        assertTrue(
            isMonologueShaped(
                "The searches came back empty, so let me fetch a live gold-rate page directly."
            )
        )
        // Real answers stay:
        assertTrue(!isMonologueShaped("Bitcoin is trading at $84,000 as of today."))
        assertTrue(!isMonologueShaped("Let me give you the key facts: gold is at $4,156/oz."))
        assertTrue(!isMonologueShaped("Let me know if you want more detail."))
        // A question back is conversation, not monologue:
        assertTrue(!isMonologueShaped("Let me check — do you want prices in USD or EUR?"))
        // Long-form answers are never monologue:
        assertTrue(!isMonologueShaped("Let me explain. " + "Detail. ".repeat(120)))
    }

    @Test
    fun `runTurn executes the tool calls that ride along with narration content`() = runBlocking {
        // The cap7 round-8 field shape: the model narrates the next step AND
        // emits the fetch call in the same response. The old flow delivered
        // the narration and dropped the call — the turn stranded with no data.
        val executor = FakeExecutor()
        val localHarness = HarnessLoop(executor, registry)
        val provider = FakeProvider(
            answer(
                "The searches came back empty, so let me fetch a live gold-rate page directly.",
                toolCalls = listOf(LLMToolCall("fetch_url", """{"url":"https://www.bankbazaar.com/gold-rate.html"}"""))
            ),
            answer("Gold is at $4,156.40 per troy ounce (spot bid), as of Sep 29, 2026.")
        )

        val result = localHarness.runTurn(
            provider,
            config(
                tool = Tool(
                    "fetch_url", "fetch",
                    """{"type":"object","properties":{"url":{"type":"string"}}}"""
                )
            )
        )

        // The fetch EXECUTED (executor was called) and the final answer is
        // the grounded one — not the dropped narration.
        assertEquals(1, executor.calls.size)
        assertEquals("FETCH_URL", executor.calls[0].first)
        assertEquals("Gold is at $4,156.40 per troy ounce (spot bid), as of Sep 29, 2026.", result!!.content)
        // The narration rode into the next round as assistant context.
        assertTrue(
            provider.requests[1].messages.any {
                it.text.contains("searches came back empty")
            }
        )
    }

    @Test
    fun `runTurn re-asks when a post-tool answer is pure monologue`() = runBlocking {
        val provider = FakeProvider(
            answer("", toolCalls = listOf(LLMToolCall("web_search", """{"query":"gold price"}"""))),
            // The field defect: after REAL results, the model answers with monologue.
            answer("Need to search for the current gold price. Let me search."),
            answer("Gold is at $4,156.40 per troy ounce, as of September 29, 2026.")
        )

        val result = harness.runTurn(
            provider,
            config(tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}"""))
        )

        assertEquals("Gold is at $4,156.40 per troy ounce, as of September 29, 2026.", result!!.content)
        assertEquals(3, provider.requests.size)
        // The corrective re-ask carries the FINAL_ANSWER_NUDGE instruction.
        assertTrue(
            provider.requests[2].messages.any { it.text.startsWith("Deliver the final user-facing answer NOW") }
        )
    }

    @Test
    fun `forcedSearchTurn executes the search itself and grounds the answer`() = runBlocking {
        val executor = FakeExecutor()
        val localHarness = HarnessLoop(executor, registry)
        val toolEvents = mutableListOf<Pair<String, Boolean>>()
        val provider = FakeProvider(
            answer("Bitcoin is trading at $84,114.36 (CoinDesk), as of Sep 29, 2026.")
        )

        val result = localHarness.forcedSearchTurn(
            provider,
            config(tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")),
            history = listOf(ChatMessage("1", "What is the current price of Bitcoin in USD right now?", ChatMessage.Sender.USER)),
            userQuery = "What is the current price of Bitcoin in USD right now?",
            onStatus = null
        )

        // THE BAR: the search executed for real — no model permission involved.
        assertEquals(1, executor.calls.size)
        assertEquals("WEB_SEARCH", executor.calls[0].first)
        assertEquals(
            "What is the current price of Bitcoin in USD right now?",
            executor.calls[0].second["query"]
        )
        // The real results were seeded into the answering call's context.
        assertEquals(1, provider.requests.size)
        val seeded = provider.requests[0].messages
        assertTrue(seeded.any { it.text.contains("TOOL RESULT web_search") && it.text.contains("status: OK") })
        assertTrue(seeded.any { it.text.contains("What is the current price of Bitcoin in USD right now?") })
        assertEquals("Bitcoin is trading at $84,114.36 (CoinDesk), as of Sep 29, 2026.", result!!.content)
    }

    @Test
    fun `forcedSearchTurn returns null when the direct search fails`() = runBlocking {
        val failing = HarnessLoop(
            HarnessToolExecutor { _, _, _ -> ActionResult.Failure("network unreachable") },
            registry
        )
        val provider = FakeProvider()

        val result = failing.forcedSearchTurn(
            provider,
            config(),
            history = listOf(ChatMessage("1", "ask", ChatMessage.Sender.USER)),
            userQuery = "What is the current price of gold?"
        )

        assertNull(result)
        // No model call was burned on a search that never happened.
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `forcedSearchTurn surfaces the tool event for the visible trace`() = runBlocking {
        val events = mutableListOf<Triple<String, Boolean, String>>()
        val localHarness = HarnessLoop(FakeExecutor(), registry)
        val cfg = HarnessLoop.TurnConfig(
            systemPrompt = "test prompt",
            history = emptyList(),
            tools = emptyList(),
            context = context,
            onToolEvent = { action, success, detail ->
                events.add(Triple(action, success, detail))
            }
        )
        val provider = FakeProvider(answer("Grounded answer."))

        localHarness.forcedSearchTurn(
            provider, cfg,
            history = emptyList(),
            userQuery = "latest bitcoin price"
        )

        assertEquals(1, events.size)
        assertEquals("WEB_SEARCH", events[0].first)
        assertTrue(events[0].second)
    }
    // ── v1.3.0 surfaceAsks: the ask-and-resume contract ─────────────────

    @Test
    fun `surfaceAsks returns an ask-shaped result without executing the tool`() = runBlocking {
        val askTool = Tool(
            "ask_user", "ask the user",
            """{"type":"object","properties":{"question":{"type":"string"}},"required":["question"]}"""
        )
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(
                    LLMToolCall("ask_user", """{"question":"Which city?","options":["Pune","Mumbai"]}""")
                )
            )
        )
        val cfg = config(tool = askTool).copy(surfaceAsks = true)

        val result = harness.runTurn(provider, cfg)

        // The ask is surfaced, not executed.
        assertEquals("Which city?", result!!.askQuestion)
        assertEquals(listOf("Pune", "Mumbai"), result.askOptions)
        // The resume payload carries the tool-round stub EXACTLY ONCE
        // (critic round 2 caught a double-append: the stub is added before
        // the tool loop, the surface path must not add it again). The stub is
        // an AGENT message ("I used ask_user(...) to work on this.").
        assertEquals(1, result.resumedMessages.count { it.text.contains("I used ") })
        assertTrue(result.resumedMessages.last().text.contains("ask_user"))
        // The original user query still leads the resume history.
        assertEquals("user ask", result.resumedMessages.first().text)
    }

    @Test
    fun `ask resume continues the turn with the user answer in context`() = runBlocking {
        val askTool = Tool(
            "ask_user", "ask the user",
            """{"type":"object","properties":{"question":{"type":"string"}},"required":["question"]}"""
        )
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(
                    LLMToolCall("ask_user", """{"question":"Which city?","options":["Pune","Mumbai"]}""")
                )
            ),
            // The resumed call must SEE the user's answer and finish.
            answer("The city you picked is Pune. Done.")
        )
        val cfg = config(tool = askTool).copy(surfaceAsks = true)

        val first = harness.runTurn(provider, cfg)!!
        assertEquals("Which city?", first.askQuestion)

        // The caller parks (unbounded), collects, then resumes:
        val resumeHistory = first.resumedMessages + ChatMessage(
            id = "ans", text = HarnessLoop.askResultMessage("Which city?", "Pune"),
            sender = ChatMessage.Sender.USER
        )
        val resumed = harness.runTurn(provider, cfg.copy(history = resumeHistory))!!

        assertEquals("The city you picked is Pune. Done.", resumed.content)
        assertEquals(2, provider.requests.size)
        // The resumed request actually carried the answer.
        assertTrue(provider.requests[1].messages.any { it.text.contains("The user answered: \"Pune\"") })
    }

    @Test
    fun `parseAskUserArguments reads the question and options`() {
        val (q, opts) = HarnessLoop.parseAskUserArguments(
            """{"question":"Pick one","options":["A","B"]}"""
        )
        assertEquals("Pick one", q)
        assertEquals(listOf("A", "B"), opts)
    }

    // ── v1.3.0 round 21: the 2026-10-03 give-up nudges ─────────────────
    // The 20:11 gold screenshot: searches ran, results carried no figure,
    // and the model's "I wasn't able to pull an actual live XAU/USD number…"
    // became the final reply. The harness now pushes ONE guided retry.

    @Test
    fun `a give-up answer on a price ask gets one guided retry`() = runBlocking {
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(LLMToolCall("web_search", """{"query":"gold price today"}"""))
            ),
            // The exact 20:11 field give-up shape.
            answer(
                "I wasn't able to pull an actual live XAU/USD number — the quote feed " +
                    "returned only page listings rather than a price."
            ),
            // The guided retry lands a grounded figure.
            answer("Gold is at $2,109.30 per ounce right now (Kitco).")
        )

        val result = harness.runTurn(
            provider,
            config(
                tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")
            ).copy(
                history = listOf(ChatMessage("1", "what's the price of gold", ChatMessage.Sender.USER))
            )
        )

        assertEquals("Gold is at $2,109.30 per ounce right now (Kitco).", result!!.content)
        // The retry request carried the guidance.
        assertTrue(
            provider.requests[2].messages.any { it.text.contains("fetch_url the most promising") }
        )
    }

    @Test
    fun `a second give-up is delivered honestly - the nudge fires only once`() = runBlocking {
        val giveUp = "I wasn't able to pull an actual live number, sorry."
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(LLMToolCall("web_search", """{"query":"gold price"}"""))
            ),
            answer(giveUp),
            answer(giveUp)
        )

        val result = harness.runTurn(
            provider,
            config(
                tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")
            ).copy(
                history = listOf(ChatMessage("1", "what's the price of gold", ChatMessage.Sender.USER))
            )
        )

        assertEquals(giveUp, result!!.content)
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun `a search without a figure on a price ask triggers fetch guidance once`() = runBlocking {
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(LLMToolCall("web_search", """{"query":"nvidia stock price"}"""))
            ),
            answer(
                "",
                toolCalls = listOf(LLMToolCall("fetch_url", """{"url":"https://nasdaq.com/market-activity/stocks/nvda"}"""))
            ),
            answer("NVDA closed at $187.42, up 1.2%.")
        )

        val result = harness.runTurn(
            provider,
            config(
                tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")
            ).copy(
                history = listOf(ChatMessage("1", "price of Nvidia stock", ChatMessage.Sender.USER))
            )
        )

        assertEquals("NVDA closed at $187.42, up 1.2%.", result!!.content)
        // The request after the figure-less search carried the guidance.
        assertTrue(
            provider.requests[1].messages.any { it.text.contains("no actual number") }
        )
    }

    @Test
    fun `non-price asks never get the give-up nudge`() = runBlocking {
        val provider = FakeProvider(
            answer(
                "",
                toolCalls = listOf(LLMToolCall("web_search", """{"query":"roman history"}"""))
            ),
            answer("I couldn't find a definitive answer in those results, but here's the overview.")
        )

        val result = harness.runTurn(
            provider,
            config(
                tool = Tool("web_search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""")
            ).copy(
                history = listOf(ChatMessage("1", "teach me about the Roman empire", ChatMessage.Sender.USER))
            )
        )

        assertEquals(
            "I couldn't find a definitive answer in those results, but here's the overview.",
            result!!.content
        )
        assertEquals(2, provider.requests.size)
    }

}
