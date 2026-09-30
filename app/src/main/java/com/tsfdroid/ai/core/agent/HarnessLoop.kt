package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMStreamEvent
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.Tool
import com.tsfdroid.ai.core.llm.providers.ModelsDevRegistry
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.2.0: the OpenCode-style agentic harness.
 *
 * The model is called REPEATEDLY until the turn is genuinely done — exactly the
 * mechanism OpenCode/Hermes use and the old single-call chat path lacked:
 *
 *  - TOOL ROUNDS: every round, tool calls the model issues are executed for
 *    real and their results are fed back as messages, then the model is called
 *    again with the full accumulated context. This is how research and
 *    multi-step answers actually get done instead of half-answered.
 *  - CONTINUATION: when a response comes back with finish_reason "length"
 *    (the model hit its output budget mid-answer), the app immediately calls
 *    the API again with a CONTINUE instruction and appends the segments —
 *    the user sees ONE complete answer, never a truncated one. This is the
 *    "no artificial output limit" guarantee: each call runs at the model's
 *    real output capability (registry-clamped, capped at 32k tokens like
 *    OpenCode itself), and long answers flow across calls.
 *  - GUARDS: rounds are capped (efficiency, not infinity), identical
 *    consecutive tool calls trip a doom-loop guard (OpenCode's), and the last
 *    rounds get a wrap-up nudge so the loop lands an answer instead of
 *    expiring into an error.
 *
 * READ-ONLY mode (Chat mode): [TurnConfig.readOnly] gates EXECUTION — a mapped
 * action outside [CHAT_MODE_ALLOWED_ACTIONS] is refused with a clear result the
 * model reads. The prompt already forbids it; this gate makes it impossible.
 */
/**
 * Test seam for tool execution: production binds [ActionDispatcher.execute]
 * through Hilt; unit tests substitute a fake. Every executed call passes the
 * same pipeline (internet pre-check, schema validation) as planned steps.
 */
fun interface HarnessToolExecutor {
    suspend fun execute(actionName: String, params: Map<String, String>, context: android.content.Context): ActionResult
}

/**
 * v1.2.1 E2E round-6 field evidence (cap15, both CI passes): mimo streamed a
 * complete-looking Bitcoin answer from memory, then — asked AGAIN by the
 * research-guarantee harness loop — answered from memory once more with ZERO
 * tool calls. The turn delivered unverified figures with no ACTIVITY trace.
 * Detection: short, announcement-shaped text ("need to…", "let me…", "I'll…")
 * that never becomes an answer. Used in two places: the harness re-asks for
 * the final answer instead of delivering monologue, and the chat stream
 * treats it as a tool-handoff signal (the model announcing work it has not
 * done yet).
 */
private val MONOLOGUE_SHAPED = Regex(
    "(?i)\\A\\s*(need to|gonna|i'm going to|i am going to|searching|looking up|" +
        "looking for|to answer|first,? i|okay,? i)"
)

/**
 * v1.2.1 round-8 field evidence (cap7 gold): "The searches came back empty,
 * so let me fetch a live gold-rate page directly." — the announcement sits
 * MID-text after a status sentence. Also covers the opener case with the
 * action-verb requirement: "Let me search" is monologue, "Let me know" and
 * "Let me give you the key facts: …" are conversation/answers.
 */
private val MONOLOGUE_ACTION_MID = Regex(
    "(?i)\\b(let me|i'll|i will)\\s+(search|fetch|look|check|find|get|grab|" +
        "pull|query|run|call)\\b"
)

internal fun isMonologueShaped(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 500) return false
    if (!MONOLOGUE_SHAPED.containsMatchIn(t) && !MONOLOGUE_ACTION_MID.containsMatchIn(t)) return false
    // A question back to the user is a real conversational move, not monologue.
    if (t.endsWith("?")) return false
    // A colon introduces the actual content ("Let me give you the key facts:
    // gold is at $4,156/oz.") — that is an answer with a preamble, never
    // pure monologue.
    return !t.contains(':')
}

@Singleton
class HarnessLoop @Inject constructor(
    private val toolExecutor: HarnessToolExecutor,
    private val registry: ModelsDevRegistry
) {

    data class TurnConfig(
        val systemPrompt: String,
        /** Conversation history + any accumulated tool messages from the caller. */
        val history: List<ChatMessage>,
        val tools: List<Tool>,
        /** Application context for action execution. */
        val context: android.content.Context,
        /** Chat mode: refuse every non-read-only action at the execution gate. */
        val readOnly: Boolean = false,
        val temperature: Float = 0.4f,
        /** Per-call output budget — registry-clamped by the provider. */
        val maxTokens: Int = OUTPUT_TOKEN_MAX,
        val reasoningEffort: String? = null,
        /** v1.3.0: this turn carries images a blind pinned model must not answer — route to a vision-capable model chain where the provider supports it. */
        val requireVision: Boolean = false,
        val maxRounds: Int = MAX_HARNESS_ROUNDS,
        val maxContinuations: Int = MAX_CONTINUATIONS,
        /** Agent-loop artifact callback (WRITE_FILE/CREATE_PDF cards). */
        val onArtifact: (suspend (action: String, params: Map<String, String>, result: ActionResult) -> Unit)? = null,
        /**
         * v1.3.0 ask_user tool: invoked when the model calls ask_user — the
         * app posts the question (with tappable options) and parks the turn
         * until the user answers; the returned string is fed back to the
         * model as the tool's result. Null disables the tool at execution
         * time (the model then reads an honest "not available" result).
         */
        val onAskUser: (suspend (question: String, options: List<String>) -> String)? = null,
        /**
         * v1.3.0: when true, an ask_user call does NOT suspend this loop —
         * runTurn returns an ask-shaped [TurnResult] (question, options, and
         * the messages to resume from) so the CALLER parks the turn outside
         * any wall-clock bound and re-invokes runTurn with the answer
         * appended. The user answering slowly must never trip a turn
         * timeout; the suspension path ([onAskUser]) remains for callers
         * that run unbounded (e.g. mid-continuation).
         */
        val surfaceAsks: Boolean = false,
        /**
         * v1.2.1: visible-work hooks. [onToolEvent] fires once per executed
         * tool call (mapped action, success, one-line outcome) and
         * [onContinuation] fires per output-limit continuation — both feed
         * the chat-side ACTIVITY trace (Claude/OpenCode step list).
         */
        val onToolEvent: (suspend (action: String, success: Boolean, detail: String) -> Unit)? = null,
        val onContinuation: (suspend (partNumber: Int) -> Unit)? = null
    )

    data class TurnResult(
        val content: String,
        val rounds: Int,
        val toolCallsExecuted: Int,
        val continuationSegments: Int,
        /** finish_reason of the last model call (null when unsurfaced). */
        val finishReason: String?,
        /** True when even after all continuations the answer stayed length-cut. */
        val stillTruncated: Boolean,
        /**
         * v1.3.0: non-null when [TurnConfig.surfaceAsks] is set and the model
         * called ask_user — the caller must park the user, collect the
         * answer (unbounded), then resume by calling runTurn again with
         * [resumedMessages] + [askResultMessage] appended.
         */
        val askQuestion: String? = null,
        val askOptions: List<String> = emptyList(),
        /** The full message list to resume the turn from (history + this round's stubs). Empty unless [askQuestion] is set. */
        val resumedMessages: List<ChatMessage> = emptyList()
    )

    /**
     * Runs the full tool/continuation loop from [config.history] until a
     * content answer is complete, the guards trip, or the model fails.
     * Returns null when no usable answer was produced (caller decides the
     * fallback — the snag message path).
     */
    suspend fun runTurn(
        provider: LLMProvider,
        config: TurnConfig,
        onStatus: (suspend (String) -> Unit)? = null
    ): TurnResult? {
        var messages = config.history
        var round = 0
        var synthesisAttempted = false
        var toolCallsExecuted = 0
        var continuationSegments = 0
        var lastFinishReason: String? = null
        val recentSignatures = ArrayDeque<String>()
        var doomWarned = false

        // v1.2.1 round-16: layer-by-layer evidence. Rounds 10-15 proved the
        // hang layer cannot be found from pump-level logs alone — cap15's
        // turn published 2 tool steps and streamed the final 646-char answer
        // (finish=stop, run 36685166207 logcat 08:41:34) and then went
        // SILENT for 17 minutes: no further HTTP call, no save, no error,
        // no timeout line. Every layer from here to the caller's save must
        // announce itself so the next failure names its layer in one run.
        android.util.Log.i(
            TAG,
            "runTurn BEGIN history=${config.history.size} msgs maxRounds=${config.maxRounds} readOnly=${config.readOnly}"
        )

        while (round < config.maxRounds) {
            round++
            onStatus?.invoke("[harness] thinking round $round of ${config.maxRounds}…")

            val response = try {
                android.util.Log.i(TAG, "round $round model-call start (history=${messages.size} msgs)")
                withTimeout(PER_MODEL_CALL_TIMEOUT_MS) {
                    provider.complete(
                        LLMRequest(
                            systemPrompt = config.systemPrompt,
                            messages = messages,
                            temperature = config.temperature,
                            maxTokens = config.maxTokens,
                            responseFormat = ResponseFormat.TEXT,
                            allowToolCalls = true,
                            tools = config.tools,
                            reasoningEffort = config.reasoningEffort,
                            requireVision = config.requireVision
                        )
                    )
                }
            } catch (tce: TimeoutCancellationException) {
                // NOT a user cancel: the per-call wall-clock bound fired. The
                // round-11/12/13 notes claimed this bound existed — it did
                // NOT (lost somewhere before round 15), which is exactly how
                // a stalled call could hold the turn for 17 unbounded
                // minutes. Abort the attempt; the caller's fallback chain
                // (forced search -> honesty note / snag) completes the turn.
                android.util.Log.w(
                    TAG,
                    "round $round model call exceeded ${PER_MODEL_CALL_TIMEOUT_MS / 1000}s wall clock — aborting attempt, fallback takes over"
                )
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "LLM call failed: ${e.localizedMessage}")
                return null
            }
            android.util.Log.i(
                TAG,
                "round $round model-call done content=${response.content.length}c tools=${response.toolCalls.size} finish=${response.finishReason}"
            )
            lastFinishReason = response.finishReason ?: lastFinishReason

            // v1.2.1 round-8 field fix (cap7 gold): a response carrying BOTH
            // content and tool calls is MID-WORK narration ("The searches came
            // back empty, so let me fetch a live gold-rate page directly." —
            // with a fetch call attached). The old flow returned the narration
            // and DROPPED the calls, stranding the turn mid-work with no data.
            // Keep the narration as context, execute the calls, and let the
            // loop land the real answer after the results arrive.
            val narrationWithCalls = response.content.isNotBlank() &&
                response.toolCalls.isNotEmpty() &&
                round < config.maxRounds - 1

            if (response.content.isNotBlank() && !narrationWithCalls) {
                // v1.1.1 blind-critic fix, kept: a model that just echoes the
                // raw web_search listing gets ONE synthesis round before its
                // text is delivered as-is.
                val echoedListing =
                    response.content.startsWith("Top web results", ignoreCase = true) ||
                        response.content.startsWith("Web results for", ignoreCase = true)
                if (echoedListing && !synthesisAttempted && round < config.maxRounds - 1) {
                    synthesisAttempted = true
                    messages = messages + userMessage(
                        "Do not repeat the raw result listing. Synthesize the search results " +
                            "you received into a direct, complete answer to the user's question — " +
                            "key facts and dates first, cite source URLs inline."
                    )
                    android.util.Log.i(TAG, "round $round echoed a raw listing — one synthesis re-ask")
                    continue
                }

                // CONTINUATION: the model hit its output budget mid-answer.
                // Never deliver a truncated reply — call again and append.
                if (response.finishReason == FINISH_LENGTH &&
                    continuationSegments < config.maxContinuations &&
                    round < config.maxRounds - 1
                ) {
                    onStatus?.invoke(
                        "[harness] output limit reached — continuing the answer (part ${continuationSegments + 2})…"
                    )
                    config.onContinuation?.invoke(continuationSegments + 2)
                    messages = messages +
                        assistantMessage(response.content) +
                        userMessage(CONTINUATION_INSTRUCTION)
                    return appendContinuations(
                        provider, config, messages, response.content,
                        roundsUsed = round, executed = toolCallsExecuted,
                        segments = 0, onStatus = onStatus
                    )
                }

                // v1.2.1 round-6 field fix (cap8 CBSE reply): after REAL tool
                // results are in context, the model sometimes answers with
                // pure internal monologue ("Need to search… Let me search.")
                // instead of the user-facing answer. Never deliver that — one
                // corrective re-ask for the final answer, then deliver.
                if (toolCallsExecuted > 0 && !synthesisAttempted &&
                    round < config.maxRounds - 1 &&
                    isMonologueShaped(response.content)
                ) {
                    synthesisAttempted = true
                    messages = messages + userMessage(FINAL_ANSWER_NUDGE)
                    android.util.Log.i(TAG, "round $round answer was monologue-shaped — one FINAL_ANSWER_NUDGE re-ask")
                    continue
                }

                android.util.Log.i(
                    TAG,
                    "runTurn EXIT answer rounds=$round tools=$toolCallsExecuted cont=$continuationSegments finish=$lastFinishReason content=${response.content.length}c"
                )
                return TurnResult(
                    content = response.content,
                    rounds = round,
                    toolCallsExecuted = toolCallsExecuted,
                    continuationSegments = continuationSegments,
                    finishReason = lastFinishReason,
                    stillTruncated = response.finishReason == FINISH_LENGTH
                )
            }

            if (response.toolCalls.isEmpty() && !narrationWithCalls) {
                android.util.Log.w(TAG, "runTurn EXIT null: round $round returned no content and no tool calls (finish=$lastFinishReason)")
                return null
            }

            // ---- Tool execution round ----
            val signature = response.toolCalls.joinToString("|") { call ->
                call.name + ":" + call.arguments.hashCode()
            }
            recentSignatures.addLast(signature)
            while (recentSignatures.size > DOOM_LOOP_THRESHOLD) recentSignatures.removeFirst()
            if (recentSignatures.size == DOOM_LOOP_THRESHOLD &&
                recentSignatures.distinct().size == 1
            ) {
                if (!doomWarned) {
                    // OpenCode's doom-loop guard: same call three times in a
                    // row. Warn once with a way out; a FOURTH identical round
                    // ends the loop — efficiency over stubbornness.
                    doomWarned = true
                    messages = messages + userMessage(DOOM_WARNING)
                    android.util.Log.i(TAG, "round $round doom-guard warning (identical calls x$DOOM_LOOP_THRESHOLD)")
                    continue
                }
                android.util.Log.w("HarnessLoop", "Doom loop: identical tool round x$DOOM_LOOP_THRESHOLD twice — stopping")
                return null
            }

            if (narrationWithCalls) {
                // The narration is context for the next round, not the answer.
                messages = messages + assistantMessage(response.content)
            } else {
                messages = messages + toolRoundStub(response.toolCalls)
            }
            for (call in response.toolCalls) {
                // v1.3.0 ask_user: the one tool that is NOT an action — it
                // parks the turn on the USER. Intercepted before the bridge
                // (it maps to no device action) and allowed in read-only
                // Chat mode too: asking a question mutates nothing. Mirrors
                // opencode's question tool. Two execution modes:
                //   - surfaceAsks: return the question to the CALLER (which
                //     parks the user outside any wall-clock bound, timeout-
                //     safe) — the chat path;
                //   - onAskUser: suspend here (legacy/unbounded callers).
                if (call.name.equals(ASK_USER_TOOL, ignoreCase = true) ||
                    call.name.equals("question", ignoreCase = true)
                ) {
                    val ask = parseAskUserArguments(call.arguments)
                    if (ask.first.isNotBlank()) {
                        if (config.surfaceAsks) {
                            config.onToolEvent?.invoke("ask_user", true, ask.first.take(120))
                            android.util.Log.i(
                                "HarnessLoop",
                                "round $round ask_user surfaced to caller (options=${ask.second.size})"
                            )
                            // `messages` ALREADY carries this round's stub
                            // (or narration) — the append above ran before the
                            // tool loop — so the resume payload is exactly it.
                            // Re-adding the stub/narration here would duplicate
                            // them (critic round 2).
                            return TurnResult(
                                content = "",
                                rounds = round,
                                toolCallsExecuted = toolCallsExecuted,
                                continuationSegments = continuationSegments,
                                finishReason = lastFinishReason,
                                stillTruncated = false,
                                askQuestion = ask.first,
                                askOptions = ask.second,
                                resumedMessages = messages
                            )
                        }
                        val answer = if (config.onAskUser != null) {
                            config.onToolEvent?.invoke(
                                "ask_user", true, ask.first.take(120)
                            )
                            config.onAskUser!!.invoke(ask.first, ask.second)
                        } else {
                            "REFUSED: asking the user is not available in this session — " +
                                "answer from what you already know or say you cannot decide."
                        }
                        toolCallsExecuted++
                        android.util.Log.i(
                            "HarnessLoop",
                            "round $round ask_user answered (options=${ask.second.size})"
                        )
                        messages = messages + ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = ToolCallBridge.renderToolResult(
                                call, true,
                                "The user answered: \"$answer\". " +
                                    "Continue with this answer in mind."
                            ),
                            sender = ChatMessage.Sender.USER
                        )
                        continue
                    }
                }
                val mapping = ToolCallBridge.map(call)
                val mapped = mapping.mapped
                val result: ActionResult = when {
                    mapped == null -> ActionResult.Failure(
                        mapping.unsupportedReason ?: "Tool not available"
                    )
                    config.readOnly && mapped.action !in CHAT_MODE_ALLOWED_ACTIONS ->
                        // THE read-only gate: execution-level, not prompt-level.
                        ActionResult.Failure(
                            "REFUSED: Chat mode is read-only. '$mapped.action' " +
                                "modifies the device or files, which is not permitted in " +
                                "Chat mode. Answer the user in text; if the task truly " +
                                "needs that action, tell them to switch to Agent mode."
                        )
                    else -> try {
                        toolExecutor.execute(mapped.action, mapped.params, config.context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ActionResult.Failure(e.localizedMessage ?: "Tool execution failed")
                    }
                }
                if (result.success && mapped != null && !config.readOnly &&
                    (mapped.action == "WRITE_FILE" || mapped.action == "CREATE_PDF")
                ) {
                    config.onArtifact?.invoke(mapped.action, mapped.params, result)
                }
                toolCallsExecuted++
                android.util.Log.i(
                    "HarnessLoop",
                    "round $round ${call.name} -> ${mapped?.action ?: "unsupported"}: success=${result.success}"
                )
                config.onToolEvent?.invoke(
                    mapped?.action ?: call.name,
                    result.success,
                    (result.data ?: result.error ?: "").toString()
                )
                messages = messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = ToolCallBridge.renderToolResult(
                        call, result.success, result.data ?: result.error ?: ""
                    ),
                    sender = ChatMessage.Sender.USER
                )
            }

            // Wrap-up nudge: make the second-to-last round land the answer.
            if (round == config.maxRounds - 1) {
                messages = messages + userMessage(WRAP_UP_NUDGE)
            }
        }
        android.util.Log.w(TAG, "runTurn EXIT null: rounds exhausted ($round of ${config.maxRounds}), tools=$toolCallsExecuted")
        return null
    }

    /**
     * Streams the FIRST round of a turn (real streaming to the chat UI) and
     * reports the outcome so the caller can decide: tool hand-off, continuation
     * hand-off, or done. Used by [AgentLoop.executeSimpleQuery] where the first
     * call should render live.
     */
    suspend fun streamFirstRound(
        provider: LLMProvider,
        config: TurnConfig,
        onContent: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ): FirstRoundResult {
        var replyText = ""
        var thinkingText = ""
        var finishReason: String? = null
        try {
            provider.streamCompleteDetailed(
                LLMRequest(
                    systemPrompt = config.systemPrompt,
                    messages = config.history,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    responseFormat = ResponseFormat.TEXT,
                    allowToolCalls = true,
                    tools = config.tools,
                    reasoningEffort = config.reasoningEffort
                )
            ).collect { event ->
                when (event) {
                    is LLMStreamEvent.Content -> {
                        if (event.text.isEmpty()) return@collect
                        replyText += event.text
                        onContent?.invoke(event.text)
                    }
                    is LLMStreamEvent.Reasoning -> {
                        thinkingText += event.text
                        onReasoning?.invoke(event.text)
                    }
                    is LLMStreamEvent.Finished -> finishReason = event.reason
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FirstRoundResult(
                status = FirstRoundStatus.FAILED,
                content = replyText,
                thinkingText = thinkingText,
                finishReason = null
            )
        }
        val status = when {
            replyText.isBlank() -> FirstRoundStatus.TOOL_HANDOFF
            // Streamed answer hit the output budget mid-answer: continuation.
            finishReason == FINISH_LENGTH -> FirstRoundStatus.CONTINUE_HANDOFF
            else -> FirstRoundStatus.COMPLETE
        }
        return FirstRoundResult(
            status = status,
            content = replyText,
            thinkingText = thinkingText,
            finishReason = finishReason
        )
    }

    enum class FirstRoundStatus { COMPLETE, TOOL_HANDOFF, CONTINUE_HANDOFF, FAILED }

    data class FirstRoundResult(
        val status: FirstRoundStatus,
        val content: String,
        val thinkingText: String,
        val finishReason: String?
    )

    /**
     * v1.2.1 LENGTH CONTRACT: reasoning models sometimes answer an explicit
     * long-form ask ("an essay of at least 600 words") with a few lazy
     * sentences and finish_reason=stop — no output-limit was hit, so plain
     * continuation never fires and the user gets a thin answer. One bounded
     * expansion pass re-asks for the FULL requested length (continuation
     * segments still flow if the expansion itself hits the budget). This is
     * the harness honoring the user's ask, not the model's whim.
     */
    suspend fun expandShortAnswer(
        provider: LLMProvider,
        config: TurnConfig,
        history: List<ChatMessage>,
        userQuery: String,
        currentReply: String
    ): TurnResult? {
        val expansionMessages = history +
            assistantMessage(currentReply) +
            userMessage(
                "Your reply above is far shorter than what was asked for. The user asked: " +
                    "\"$userQuery\". Deliver the COMPLETE answer at the requested length and " +
                    "depth — do not summarize, do not abbreviate, do not add meta commentary " +
                    "about the request. If you already covered part of it, keep that content " +
                    "and continue/expand from there so the final answer stands alone. " +
                    "Output the full answer now."
            )
        val response = try {
            withTimeout(PER_MODEL_CALL_TIMEOUT_MS) {
                provider.complete(
                    LLMRequest(
                        systemPrompt = config.systemPrompt,
                        messages = expansionMessages,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        responseFormat = ResponseFormat.TEXT,
                        allowToolCalls = false,
                        reasoningEffort = config.reasoningEffort
                    )
                )
            }
        } catch (tce: TimeoutCancellationException) {
            android.util.Log.w(TAG, "expansion call exceeded ${PER_MODEL_CALL_TIMEOUT_MS / 1000}s wall clock — giving up on the expansion")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Expansion call failed: ${e.localizedMessage}")
            return null
        }
        if (response.content.isBlank()) return null
        return if (response.finishReason == FINISH_LENGTH) {
            appendContinuations(
                provider, config, expansionMessages, response.content,
                roundsUsed = 1, executed = 0, segments = 0, onStatus = null
            )
        } else {
            TurnResult(
                content = response.content,
                rounds = 1,
                toolCallsExecuted = 0,
                continuationSegments = 0,
                finishReason = response.finishReason,
                stillTruncated = false
            )
        }
    }

    /**
     * Continues a length-truncated answer: repeatedly calls the API with the
     * accumulated partial + a CONTINUE instruction, appending segments until
     * the model finishes or the continuation budget is spent. Returns the
     * FULL joined answer.
     */
    suspend fun continueAnswer(
        provider: LLMProvider,
        config: TurnConfig,
        historySoFar: List<ChatMessage>,
        firstSegment: String,
        roundsUsed: Int = 1,
        onStatus: (suspend (String) -> Unit)? = null
    ): TurnResult {
        val result = appendContinuations(
            provider, config, historySoFar, firstSegment,
            roundsUsed = roundsUsed, executed = 0, segments = 0,
            onStatus = onStatus
        )
        return result
    }

    private suspend fun appendContinuations(
        provider: LLMProvider,
        config: TurnConfig,
        messages: List<ChatMessage>,
        firstSegment: String,
        roundsUsed: Int,
        executed: Int,
        segments: Int,
        onStatus: (suspend (String) -> Unit)?
    ): TurnResult {
        var current = messages
        var joined = firstSegment
        var totalSegments = segments
        var lastFinish: String? = null
        var round = roundsUsed
        var toolCallsExecuted = executed

        while (totalSegments < config.maxContinuations && round < config.maxRounds) {
            val response = try {
                withTimeout(PER_MODEL_CALL_TIMEOUT_MS) {
                    provider.complete(
                        LLMRequest(
                            systemPrompt = config.systemPrompt,
                            messages = current,
                            temperature = config.temperature,
                            maxTokens = config.maxTokens,
                            responseFormat = ResponseFormat.TEXT,
                            allowToolCalls = true,
                            tools = config.tools,
                            reasoningEffort = config.reasoningEffort,
                            requireVision = config.requireVision
                        )
                    )
                }
            } catch (tce: TimeoutCancellationException) {
                android.util.Log.w(TAG, "continuation call exceeded ${PER_MODEL_CALL_TIMEOUT_MS / 1000}s wall clock — delivering what is already joined")
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Continuation call failed: ${e.localizedMessage}")
                break
            }
            round++
            lastFinish = response.finishReason ?: lastFinish
            if (response.toolCalls.isNotEmpty() && response.content.isBlank()) {
                // The model answered the continuation with tool calls — run
                // them and keep continuing afterwards.
                current = current + toolRoundStub(response.toolCalls)
                for (call in response.toolCalls) {
                    // v1.3.0 ask_user works mid-continuation too — same
                    // contract as the main loop's intercept.
                    if (call.name.equals(ASK_USER_TOOL, ignoreCase = true) ||
                        call.name.equals("question", ignoreCase = true)
                    ) {
                        val ask = parseAskUserArguments(call.arguments)
                        if (ask.first.isNotBlank()) {
                            val answer = if (config.onAskUser != null) {
                                config.onToolEvent?.invoke("ask_user", true, ask.first.take(120))
                                config.onAskUser!!.invoke(ask.first, ask.second)
                            } else {
                                "REFUSED: asking the user is not available in this session."
                            }
                            toolCallsExecuted++
                            current = current + ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = ToolCallBridge.renderToolResult(
                                    call, true,
                                    "The user answered: \"$answer\". " +
                                        "Continue with this answer in mind."
                                ),
                                sender = ChatMessage.Sender.USER
                            )
                            continue
                        }
                    }
                    val mapping = ToolCallBridge.map(call)
                    val mapped = mapping.mapped
                    val result: ActionResult = when {
                        mapped == null -> ActionResult.Failure(
                            mapping.unsupportedReason ?: "Tool not available"
                        )
                        config.readOnly && mapped.action !in CHAT_MODE_ALLOWED_ACTIONS ->
                            ActionResult.Failure(
                                "REFUSED: Chat mode is read-only. '${mapped.action}' is not permitted here."
                            )
                        else -> try {
                            toolExecutor.execute(mapped.action, mapped.params, config.context)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ActionResult.Failure(e.localizedMessage ?: "Tool execution failed")
                        }
                    }
                    toolCallsExecuted++
                    config.onToolEvent?.invoke(
                        mapped?.action ?: call.name,
                        result.success,
                        (result.data ?: result.error ?: "").toString()
                    )
                    if (result.success && mapped != null && !config.readOnly &&
                        (mapped.action == "WRITE_FILE" || mapped.action == "CREATE_PDF")
                    ) {
                        config.onArtifact?.invoke(mapped.action, mapped.params, result)
                    }
                    current = current + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = ToolCallBridge.renderToolResult(
                            call, result.success, result.data ?: result.error ?: ""
                        ),
                        sender = ChatMessage.Sender.USER
                    )
                }
                continue
            }
            if (response.content.isBlank()) break
            joined = joinSegments(joined, response.content)
            totalSegments++
            if (response.finishReason != FINISH_LENGTH) break
            if (totalSegments >= config.maxContinuations || round >= config.maxRounds - 1) break
            onStatus?.invoke(
                "[harness] output limit reached — continuing the answer (part ${totalSegments + 1})…"
            )
            config.onContinuation?.invoke(totalSegments + 1)
            current = current +
                assistantMessage(response.content) +
                userMessage(CONTINUATION_INSTRUCTION)
        }
        return TurnResult(
            content = joined,
            rounds = round,
            toolCallsExecuted = toolCallsExecuted,
            continuationSegments = totalSegments,
            finishReason = lastFinish,
            stillTruncated = lastFinish == FINISH_LENGTH
        )
    }

    /** True when at least one model in the reachable set can actually see images. */
    suspend fun hasVisionSupport(): Boolean {
        val specs = runCatching { registry.specs() }.getOrDefault(emptyMap())
        return specs.values.any {
            it.inputModalities.contains("image") && it.free && it.chatCompletions && !it.deprecated
        }
    }

    /**
     * v1.2.1 round-6 fix (cap15, failed BOTH CI passes): the
     * research-guarantee path re-asked the model and trusted it to call
     * web_search — it answered from memory again, zero tool events, and an
     * unverified answer shipped with no ACTIVITY trace. This turn is the
     * deterministic version: the HARNESS executes WEB_SEARCH itself (no model
     * permission involved), feeds the real results into the context, and the
     * model only writes the grounded user-facing answer on top. The visible
     * trace is guaranteed whenever the network cooperates; the honesty
     * boundary stays for genuine network failures only.
     *
     * Returns null only when the direct search itself failed — the caller
     * then appends the "live search could not be reached" note.
     */
    suspend fun forcedSearchTurn(
        provider: LLMProvider,
        config: TurnConfig,
        history: List<ChatMessage>,
        userQuery: String,
        onStatus: (suspend (String) -> Unit)? = null
    ): TurnResult? {
        val query = userQuery.trim().take(240).ifBlank { return null }
        onStatus?.invoke("[harness] running the search directly…")
        val result = try {
            toolExecutor.execute("WEB_SEARCH", mapOf("query" to query), config.context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure(e.localizedMessage ?: "Search execution failed")
        }
        config.onToolEvent?.invoke(
            "WEB_SEARCH",
            result.success,
            (result.data ?: result.error ?: "").toString()
        )
        if (!result.success) {
            android.util.Log.w("HarnessLoop", "forced search failed: ${result.error}")
            return null
        }
        // Seed the REAL results into the context and let the normal loop
        // machinery finish the turn (echo guard, monologue guard, wrap-up,
        // continuation, doom guard all reused). Two rounds is enough: answer
        // from the results already in context.
        val seeded = history + userMessage(
            "TOOL RESULT web_search (status: OK):\n${result.data}\n\n" +
                "Answer the user's question (\"$userQuery\") using these REAL search " +
                "results. Key facts and figures first, cite source URLs inline. " +
                "Deliver the final user-facing answer only — no internal monologue, " +
                "no announcements of what you are about to do."
        )
        return runTurn(
            provider,
            config.copy(history = seeded, maxRounds = 2),
            onStatus
        )
    }

    private fun userMessage(text: String) = ChatMessage(
        id = UUID.randomUUID().toString(),
        text = text,
        sender = ChatMessage.Sender.USER
    )

    private fun assistantMessage(text: String) = ChatMessage(
        id = UUID.randomUUID().toString(),
        text = text,
        sender = ChatMessage.Sender.AGENT
    )

    /**
     * v1.2.1 round-9 field fix (cap7 pass-2): the literal "[tool calls
     * issued]" stub made reasoning models doubt their own transcript — the
     * model burned rounds re-litigating it ("The transcript says [tool calls
     * issued] - but that's what I claimed") instead of using the tool
     * results that follow. The stub now records what the assistant ACTUALLY
     * did, in first person, so the sequence reads as a coherent story:
     * action -> real result -> grounded answer.
     */
    private fun toolRoundStub(calls: List<com.tsfdroid.ai.core.llm.LLMToolCall>): ChatMessage {
        val summary = calls.joinToString("; ") { call ->
            val args = call.arguments.replace("\n", " ").take(90)
            if (args.isBlank()) call.name else "${call.name}($args)"
        }
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "I used $summary to work on this.",
            sender = ChatMessage.Sender.AGENT
        )
    }

    companion object {
        private const val TAG = "HarnessLoop"

        /** v1.3.0: the ask-the-user tool's advertised name (opencode: "question"). */
        const val ASK_USER_TOOL = "ask_user"

        /**
         * v1.3.0: the tool-result message the CALLER appends to
         * [TurnResult.resumedMessages] before re-invoking runTurn — the
         * resume equivalent of the in-loop suspension's result text.
         */
        fun askResultMessage(question: String, answer: String): String =
            "TOOL RESULT ask_user (status: OK):\n" +
                "The user answered: \"$answer\". Continue with this answer in mind."

        /** Max options accepted from the model's ask_user call — keep the chip row tappable. */
        const val ASK_USER_MAX_OPTIONS = 5

        /**
         * Parses an ask_user tool call's arguments. The schema is
         * {question: string, options?: string[]} — tolerant of the model
         * sending an object array [{label}] (opencode's shape) or a single
         * question string. Returns (question, options).
         */
        fun parseAskUserArguments(arguments: String): Pair<String, List<String>> {
            val trimmed = arguments.trim()
            if (trimmed.isEmpty()) return "" to emptyList()
            return runCatching {
                val obj = Json.parseToJsonElement(trimmed) as? JsonObject ?: return "" to emptyList()
                val question = (obj["question"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull?.trim().orEmpty()
                val header = (obj["header"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull?.trim().orEmpty()
                val labeledQuestion = if (header.isNotBlank() && !question.startsWith(header)) {
                    "$header: $question"
                } else {
                    question
                }
                val options = when (val raw = obj["options"]) {
                    is kotlinx.serialization.json.JsonArray -> raw.mapNotNull { el ->
                        when (el) {
                            is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull?.trim()
                            is JsonObject -> (el["label"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull?.trim()
                            else -> null
                        }
                    }
                    is kotlinx.serialization.json.JsonPrimitive -> listOfNotNull(raw.contentOrNull?.trim())
                    else -> emptyList()
                }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(ASK_USER_MAX_OPTIONS)
                labeledQuestion to options
            }.getOrElse { "" to emptyList() }
        }

        /**
         * v1.2.1 round-16: the per-model-call wall-clock bound the round-11/12/13
         * notes described but the code never carried. EVERY harness model call
         * (tool rounds, continuations, expansion) is capped at 8 minutes; the
         * pump's per-line ensureActive() makes the bound observable even inside
         * a pure-blocking SSE read, and the caller's fallback chain completes
         * the turn honestly instead of hanging forever.
         */
        const val PER_MODEL_CALL_TIMEOUT_MS = 480_000L

        /**
         * v1.2.0 output budget per call: OpenCode's own OUTPUT_TOKEN_MAX. The
         * provider still clamps to each model's registry max_output, so free
         * models with smaller ceilings are honored — but the app never imposes
         * an artificial 4k/8k cut of its own again.
         */
        const val OUTPUT_TOKEN_MAX = 32_000

        /** Efficiency guard: the harness never calls the model forever. */
        const val MAX_HARNESS_ROUNDS = 10

        /** Continuation segments beyond the first streamed call. */
        const val MAX_CONTINUATIONS = 3

        /** OpenCode's identical-consecutive-tool-call doom threshold. */
        const val DOOM_LOOP_THRESHOLD = 3

        const val FINISH_LENGTH = "length"

        val CONTINUATION_INSTRUCTION =
            "CONTINUE: your reply was cut off by the output limit. Resume EXACTLY where it " +
                "stopped — no preamble, no apology, no repetition of earlier text. If you " +
                "were mid-sentence, continue mid-sentence with the exact next characters. " +
                "Complete the full answer."

        val WRAP_UP_NUDGE =
            "You have enough information. Answer the user's request now in full — " +
                "no more tool calls."

        val DOOM_WARNING =
            "You have issued the identical tool call ${DOOM_LOOP_THRESHOLD} times in a row " +
                "with the same arguments. Do NOT repeat it again. Use the result you already " +
                "have and answer the user's request now."

        /**
         * v1.2.1 round-6: the corrective re-ask when a post-tool answer comes
         * back as internal monologue instead of the user-facing reply.
         */
        val FINAL_ANSWER_NUDGE =
            "Deliver the final user-facing answer NOW using the tool results above. " +
                "No internal monologue, no \"let me search\", no announcements — just the " +
                "complete, direct answer to the user's question, key facts first, " +
                "citing the source URLs from the results."

        /**
         * The Chat-mode execution allowlist: actions that cannot mutate the
         * device, files, or the outside world. EVERYTHING else is refused by
         * the harness gate in Chat mode — reads, searches, lookups, and
         * screen understanding only.
         */
        val CHAT_MODE_ALLOWED_ACTIONS = setOf(
            "READ_FILE", "LIST_FILES", "WEB_SEARCH", "FETCH_URL", "SUMMARIZE_URL",
            "GET_WEATHER", "GET_NEWS", "CALCULATE", "TRANSLATE", "DEFINE_WORD",
            "CONVERT_UNITS", "CURRENCY_CONVERT", "CHECK_STOCK", "FACT_CHECK",
            "GET_SYSTEM_INFO", "READ_NOTES", "RECALL_MEMORY", "QUERY_KNOWLEDGE_GRAPH",
            "ANALYZE_SCREENSHOT"
        )

        /**
         * Glues continuation segments into one answer. Mid-sentence cuts must
         * rejoin seamlessly (the model is told to resume with the exact next
         * characters); clean stops get a newline.
         */
        fun joinSegments(a: String, b: String): String {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val next = b.trimStart()
            val lastChar = a.last()
            val endsMidSentence = lastChar.isLetterOrDigit() || lastChar == ',' ||
                lastChar == ';' || lastChar == '-' || lastChar == '/' || lastChar == '('
            val startsLowercase = next.firstOrNull()?.isLowerCase() == true
            return if (endsMidSentence && (startsLowercase || next.firstOrNull() == ',')) {
                a + next
            } else if (a.endsWith("\n")) {
                a + next
            } else {
                a + "\n" + next
            }
        }
    }
}
