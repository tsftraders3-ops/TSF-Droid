package com.tsfdroid.ai.core.agent

import android.content.Context
import com.tsfdroid.ai.actions.ActionDispatcher
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.LatencyBudgetStatus
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.core.llm.prompts.HarnessPrompts
import com.tsfdroid.ai.core.llm.prompts.PlanningPrompts
import com.tsfdroid.ai.core.memory.MemoryManager
import com.tsfdroid.ai.core.memory.ExecutionHistoryPrivacy
import com.tsfdroid.ai.data.models.AutoMode
import com.tsfdroid.ai.data.models.ChatMode
import com.tsfdroid.ai.data.models.selectedModelFor
import com.tsfdroid.ai.core.harness.ActivityStep
import com.tsfdroid.ai.core.harness.ContextCompactor
import com.tsfdroid.ai.core.harness.ToolCallRecord
import com.tsfdroid.ai.core.harness.ToolCallRecords
import com.tsfdroid.ai.core.memory.UserMemoryLearner
import com.tsfdroid.ai.core.llm.providers.ModelsDevRegistry
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStatus
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.data.models.effectiveGrantedActions
import com.tsfdroid.ai.data.models.resolvedAutoMode
import com.tsfdroid.ai.data.models.approvalSettings
import com.tsfdroid.ai.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.tsfdroid.ai.core.llm.LLMStreamEvent
import com.tsfdroid.ai.core.llm.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tsfdroid.ai.core.util.NetworkErrorFormatter
import com.tsfdroid.ai.core.llm.error.LLMError
import com.tsfdroid.ai.core.llm.error.LLMException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_NEEDS_INPUT_PROMPTS = 5
/**
 * v1.6.0 (field P0-8e): the per-PLAN needs-input budget. The per-action cap
 * of 5 never bit in the field - broken steps cycled prompts ACROSS steps
 * (searchText -> content -> direction -> searchText) and burned 28 minutes.
 */
private const val MAX_NEEDS_INPUT_PROMPTS_PER_PLAN = 8
private const val MAX_INCOMPLETE_MESSAGE_IDS = 100
/** Tail length of the live thinking trace published during planning. */
private const val LIVE_THINKING_TAIL = 1500

/** v1.3.0: planner history bound — how many recent turns ride along. */
private const val PLANNING_HISTORY_MESSAGES = 10
/**
 * v1.3.0: max ask_user resumes per chat turn — the loop must land an answer
 * eventually; beyond this the model gets a "too many questions" tool result.
 */
private const val MAX_ASKS_PER_TURN = 3
/** Rough per-message serialization overhead for char-budget trimming. */
private const val MESSAGE_OVERHEAD_CHARS = 48
/**
 * v1.3.0: approximate token cost of one image part in the history budget
 * (vision models charge roughly 1k+ tokens per image).
 */
private const val IMAGE_COST_TOKENS = 1_100

/**
 * v1.3.0: the Claude-style thinking-duration step — "Thought for 12s"
 * (or "Thought for 1m 40s") as the FIRST entry of the visible step
 * trace, so the collapsed THINKING section header can render it and the
 * ACTIVITY list shows the real cost of the reasoning phase. Null when no
 * reasoning streamed ([durationMs] <= 0).
 */
internal fun thinkingDurationStep(durationMs: Long): ActivityStep? {
    if (durationMs <= 0L) return null
    val totalSeconds = (durationMs + 500) / 1000
    val label = if (totalSeconds < 60) {
        "Thought for ${totalSeconds}s"
    } else {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (seconds == 0L) "Thought for ${minutes}m" else "Thought for ${minutes}m ${seconds}s"
    }
    return ActivityStep(
        kind = ActivityStep.KIND_THINKING,
        label = label,
        detail = "reasoning phase, measured from the first thinking delta to the first answer delta"
    )
}

/**
 * v1.3.0: keeps the NEWEST messages whose estimated TOKEN cost fits in
 * [maxTokens] (text at the same 4-chars-per-token heuristic the compactor
 * and output clamp use; each carried image at its own token weight),
 * preserving chronological order. This is the BACKSTOP under the 75%
 * compaction rule, not a replacement for it: compaction summarizes what it
 * trims, this only bounds the assembly.
 */
internal fun trimHistoryToTokenBudget(history: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
    val kept = mutableListOf<ChatMessage>()
    var used = 0
    for (msg in history.asReversed()) {
        val cost = com.tsfdroid.ai.core.llm.PromptBudget.estimateTokens(msg.text) +
            MESSAGE_OVERHEAD_CHARS / 4 + msg.allImages().size * IMAGE_COST_TOKENS
        if (used + cost > maxTokens && kept.isNotEmpty()) break
        kept.add(msg)
        used += cost
    }
    kept.reverse()
    return kept
}

/**
 * v1.3.0: the token budget for the chat-path history — 60% of the model's
 * registry context window (leaving the system prompt, attachments, and
 * growth room below the 75% compaction line, which stays the primary
 * "never lose potential" mechanism). On-device models are hard-capped at
 * a small budget: they live at 4k-token windows where the old 30-message
 * cap already flirted with overflow. Unknown remote models keep a
 * legacy-equivalent 16k tokens.
 */
internal fun historyBudgetFor(modelSpec: com.tsfdroid.ai.core.llm.providers.ZenModelSpec?, onDeviceProvider: Boolean): Int {
    if (onDeviceProvider) {
        // Critic round 2: the flat budget ignored the REAL on-device windows
        // (Gemma 4096, recommended Qwen 0.5B 1280) and the fixed prompt cost
        // (~1.3k tokens: system + tools + context). 1000 tokens of history
        // fits Gemma with output room; the Qwen 0.5B overflow is a pre-existing
        // prompt-size condition no history budget can cure.
        return 1_000
    }
    val window = modelSpec?.contextWindow?.takeIf { it > 0 }
    val budget = window?.let { (it * 0.60).toInt() } ?: 16_000
    return budget.coerceIn(6_000, 120_000)
}

/**
 * v1.2.1 round-11: IDLE bound on the streamed first call of a chat turn —
 * the maximum silence between two deltas. Free-tier reasoning models can
 * trickle a stream for 15+ minutes (a token every few seconds keeps the
 * 300s read timeout quiet); a healthy stream never hits this because its
 * deltas arrive continuously, no matter how long the full answer runs.
 */
private const val STREAM_IDLE_TIMEOUT_MS = 120_000L

/**
 * v1.2.1 round-16: the TURN-level wall-clock bound for every harness fallback
 * invocation (tool loop / forced search / expansion). Per-call bounds live in
 * [HarnessLoop]; this one guarantees the whole fallback chain — however many
 * bounded calls it chains — still completes the turn inside the E2E windows
 * and, above all, never hangs a user's chat indefinitely again.
 */
private const val HARNESS_TURN_TIMEOUT_MS = 900_000L

/**
 * v1.3.0 (run-107): the plan-step hard wall clock. A single action's
 * dispatch may never run unbounded — the gold-price WEB_SEARCH step sat
 * silent for 10 minutes in BOTH E2E passes (network-path stall; the HTTP
 * fetch now has its own hard bound too, see InformationActions.httpGetText).
 * ASK_USER steps are EXCLUDED: user thinking time is deliberately unbounded
 * (the round-6 ask-parking contract). 3 minutes is generous for every
 * machine action (searches, file writes, app automation) while turning a
 * pathological stall into an honest step failure the plan can recover from.
 */
private const val ACTION_STEP_TIMEOUT_MS = 180_000L

/**
 * v1.3.0 (run-113): the post-step advisory machinery is bounded too — the
 * evaluator/replan LLM calls and the memory bookkeeping. Run-113 evidence
 * (gold turn, both passes): the evaluator's Zen call returned a 2-char
 * reply, and the turn then went SILENT forever — no advisory-failure log,
 * no step completion, no summary; the coroutine wedged somewhere in the
 * post-call tail. Every segment of that tail is now individually bounded:
 * a wedged call degrades to the same honest paths the existing catches
 * already handle (advisory CONTINUE / null replan / skipped logging).
 */
private const val ADVISORY_CALL_TIMEOUT_MS = 120_000L
private const val MEMORY_LOG_TIMEOUT_MS = 60_000L

/**
 * v1.3.0 round 16 (the gold-turn wedge, third sighting): the PLAN-STALL
 * WATCHDOG. Rounds 10-15 individually bounded every segment of the plan turn
 * (per-fetch wall clock, per-step 180s, advisory 120s, memory 60s) — and the
 * run-119 gold turn STILL died silently in both passes: the advisor's pump
 * returned in 2.1s (content=2c, reasoning=437c, finish=stop), then the plan
 * coroutine went SILENT for 10 minutes with NONE of the bounds firing and
 * no log from any catch path. Whatever the exact suspension point is (it
 * moved between rounds), the class is "a continuation that never resumes":
 * withTimeout cannot fire inside code that never cooperates again.
 *
 * The watchdog is therefore PROGRESS-based, not location-based: a separate
 * coroutine on [scope] checks that the plan loop is still making progress
 * (any loop iteration, step completion, or advisory return). No progress for
 * 7 minutes (with ASK parking excluded — user thinking time is unbounded by
 * design) → the turn is salvaged: the plan is terminal-marked via the
 * mutex-free watchdog path, and a deterministic reply quoting the completed
 * steps' results is saved. The salvaged reply carries the real gathered
 * data (the search listing with prices), so the user — and the E2E — get
 * the substance of the turn instead of a silent wedge. 7 minutes is
 * comfortably above the worst legitimate progress gap (180s step + 120s
 * advisory + margins) and well inside the 600s E2E window, leaving the
 * salvage ~3 minutes of visibility.
 */
private const val PLAN_STALL_WATCHDOG_MS = 420_000L
private const val PLAN_STALL_CHECK_INTERVAL_MS = 30_000L
/**
 * v1.3.0 round-7: dedicated bound for the ask-confirmation summary call —
 * a single 120-token sentence must never borrow the 15-minute turn budget;
 * a stalling free tier degrades to the canned answer-summary instead of
 * delaying the final bubble by minutes.
 */
private const val ASK_CONFIRM_TIMEOUT_MS = 30_000L

/**
 * v1.3.0 round 24: the synthesized WEB_SEARCH query-repair call's wall clock.
 * A stalling free tier must never delay the corrective plan — the
 * deterministic SearchQueryQuality strip stays as the fallback.
 */
private const val SEARCH_QUERY_REPAIR_TIMEOUT_MS = 15_000L

/**
 * v1.4.0: dedicated bound for the Hermes answer-engine synthesis call. A
 * real answer (up to 900 tokens) needs more than the ask-confirm sentence,
 * but a stalling free tier must never delay the final bubble past this —
 * the deterministic extractive fallback answers from the same step results.
 * Two attempts (retry nudge) fit inside the E2E 600s windows alongside the
 * rest of the turn.
 */
private const val SYNTHESIS_TIMEOUT_MS = 75_000L
/**
 * v1.2.0: bounded rounds for the chat-path tool loop now live in
 * [HarnessLoop] (OpenCode-style: tool rounds + continuation + doom guard).
 * The plan-path budget is unchanged.
 */

/**
 * v1.2.0: chat-path context window. The harness shares the conversation with
 * every call (OpenCode re-sends full history each step); the effective window
 * is now TOKEN-AWARE (v1.3.0): up to [CHAT_HISTORY_WINDOW_MAX] messages ride
 * along, trimmed to a char budget derived from the model's registry context
 * window — a 128k model keeps far more turns than a small one, and the 75%
 * compaction rule still guards the far end.
 */
private const val CHAT_HISTORY_WINDOW_MAX = 200

/**
 * Output budget for plan generation. Plans for content-creation tasks
 * (WRITE_FILE with a full HTML page, CREATE_PDF body) carry the entire file
 * content inline, so the ceiling must leave room for real artifacts — 1500
 * truncated such plans mid-JSON and the turn degraded into a prose
 * "I am creating the file" chat reply with nothing actually written.
 */
private const val PLANNING_MAX_TOKENS = 4096

/**
 * v1.2.1: minimum delivered length for an explicit long-form ask before the
 * harness's one-pass expansion kicks in. A 600-word essay is ~3500 chars;
 * 1200 chars means the model did not even try — everything at or above the
 * threshold is treated as the model exercising legitimate length judgment.
 */
private const val LONG_FORM_MIN_CHARS = 1200

/**
 * v1.2.1: queries that REQUIRE fresh, real-world data. When the chat model
 * answers one of these without a single tool call, the harness grounds the
 * answer through the tool loop (the research guarantee) — the model's prompt
 * mandate alone was proven insufficient on the live endpoint.
 */
private val FRESH_DATA_QUERY =
    Regex(
        "(?i)(current|right now|as of|latest|today('s)?|tonight|this week|this month|" +
            "price|worth|stock|share price|exchange rate|weather|forecast|temperature|" +
            "news|headline|score|standings|who won|release date|schedule)"
    )

/**
 * v1.2.1: queries that explicitly ask for LONG-FORM output (essays, stories,
 * reports, detailed explanations). Combined with [LONG_FORM_MIN_CHARS] this
 * drives the bounded expansion pass — lazy one-paragraph answers to a
 * "600 words" ask are re-delivered at the requested length.
 */
private val LONG_FORM_QUERY =
    Regex(
        "(?i)(essay|article|story|letter|poem|report|blog post|chapter|" +
            "in detail|detailed|thorough|comprehensive|in-depth|deep dive|" +
            "at least \\d+ words|\\d+\\+? words|full paragraphs|explain (everything|fully|the complete))"
    )

internal fun requiresFreshData(query: String): Boolean = FRESH_DATA_QUERY.containsMatchIn(query)

internal fun asksForLongForm(query: String): Boolean =
    query.length > 40 && LONG_FORM_QUERY.containsMatchIn(query)

private val CONTACT_NUMBER_PROMPT_ACTIONS = setOf("MAKE_CALL", "SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM")

internal fun paramKeyForNeedsInput(needsInput: ActionResult.NeedsInput, actionName: String): String {
    needsInput.metadata["param"]?.let { return it }

    val asksForNumber = needsInput.question.contains("number", ignoreCase = true) ||
            needsInput.question.contains("phone", ignoreCase = true)
    return if (actionName.uppercase() in CONTACT_NUMBER_PROMPT_ACTIONS && asksForNumber) {
        "contact"
    } else {
        "value"
    }
}

sealed interface AgentState {
    object Idle : AgentState
    object Listening : AgentState
    object Thinking : AgentState
    data class PlanProposed(val plan: Plan) : AgentState
    data class ExecutingPlan(val currentStepDesc: String) : AgentState
    data class Speaking(val text: String) : AgentState
    data class Error(val message: String) : AgentState
}

@Singleton
class AgentLoop @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val llmProviderFactory: LLMProviderFactory,
    private val planManager: PlanManager,
    private val actionDispatcher: ActionDispatcher,
    private val actionSequenceExecutor: ActionSequenceExecutor,
    private val memoryManager: MemoryManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: com.tsfdroid.ai.data.repository.SettingsRepository,
    private val reEvalEngine: dagger.Lazy<ReEvaluationEngine>,
    private val harnessLoop: HarnessLoop,
    private val modelsDevRegistry: ModelsDevRegistry,
    private val memoryLearner: UserMemoryLearner
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val queryMutex = Mutex()

    /**
     * v1.3.0 round 16: generation counter for plan-turn executions. The stall
     * watchdog bumps it when it salvages a wedged turn; the superseded loop
     * checks it at every iteration head and exits without side effects if it
     * ever resumes (the observed wedges never did, but a 20-minutes-late
     * resume must not fight the salvage).
     */
    private val planExecutionEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * v1.3.0 round 18: the stall watchdog runs on its OWN thread. Run-122
     * evidence: the watchdog armed at plan start never logged a single line
     * while the plan coroutine sat wedged for 10 minutes — so round 16's
     * version either died with the plan coroutine (its finally-cancel!) or
     * starved on Dispatchers.Default. A dedicated single thread answers
     * neither to the plan coroutine's lifetime nor to any pool's health.
     */
    private val planWatchdogScope by lazy {
        CoroutineScope(kotlinx.coroutines.newSingleThreadContext("plan-stall-watchdog"))
    }

    // The currently in-flight processQuery/approveProposedPlan job, if any. Tracked so a
    // fresh query (or an explicit cancel) can stop whatever the agent is doing right now.
    // NOT reassigned when processQuery is merely delivering a reply to a pending
    // awaitUserResponse() prompt - that reply belongs to the job already running.
    @Volatile private var currentJob: Job? = null

    // The session id pinned for whichever task currentJob is currently running end-to-end.
    // Resolved ONCE, right when a genuinely new task starts (processQuery for a fresh query,
    // or approveProposedPlan), then threaded as a plain parameter through that task's whole
    // call chain, so every message the task writes - plan execution, contact-picker prompts,
    // the final summary - uses that same id and never re-resolves "current session" mid-task.
    // If the user switches (or starts) a chat while the task is still running, its writes
    // keep landing in the chat it was pinned to, not wherever the user has navigated to.
    //
    // That parameter-threading is what makes pinning safe against races: this field is only
    // ever read back in one place - relaying a reply to THIS SAME task's awaitUserResponse()
    // prompt (see processQuery) - never by the task's own ongoing execution, so a later task
    // overwriting this field can't redirect an earlier task's still-unwinding writes.
    @Volatile private var activeTaskSessionId: String = ""

    // The last successfully executed action/params, scoped PER SESSION so a contextual
    // follow-up (AliasResolver.resolveContextual - "turn it off", "stop it", ...) in one
    // chat can only ever resolve against that same chat's own history, never leftover
    // state from an unrelated conversation. Previously this was a pair of process-global
    // @Volatile fields, which meant a device toggle executed in chat A would silently be
    // replayed by a generic contextual phrase typed in chat B. Bundled into one data class
    // per session (rather than two parallel maps) so an action and its params can never be
    // read back mismatched.
    private data class LastExecutedAction(val action: String, val params: Map<String, String>)
    private val lastExecutedActionsBySession = java.util.concurrent.ConcurrentHashMap<String, LastExecutedAction>()

    // Session id the currently PlanProposed plan (if any) was proposed in, or null when
    // agentState isn't PlanProposed. planManager.currentPlan and agentState are both
    // single global values with no session affiliation of their own, so this is what lets
    // a genuinely new task starting in a DIFFERENT session (see resolveStaleProposedPlan)
    // recognize "there's a proposal out there that isn't mine" before it does anything
    // that would otherwise silently clobber it.
    @Volatile private var proposedPlanSessionId: String? = null

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _chatError = MutableStateFlow<ChatErrorUiState?>(null)
    val chatError: StateFlow<ChatErrorUiState?> = _chatError.asStateFlow()

    /**
     * Live reasoning-model thinking trace (v1.0.5): the tail of what the
     * model is currently thinking, published while a planning call or a
     * streamed reply is in flight and reset to null when the turn ends.
     * The chat UI renders it under the thinking indicator so the user can
     * watch the agent's reasoning in real time instead of staring at dots.
     */
    private val _liveThinking = MutableStateFlow<String?>(null)
    val liveThinking: StateFlow<String?> = _liveThinking.asStateFlow()

    /**
     * v1.2.1: the visible steps of the CURRENT turn (tool calls, output-limit
     * continuations, context compactions, plan steps) — the Claude / OpenCode
     * "show your work" trace, published live while the agent works. Cleared at
     * every new task start; the final snapshot is persisted onto the agent's
     * message as [ChatMessage.stepsJson] so the ACTIVITY section survives
     * restarts.
     */
    private val _activitySteps = MutableStateFlow<List<ActivityStep>>(emptyList())
    val activitySteps: StateFlow<List<ActivityStep>> = _activitySteps.asStateFlow()

    /** v1.2.1: the live plan — drives the chat-side agent TODO checklist. */
    val currentPlan: StateFlow<Plan?> get() = planManager.currentPlan

    private val activityStepsMutex = Mutex()

    private suspend fun publishStep(step: ActivityStep) {
        activityStepsMutex.withLock {
            _activitySteps.value = _activitySteps.value + step
            android.util.Log.i(
                "AgentLoop",
                "publishStep: ${step.kind}/${step.label} status=${step.status} total=${_activitySteps.value.size}"
            )
        }
    }

    private suspend fun completeLastRunningStep(status: String, detail: String) {
        activityStepsMutex.withLock {
            val current = _activitySteps.value
            val idx = current.indexOfLast { it.status == ActivityStep.STATUS_RUNNING }
            if (idx < 0) return@withLock
            _activitySteps.value = current.toMutableList().apply {
                set(idx, get(idx).copy(status = status, detail = detail.take(160)))
            }
        }
    }

    private fun currentStepsSnapshot(): List<ActivityStep> = _activitySteps.value

    // Ids of partially streamed agent replies, so re-sent context can label them as
    // incomplete. Bounded: an insertion-ordered set capped at
    // MAX_INCOMPLETE_MESSAGE_IDS, dropping the oldest entry once full - only the ids
    // still inside the last-10-messages context window matter, so evicted entries can
    // never affect a prompt again.
    private val incompleteMessageIds: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                    size > MAX_INCOMPLETE_MESSAGE_IDS
            }
        )
    )

    // v1.4.0 chat export: the tool-call log of the ACTIVE plan loop — one
    // record per dispatched action with raw params, redacted + capped result,
    // and wall-clock duration. Cleared at every executePlanLoop start; the
    // summary save (speakAndSaveSummary) persists the snapshot onto the
    // summary message. The plan-execution epoch serializes plan loops, so
    // this collector never carries two plans' calls at once.
    private val activePlanToolRecords =
        java.util.Collections.synchronizedList(mutableListOf<ToolCallRecord>())

    /** v1.4.0 chat export: the concrete model id that planned/executed the active plan loop. */
    @Volatile private var activePlanModelId: String? = null

    // A single pending awaitUserResponse() prompt, identified by [requestId] - not just a
    // session id, so that even a second prompt opened for the SAME session can never be
    // resolved by a reply aimed at an earlier, already-abandoned one. [deferred] is
    // completed exactly once, by the reply path in processQuery, and ONLY if it is still
    // the value installed here (re-validated at completion time, not just when the reply
    // was scheduled) - there is deliberately no buffering flow a stale reply could sit in
    // and later be picked up by some unrelated future prompt. See processQuery.
    private data class PendingUserInput(
        val sessionId: String,
        val requestId: String,
        val deferred: CompletableDeferred<String>
    )

    @Volatile private var pendingUserInput: PendingUserInput? = null

    /**
     * v1.6.0 (field P0-8e): needs-input prompts spent by the CURRENT plan -
     * reset at every executePlanLoop start, capped per-plan (the per-action
     * cap of 5 never bit in the field; the loop cycled ACROSS steps).
     */
    @Volatile private var needsInputPromptsThisPlan = 0

    /**
     * v1.6.0 (field P2-5): wall-clock origin of the plan currently running,
     * so the plan path's final summary can carry turnWallMs like the chat
     * path does (the field report could not account for ~9 minutes of a
     * 17-minute turn).
     */
    @Volatile private var activePlanWallStartAt = 0L

    // Session id of whichever task is currently parked inside awaitUserResponse(), or null
    // when nothing is waiting. Session-affiliated (not a bare boolean) so processQuery can
    // tell a genuine reply to THIS prompt (arriving from the same session) apart from an
    // unrelated new message that happens to arrive in some other chat while this prompt is
    // still open - see processQuery.
    @Volatile private var waitingSessionId: String? = null

    // v1.3.0 round-7: true from the moment a plan-path turn asks the user
    // anything (ASK_USER action, or a NeedsInput re-ask) until the plan's
    // summary is saved. speakAndSaveSummary consumes it to route the final
    // reply through an LLM confirmation that QUOTES the user's answer —
    // the opencode question-tool round-trip. Run-102 cap21 evidence: without
    // it the plan's ask step completed, "Pune" sat in the step result, and
    // humanizeGoalDone swallowed it into "All done!".
    @Volatile private var askedUserDuringPlan = false

    // v1.3.0 ask_user: the LIVE question surface — the question the agent is
    // currently waiting on the user to answer (with tappable options), scoped
    // to the session whose task is parked inside awaitUserResponse(). Drives
    // the answer-mode input bar + option chips in the chat UI. Null when no
    // ask is pending anywhere.
    data class PendingAsk(
        val sessionId: String,
        val question: String,
        val options: List<String>
    )

    private val _pendingAsk = MutableStateFlow<PendingAsk?>(null)
    val pendingAsk: StateFlow<PendingAsk?> = _pendingAsk.asStateFlow()

    val isWaitingForUserInput: Boolean
        get() = waitingSessionId != null

    /**
     * Suspends until the user answers a prompt this task just posted. Defaults to
     * whichever session the currently running task is pinned to (activeTaskSessionId) -
     * correct for every existing internal caller, since a call to this function only ever
     * happens from within that task's own execution. Pass a session explicitly only if
     * that assumption doesn't hold.
     */
    suspend fun awaitUserResponse(sessionId: String = activeTaskSessionId): String {
        val request = PendingUserInput(sessionId, UUID.randomUUID().toString(), CompletableDeferred())
        waitingSessionId = sessionId
        pendingUserInput = request
        try {
            return request.deferred.await()
        } finally {
            // Only clear state that is still ours - a newer prompt for the same (or a
            // different) session, or an explicit abandon/cancel, may already have
            // replaced or cleared it, and clobbering that would be wrong.
            if (pendingUserInput === request) {
                pendingUserInput = null
            }
            if (waitingSessionId == sessionId) {
                waitingSessionId = null
            }
        }
    }

    fun setAgentState(state: AgentState) {
        _agentState.value = state
    }

    /**
     * v1.3.0 ask_user execution: post the question as a real chat bubble
     * (with its tappable options), publish it as the live pending ask, then
     * park THIS task on [awaitUserResponse] until the user answers. The
     * answer returns to the harness as the tool's result and the turn
     * continues with it in context — the opencode "question" tool contract.
     *
     * Public surface for the ASK_USER ACTION (the plan path): it used to show
     * a transient Toast and wait invisibly — the question never appeared in
     * the chat at all (E2E cap21 evidence). Both ask paths now share this
     * one implementation, so the ANSWER NEEDED surface renders regardless of
     * which pipeline asked.
     */
    suspend fun askUserQuestion(
        question: String,
        options: List<String>,
        sessionId: String = activeTaskSessionId
    ): String {
        // v1.3.0 PRE-ARM the answer routing BEFORE anything else: the ANSWER
        // NEEDED surface renders as soon as _pendingAsk is set below, and a
        // fast user (or an E2E driver) can type and send an answer in the
        // suspend-gap before awaitUserResponse installs the park — that
        // answer would otherwise register as a NEW QUERY and cancel this
        // very task. Arm waitingSessionId first; awaitUserResponse re-sets
        // the same value microseconds later, and its finally-clause clears
        // it again if the ask never reaches the park.
        waitingSessionId = sessionId
        // v1.3.0 round-7: the plan-path variant of this ask must be answered
        // in the plan's final summary — flag it for speakAndSaveSummary.
        askedUserDuringPlan = true
        val askMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = question,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "ask_user",
            mode = "AGENT",
            askOptionsJson = if (options.isNotEmpty()) {
                com.tsfdroid.ai.data.models.serializeAskOptions(
                    com.tsfdroid.ai.data.models.AskOptions(options = options)
                )
            } else {
                null
            }
        )
        conversationRepository.insertMessage(sessionId, askMsg)
        memoryManager.storeMessage(askMsg, sessionId)
        _pendingAsk.value = PendingAsk(sessionId, question, options)
        try {
            val answer = awaitUserResponse(sessionId)
            // v1.3.0 round-8: the ask answer is often a durable preference
            // ("Which city do you prefer?" -> "Pune") — exactly what the
            // Hermes memory is for. The normal learnFromExchange hook fires
            // at the end of a chat turn, but the answer path returns before
            // it, so ask answers were never learned. Background, never
            // blocks, never fails the ask.
            if (answer.isNotBlank()) {
                scope.launch {
                    runCatching {
                        memoryLearner.learnFromExchange(
                            userText = "The assistant asked me: \"$question\"\nMy answer: $answer",
                            assistantText = question
                        )
                    }
                }
            }
            return answer
        } finally {
            _pendingAsk.value = null
            // If the ask was cancelled BEFORE the park installed, nothing
            // else would clear the pre-armed flag — clear it here so the
            // session's next message is not misrouted into a dead ask.
            if (pendingUserInput == null && waitingSessionId == sessionId) {
                waitingSessionId = null
            }
        }
    }

    private suspend fun handleAskUser(
        question: String,
        options: List<String>,
        sessionId: String
    ): String = askUserQuestion(question, options, sessionId)

    // Speak callback to be implemented by TTS service
    var onSpeakCallback: ((String) -> Unit)? = null

    /**
     * @param explicitSessionId The session this message actually belongs to (whatever chat
     * the caller was looking at when the user hit send), if the caller can determine it.
     * Used to decide whether this message answers a pending awaitUserResponse() prompt -
     * see below - and, for a genuinely new query, which session it's stored in. Callers
     * that cannot determine this (e.g. voice input via OpenDroidService, which has no
     * notion of "which chat" a spoken query belongs to) pass null, which preserves the
     * pre-multi-session behavior: a null session is always assumed to answer whatever
     * prompt is currently pending, and otherwise falls back to resolving "current".
     */
    fun processQuery(
        query: String,
        context: Context,
        explicitSessionId: String? = null,
        /** v1.2.0: JSON [MessageAttachments] the user uploaded with this message. */
        attachmentsJson: String? = null
    ) {
        // v1.0.6: capture the application context for artifact-card emission
        // and the chat tool loop (Context is not threaded everywhere).
        appContext = context.applicationContext
        // processQuery is also the delivery path for a reply to a pending
        // awaitUserResponse() prompt (see below). That's ONLY true when this message comes
        // from the SAME session the waiting task is pinned to (waitingSessionId) - a
        // message from a different session is a genuinely new query, never an answer to
        // some other chat's prompt, no matter how it compares to the global "is anything
        // waiting" state.
        val waitingSession = waitingSessionId
        // Snapshot of the exact prompt that is pending right now, at the moment this
        // reply was scheduled. The reply is only ever allowed to resolve THIS SAME
        // instance - re-checked below, inside the launched coroutine, right before
        // completing it - never whatever happens to be pending by the time that
        // coroutine actually runs.
        val pendingAtScheduleTime = pendingUserInput
        val isAnsweringPendingQuestion = waitingSession != null &&
                (explicitSessionId == null || explicitSessionId == waitingSession)

        if (waitingSession != null && !isAnsweringPendingQuestion) {
            // A brand-new message arrived in a different session while another task is
            // still parked on a prompt in `waitingSession`. Nobody there is ever going to
            // answer that prompt now - kill it and leave a short, honest message in ITS OWN
            // session saying it stopped waiting, rather than either routing this message
            // into the wrong chat or leaving that chat hanging silently forever.
            abandonWaitingTask(waitingSession)
        } else if (!isAnsweringPendingQuestion) {
            currentJob?.cancel()
        }

        val job = scope.launch {
            try {
                // Pin the session THIS task writes to, resolved once, right here, before any
                // message is written. A genuinely new query pins whatever session the caller
                // says this message belongs to (falling back to whatever is current if the
                // caller couldn't say); a reply to a pending prompt reuses the session the
                // task it's replying to already pinned via activeTaskSessionId - it must NOT
                // re-resolve "current", since the user may have switched (or created) chats
                // while that task was still waiting on them.
                val sessionId = if (isAnsweringPendingQuestion) {
                    activeTaskSessionId
                } else {
                    (explicitSessionId ?: conversationRepository.ensureCurrentSessionId())
                        .also { activeTaskSessionId = it }
                }

                if (!isAnsweringPendingQuestion) {
                    // This is a genuinely new task. If some OTHER session still has an
                    // unresolved plan proposal sitting in agentState/planManager, resolve
                    // it explicitly right now, before anything below can silently
                    // overwrite it out from under that chat - see resolveStaleProposedPlan.
                    resolveStaleProposedPlan(sessionId)
                }

                // Only capture the screen when the query actually asks about it.
                // Attaching a screenshot to every request would silently send
                // whatever is on screen (messages, banking apps, ...) to the LLM.
                val screenshotBase64 = if (needsScreenContext(query)) {
                    com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()?.takeScreenshotAndEncode()
                } else {
                    null
                }

                // Save user message
                // v1.6.0 (field P2-6): the mode this turn runs in rides the
                // USER message too - the next field analysis must never have
                // to guess CHAT vs AGENT again.
                val userChatMode = runCatching {
                    ChatMode.fromNullable(settingsRepository.llmConfig.first().chatMode)
                }.getOrDefault(ChatMode.CHAT)
                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = query,
                    sender = ChatMessage.Sender.USER,
                    modelBadge = null,
                    imageBase64 = screenshotBase64,
                    attachmentsJson = attachmentsJson,
                    mode = userChatMode.name
                )
                memoryManager.storeMessage(userMsg, sessionId)
                conversationRepository.insertMessage(sessionId, userMsg)

                if (isAnsweringPendingQuestion) {
                    // Re-validate at delivery time, not just at schedule time: only
                    // complete the EXACT prompt this reply was aimed at (matched by
                    // requestId, not merely by session). If it's gone - already answered,
                    // abandoned, or superseded by a newer prompt in the meantime - there
                    // is nothing to buffer it for; just drop it.
                    //
                    // v1.3.0 pre-arm grace: an ask arms waitingSessionId BEFORE the
                    // question bubble is posted, and the park (pendingUserInput)
                    // installs a few suspends later. An answer sent inside that
                    // window arrives here with a null current pending - it would be
                    // silently DROPPED and the asking task would wait forever. Poll
                    // briefly for the park to materialize before giving up (E2E
                    // cap21 round-101 evidence: the race is real and user-hittable).
                    var pending = pendingUserInput
                    if (pending == null || pending.requestId != pendingAtScheduleTime?.requestId) {
                        val graceDeadline = System.currentTimeMillis() + 2_500
                        while (pending == null && System.currentTimeMillis() < graceDeadline) {
                            runCatching { kotlinx.coroutines.delay(50) }
                            pending = pendingUserInput
                        }
                    }
                    // Accept the EXACT scheduled prompt; when the schedule-time
                    // snapshot was null (the pre-arm window — the park installed
                    // only after this reply was sent), accept THIS session's
                    // freshly-installed park: it can only be the ask the user was
                    // answering.
                    if (pending != null &&
                        (
                            pending.requestId == pendingAtScheduleTime?.requestId ||
                                (pendingAtScheduleTime == null && pending.sessionId == sessionId)
                            )
                    ) {
                        pending.deferred.complete(query)
                    }
                    return@launch
                }

                // Serialize query handling so contextual follow-up state cannot race.
                queryMutex.withLock {
                    processQueryLocked(userMsg, query, context, sessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // v1.2.1 round-16: this catch used to kill the turn in total
                // silence — no log line, no card — which is how a turn could
                // "vanish" after its last model call (cap15's 17-minute
                // mystery window had this as a live suspect). Speak up.
                android.util.Log.w(
                    "AgentLoop",
                    "processQuery failed: ${e.javaClass.simpleName}: ${e.localizedMessage}",
                    e
                )
                _agentState.value = AgentState.Error(e.localizedMessage ?: "Unknown processing error")
            }
        }

        if (!isAnsweringPendingQuestion) {
            currentJob = job
        }
    }

    /**
     * Re-executes a previously failed request after the user taps Retry on its error
     * card. Mirrors processQuery's new-task path, but reuses the user message already
     * persisted for [requestId] in [sessionId] instead of inserting a duplicate bubble -
     * and always runs in the error's own session, never wherever the user is looking.
     * Falls back to the session's last user message if [requestId] doesn't resolve to
     * one (e.g. a plan re-evaluation failure, whose requestId is a plan id).
     */
    fun retryRequest(requestId: String, sessionId: String, context: Context) {
        val waitingSession = waitingSessionId
        if (waitingSession != null && waitingSession != sessionId) {
            abandonWaitingTask(waitingSession)
        } else {
            currentJob?.cancel()
        }

        val job = scope.launch {
            try {
                activeTaskSessionId = sessionId
                resolveStaleProposedPlan(sessionId)

                val messages = conversationRepository.getMessages(sessionId).first()
                val userMsg = messages.lastOrNull {
                    it.id == requestId && it.sender == ChatMessage.Sender.USER
                } ?: messages.lastOrNull { it.sender == ChatMessage.Sender.USER } ?: return@launch

                queryMutex.withLock {
                    processQueryLocked(userMsg, userMsg.text, context, sessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agentState.value = AgentState.Error(e.localizedMessage ?: "Unknown processing error")
            }
        }
        currentJob = job
    }

    private suspend fun processQueryLocked(userMsg: ChatMessage, query: String, context: Context, sessionId: String) {
                // Each new task starts with a clean slate: a stale error card from an
                // earlier request must not outlive the request it described.
                _chatError.value = null
                _agentState.value = AgentState.Thinking

                // v1.2.1 round-10: every new task starts a FRESH visible-step
                // trace. The snapshot previously leaked across turns — stale
                // steps from an earlier turn (a) rendered on the wrong
                // message's ACTIVITY section and (b) made the research
                // guarantee believe THIS turn had already run tools (its
                // zero-tool-events check read the polluted snapshot), skipping
                // the forced search and shipping a memory-answer with no trace
                // (cap15 failed both passes of run 36629953091 this way).
                _activitySteps.value = emptyList()

                // v1.4.0: artifact cards are TURN-SCOPED — a file collected
                // by a turn that died before its reply save must never ride
                // the NEXT turn's final message.
                collectedArtifacts.clear()

                // v1.2.0 CHAT MODE: the read-only conversational mode skips the
                // whole action-routing cascade (complexity → alias shortcuts →
                // LLM intent router) — chat mode has no device actions to route,
                // and skipping the LLM router removes a full round trip per
                // message (the latency complaint). Everything goes straight to
                // the harness chat path with read-only tools.
                val chatMode = ChatMode.fromNullable(
                    runCatching { settingsRepository.llmConfig.first().chatMode }.getOrNull()
                )
                if (chatMode == ChatMode.CHAT) {
                    executeSimpleQuery(userMsg, sessionId)
                    return
                }

                // 0. Check if this is a complex, multi-step query
                //    If so, skip ALL shortcuts and let the LLM planner handle it properly
                val complexity = intentClassifier.classifyComplexity(query)
                val isMultiStep = complexity != QueryComplexity.SIMPLE

                // 1. Alias resolution — bypass LLM for simple, single-action commands ONLY
                if (!isMultiStep) {
                    val lastExecuted = lastExecutedActionsBySession[sessionId]
                    val contextual = AliasResolver.resolveContextual(query, lastExecuted?.action, lastExecuted?.params)
                    if (contextual != null) {
                        executeAliasDirect(contextual, query, context, sessionId)
                        return
                    }

                    val alias = AliasResolver.resolve(query)
                    if (alias != null) {
                        executeAliasDirect(alias, query, context, sessionId)
                        return
                    }

                    // 1b. Alarm shortcut — bypass LLM for simple alarm requests ONLY
                    if (AliasResolver.isAlarmRequest(query)) {
                        val timeStr = AliasResolver.extractAlarmTime(query)
                        if (timeStr != null) {
                            val alarmHint = AliasResolver.ActionHint(
                                "SET_ALARM",
                                mapOf("time" to timeStr, "label" to "Alarm")
                            )
                            executeAliasDirect(alarmHint, query, context, sessionId)
                            return
                        }
                    }

                    // 1c. Timer shortcut — bypass LLM for simple timer requests ONLY
                    if (AliasResolver.isTimerRequest(query)) {
                        val durationSecs = AliasResolver.extractTimerDuration(query)
                        if (durationSecs != null) {
                            val timerHint = AliasResolver.ActionHint(
                                "SET_TIMER",
                                mapOf("duration" to durationSecs.toString(), "label" to "Timer")
                            )
                            executeAliasDirect(timerHint, query, context, sessionId)
                            return
                        }
                    }

                    // 1d. Read & Remember screen shortcut — bypass LLM for screen reading/saving
                    if (AliasResolver.isReadAndRememberRequest(query)) {
                        val topic = AliasResolver.extractTopicForReadAndRemember(query)
                        val readAndRememberHint = AliasResolver.ActionHint(
                            "READ_AND_REMEMBER_SCREEN",
                            mapOf("topic" to topic, "save_as" to "note")
                        )
                        executeAliasDirect(readAndRememberHint, query, context, sessionId)
                        return
                    }

                    // 1e. Recall memory shortcut — bypass LLM for querying saved notes/memory
                    if (AliasResolver.isRecallMemoryRequest(query)) {
                        val recallQuery = AliasResolver.extractRecallQuery(query)
                        val recallHint = AliasResolver.ActionHint(
                            "RECALL_MEMORY",
                            mapOf("query" to recallQuery)
                        )
                        executeAliasDirect(recallHint, query, context, sessionId)
                        return
                    }
                }

                // 2. Intent Classification
                val requiresAction = intentClassifier.requiresAction(query)
                if (requiresAction) {
                    generatePlan(userMsg, context, sessionId)
                } else {
                    executeSimpleQuery(userMsg, sessionId)
                }
    }

    /**
     * Execute an alias-resolved command directly, bypassing the LLM.
     * Builds a single-step Plan and runs it through the normal plan execution pipeline.
     */
    private suspend fun executeAliasDirect(
        alias: AliasResolver.ActionHint,
        originalQuery: String,
        context: Context,
        sessionId: String
    ) {
        try {
            val speechText = humanizePreSpeech(alias.action)
            onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(speechText))

            // Save agent response
            val replyMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = speechText,
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "alias"
            )
            conversationRepository.insertMessage(sessionId, replyMsg)
            memoryManager.storeMessage(replyMsg, sessionId)

            // Build a single-step plan from the alias
            val plan = buildSingleStepPlan(originalQuery, alias.action, alias.baseParams)

            planManager.startNewPlan(plan, context, PlanStatus.PROPOSED)
            val approval = settingsRepository.llmConfig.first().approvalSettings()
            if (AutoApprovalPolicy.shouldAutoApprove(approval.mode, approval.grantedActions, plan)) {
                executePlanLoop(plan, context, sessionId, autoApproved = true)
            } else {
                proposePlan(plan, sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _agentState.value = AgentState.Error("Alias execution failed: ${e.localizedMessage}")
        }
    }

    fun dismissChatError() {
        _chatError.value = null
    }

    /**
     * v1.3.0 round 21 (the 2026-10-03 field evidence, screenshot 20:16): the
     * green "Speaking:" status line showed literal markdown — "Speaking:
     * **Taparia Tools Ltd (BSE: 5056…" — and the phone's TTS SPOKE the
     * asterisks. EVERY text that reaches speech or a speech-shaped status
     * surface goes through [SpeechText.forSpeech] first: the words survive,
     * the markup does not, and URLs become the word "link".
     */
    private fun announce(text: String) {
        val clean = com.tsfdroid.ai.core.util.SpeechText.forSpeech(text)
        _agentState.value = AgentState.Speaking(clean)
        onSpeakCallback?.invoke(clean)
    }

    private fun publishChatError(error: ChatErrorUiState) {
        _chatError.value = error
        _agentState.value = AgentState.Error(error.title())
    }

    private suspend fun executeSimpleQuery(userMsg: ChatMessage, sessionId: String) {
        val runId = UUID.randomUUID().toString()
        val requestId = userMsg.id
        // v1.6.0 (field P2-5): the turn's wall-clock origin - every phase
        // (streaming, harness, expansion, continuation) is accounted for in
        // turnWallMs at the final save.
        val turnStartWallAt = System.currentTimeMillis()
        try {
            val provider = llmProviderFactory.getActiveProvider()
            val relevantContext = memoryManager.getRelevantContext(userMsg.text)
            val config = settingsRepository.llmConfig.first()
            val mode = ChatMode.fromNullable(config.chatMode)
            val autoModeLabel = config.approvalSettings().mode.name
            val appCtx = contextOrNull() ?: run {
                _agentState.value = AgentState.Idle
                return
            }

            // v1.2.0: the harness system prompt — mode-aware, carrying the tool
            // contract, the continuation protocol, and the artifact quality bar.
            val systemPrompt = HarnessPrompts.harnessSystemPrompt(
                mode = mode,
                autoModeLabel = autoModeLabel,
                relevantContext = relevantContext,
                dateTimeLine = currentDateTimeLine()
            )

            // v1.2.1 CONTEXT COMPACTION — the OpenCode 75% rule: before the
            // turn, the assembled prompt is measured against the active model's
            // registry context window; at 75% the older history is summarized
            // into a dense context note (recent messages stay verbatim) and a
            // visible "Compacted conversation history" step is published. Long
            // projects and long chats never hit the wall or silently forget.
            // v1.3.0: capability lookups (context window, modalities) use the
            // ALL-provider metadata map — the Zen-only spec map is empty for
            // every other provider, which silently lied about their windows.
            val activeModelId = config.selectedModelFor(config.activeProvider)
            val modelSpec = runCatching { modelsDevRegistry.modelInfo()[activeModelId] }.getOrNull()
            val onDeviceProvider = config.activeProvider.contains("device", ignoreCase = true) ||
                config.activeProvider.contains("gemma", ignoreCase = true) ||
                config.activeProvider.contains("litert", ignoreCase = true)

            // v1.2.0 shared context, v1.3.0 TOKEN-AWARE: up to 200 messages ride
            // along, trimmed to the model's real context budget in TOKEN units
            // (60% of the registry window, images at their token weight, on-device
            // models capped hard — they live at 4k windows). The old fixed
            // 30-message cap silently amputated long conversations on
            // big-context models. The 75% compaction rule stays as the far
            // guard: 60% history + system + attachments can still cross it on
            // heavy turns, and compaction SUMMARIZES instead of dropping — the
            // opencode "never lose potential" order of operations.
            val historyBudgetTokens = historyBudgetFor(modelSpec, onDeviceProvider)
            var lastMsgs = trimHistoryToTokenBudget(
                conversationRepository.getLastMessages(sessionId, CHAT_HISTORY_WINDOW_MAX).map { msg ->
                    val withUploads = if (msg.id == userMsg.id) {
                        msg.copy(
                            imageBase64 = userMsg.imageBase64,
                            attachmentsJson = userMsg.attachmentsJson
                        )
                    } else {
                        msg
                    }
                    if (incompleteMessageIds.contains(withUploads.id) &&
                        withUploads.sender == ChatMessage.Sender.AGENT
                    ) {
                        withUploads.copy(text = "[incomplete assistant reply]\n${withUploads.text}")
                    } else {
                        withUploads
                    }
                },
                historyBudgetTokens
            )

            // v1.2.0 vision routing, v1.3.0 ACTUALLY ROUTED: when the turn (or
            // recent history) carries images, requests must reach a model that
            // can see them. Three honest outcomes, checked in order:
            //   1. the ACTIVE model sees images (or its capabilities are
            //      unknown — trust the user's pin) → send directly;
            //   2. the active model is blind but the provider can route (the Zen
            //      vision chain) → set requireVision and let the chain pick a
            //      vision-capable model — the flag existed since v1.2.0 but NO
            //      caller ever set it, AND the `is` check was always false
            //      because every provider is wrapped (fixed via rawProvider);
            //   3. no routing possible → degrade images to an honest text note.
            val turnHasImages = lastMsgs.any { it.allImages().isNotEmpty() }
            var visionRoutingRequired = false
            if (turnHasImages) {
                val activeSeesImages = modelSpec?.inputModalities?.contains("image") ?: true
                val rawProvider = (provider as? com.tsfdroid.ai.core.llm.WrappedLLMProvider)?.rawProvider ?: provider
                when {
                    activeSeesImages -> Unit
                    rawProvider is com.tsfdroid.ai.core.llm.providers.OpenCodeZenProvider &&
                        harnessLoop.hasVisionSupport() -> visionRoutingRequired = true
                    else -> lastMsgs = lastMsgs.map(::degradeImagesToNote)
                }
            }

            // v1.2.1: the 75% compaction pass (uses the vision-routed history).
            // v1.3.0: on-device models have no models.dev spec, so the compactor
            // gets the Gemma-class 4096 window as its reference — the 1000-token
            // history budget keeps the assembly far below it, compaction stays
            // the far guard rather than being silently disabled.
            val compaction = ContextCompactor(provider)
                .compactIfNeeded(
                    systemPrompt, lastMsgs,
                    modelSpec?.contextWindow ?: if (onDeviceProvider) 4_096 else null
                )
            if (compaction.compacted) {
                publishStep(
                    ActivityStep(
                        kind = ActivityStep.KIND_COMPACTION,
                        label = "Compacted conversation history",
                        detail = "${compaction.tokensBefore} -> ${compaction.tokensAfter} estimated tokens " +
                            "(75% context rule); older messages summarized, recent kept"
                    )
                )
            }
            lastMsgs = compaction.messages

            val replyId = UUID.randomUUID().toString()
            var currentReplyText = ""
            var currentThinkingText = ""
            var inserted = false
            var lastDbWriteAt = 0L
            var lastFinishReason: String? = null
            // v1.4.0 chat export: the FULL per-turn tool-call log (raw args,
            // mapped params, capped results, durations) collected from every
            // harness path this turn takes (handoff, research guarantee,
            // continuation, forced search) and persisted on the final reply.
            val turnToolRecords =
                java.util.Collections.synchronizedList(mutableListOf<ToolCallRecord>())
            // v1.4.0 chat export: usage of the harness phase, when it ran.
            var turnTokensUsed: Int? = null
            var turnLatencyTotal: Long? = null
            // v1.3.0: thinking-duration measurement for the Claude-style
            // "Thought for X seconds" header — from the first reasoning delta
            // to the first content delta (the visible thinking phase).
            var firstReasoningAt = 0L
            var firstContentAt = 0L
            val replyMsg = ChatMessage(
                id = replyId,
                text = currentReplyText,
                sender = ChatMessage.Sender.AGENT,
                modelBadge = provider.name,
                // v1.4.0 chat export: the concrete model id behind the badge —
                // the machine-readable identity the export carries.
                modelId = activeModelId
            )

            // v1.0.5: DB writes are throttled — reasoning deltas can arrive
            // dozens per second and every write rewrites the message row.
            // Content deltas always flush immediately (the visible reply is
            // the deliverable); thinking flushes at most every 300ms.
            suspend fun persistReply(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - lastDbWriteAt < 300) return
                lastDbWriteAt = now
                conversationRepository.insertMessage(
                    sessionId,
                    replyMsg.copy(
                        text = currentReplyText,
                        thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                        // v1.4.0 chat export: the running tool-call log rides
                        // every throttled write so an interrupted turn still
                        // carries whatever executed before the interruption.
                        toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList())
                    )
                )
                inserted = true
            }

            // v1.2.0: one shared harness config for every call this turn makes.
            // maxTokens = the model's REAL output capability (OpenCode's 32k
            // ceiling), registry-clamped per model by the provider — never an
            // artificial 4k/8k app-side cut again.
            val turnConfig = HarnessLoop.TurnConfig(
                systemPrompt = systemPrompt,
                history = lastMsgs,
                tools = chatToolsFor(mode),
                context = appCtx,
                readOnly = mode == ChatMode.CHAT,
                temperature = 0.4f,
                maxTokens = HarnessLoop.OUTPUT_TOKEN_MAX,
                reasoningEffort = config.reasoningEffort,
                requireVision = visionRoutingRequired,
                onAskUser = { question, options ->
                    handleAskUser(question, options, sessionId)
                },
                onArtifact = { action, params, result ->
                    emitArtifactCardIfNeeded(action, params, result, sessionId)
                },
                onToolEvent = { action, success, detail ->
                    publishStep(
                        ActivityStep(
                            kind = ActivityStep.KIND_TOOL,
                            label = action,
                            detail = detail,
                            status = if (success) ActivityStep.STATUS_DONE else ActivityStep.STATUS_ERROR
                        )
                    )
                },
                onContinuation = { part ->
                    publishStep(
                        ActivityStep(
                            kind = ActivityStep.KIND_CONTINUATION,
                            label = "Answer continued (auto)",
                            detail = "Output limit reached — the answer flows across calls (part $part)"
                        )
                    )
                },
                // v1.4.0 chat export: full tool-call records from every harness
                // path this turn takes — the debugging log behind the export.
                onToolRecord = { record -> turnToolRecords.add(record) }
            )

            // v1.2.1 round-10/11: the STREAMED first call is bounded by the
            // IDLE GAP between deltas, not the total duration (round-10 field
            // evidence, run 36640751137: free-tier reasoning models trickle —
            // one token every few seconds keeps every 300s read-timeout quiet
            // while the user stares at a half-answer for a quarter of an
            // hour). A healthy long answer keeps emitting and is never cut;
            // a 120s silence means the stream is stalled — the partial stays
            // on screen and the harness finishes the turn below.
            try {
                provider.streamCompleteDetailed(
                        LLMRequest(
                            systemPrompt = systemPrompt,
                            messages = lastMsgs,
                            temperature = 0.4f,
                            // v1.2.0: the model's REAL output capability — no more
                            // artificial app-side truncation. The provider clamps
                            // to each model's registry max_output/context window.
                            maxTokens = HarnessLoop.OUTPUT_TOKEN_MAX,
                            responseFormat = ResponseFormat.TEXT,
                            // v1.0.6: chat turns may answer with tool calls — when
                            // they do, the streamed reply stays blank and the
                            // harness below executes the calls natively.
                            allowToolCalls = true,
                            tools = chatToolsFor(mode),
                            reasoningEffort = config.reasoningEffort,
                            // v1.3.0: the routed vision flag — blind pins now
                            // ride the Zen vision chain instead of ignoring
                            // the images entirely.
                            requireVision = visionRoutingRequired
                        )
                    ).timeout(STREAM_IDLE_TIMEOUT_MS.milliseconds)
                    .collect { event ->
                        when (event) {
                            is LLMStreamEvent.Content -> {
                                if (event.text.isEmpty()) return@collect
                                if (firstContentAt == 0L) firstContentAt = System.currentTimeMillis()
                                currentReplyText += event.text
                                persistReply(force = true)
                            }
                            is LLMStreamEvent.Reasoning -> {
                                if (firstReasoningAt == 0L) firstReasoningAt = System.currentTimeMillis()
                                currentThinkingText = AnswerHygiene.joinThinkingSegments(currentThinkingText, event.text)
                                _liveThinking.value = currentThinkingText.takeLast(LIVE_THINKING_TAIL)
                                persistReply(force = false)
                            }
                            // v1.2.0: the finish signal the continuation loop keys on.
                            is LLMStreamEvent.Finished -> lastFinishReason = event.reason
                        }
                    }
            } catch (timedOut: TimeoutCancellationException) {
                // NOT a user cancel: the stream went silent past the idle
                // bound. Whatever streamed stays on screen; the flow below
                // (research guarantee / harness fallback) finishes the turn.
                android.util.Log.w(
                    "AgentLoop",
                    "streamed first call went idle past ${STREAM_IDLE_TIMEOUT_MS / 1000}s — handing the turn to the harness"
                )
            } catch (streamError: CancellationException) {
                _liveThinking.value = null
                if (inserted && currentReplyText.isNotBlank()) {
                    // This coroutine is already cancelled; without NonCancellable the
                    // suspend insert would abort immediately and the "Stopped" partial
                    // would never persist.
                    withContext(NonCancellable) {
                        conversationRepository.insertMessage(
                            sessionId,
                            replyMsg.copy(
                                text = currentReplyText,
                                modelBadge = "Stopped",
                                thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                                // v1.4.0 chat export: the stopped partial keeps
                                // whatever tool calls completed before the stop.
                                toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList())
                            )
                        )
                    }
                }
                throw streamError
            } catch (streamError: LLMException) {
                _liveThinking.value = null
                // v1.1.1: a MALFORMED_RESPONSE with nothing on screen yet (no
                // partial text) must not greet a casual chat turn with the
                // scary red error card — the screenshot field failure. The
                // harness below already knows how to recover tool-call
                // answers; give the turn one more shot there, and only fall
                // back to a plain conversational snag message when even that
                // cannot produce an answer. Partial text + other errors keep
                // the actionable error card (Retry/Dismiss).
                if (streamError.error == LLMError.MalformedResponse && currentReplyText.isBlank()) {
                    val harnessTurn = harnessFallbackTurn(provider, turnConfig, lastMsgs)
                    val harnessAnswer = harnessTurn?.content
                    if (!harnessAnswer.isNullOrBlank()) {
                        // v1.4.0 chat export: usage from this recovery path.
                        turnTokensUsed = harnessTurn?.tokensUsed?.takeIf { it > 0 }
                        turnLatencyTotal = harnessTurn?.latencyMs?.takeIf { it > 0 }
                        // v1.2.1 round-17 (run 36698428126, BOTH passes failed
                        // cap15 the same way): the streamed first call of a
                        // research ask comes back as ONLY tool calls + zero
                        // content — the wrapper's retry envelope maps that to
                        // MalformedResponse (the 3-pump retry signature in the
                        // logcat) and this branch runs. The harness then
                        // executes the searches, publishes the steps
                        // (publishStep logs prove it: tool/WEB_SEARCH x2), and
                        // returns the grounded answer — but THIS save, unlike
                        // the blank-reply handoff and the final save, dropped
                        // stepsJson. The live in-bubble trace only renders
                        // while the state is Thinking/Speaking, which ends
                        // seconds after the answer lands (TTS completion ->
                        // Idle) — BEFORE the turn's reply detection settles.
                        // Net effect: neither the live trace nor a persisted
                        // ACTIVITY section ever existed on the final bubble.
                        // Same contract as every other reply save: the work
                        // the user just watched must survive on the answer.
                        val loopSteps = currentStepsWithThinking(
                            if (firstReasoningAt > 0L) {
                                (if (firstContentAt > 0L) firstContentAt else System.currentTimeMillis()) - firstReasoningAt
                            } else {
                                0L
                            }
                        )
                        val loopStepsEncoded =
                            com.tsfdroid.ai.core.harness.ActivitySteps.encode(loopSteps)
                        android.util.Log.i(
                            "AgentLoop",
                            "malformed-stream harness reply save: steps=${loopSteps.size} " +
                                "jsonLen=${loopStepsEncoded?.length ?: -1} id=$replyId"
                        )
                        val loopMsg = replyMsg.copy(
                            text = harnessAnswer,
                            timestamp = System.currentTimeMillis(),
                            thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                            // v1.2.1: persist the visible step trace on the reply.
                            stepsJson = loopStepsEncoded,
                            // v1.4.0 chat export: measured thinking phase + the
                            // full tool-call log on this recovery path too.
                            thinkingDurationMs = if (firstReasoningAt > 0L) {
                                (if (firstContentAt > 0L) firstContentAt else System.currentTimeMillis()) - firstReasoningAt
                            } else {
                                null
                            },
                            toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList()),
                            tokensUsed = turnTokensUsed,
                            turnLatencyMs = turnLatencyTotal
                        )
                        conversationRepository.insertMessage(sessionId, loopMsg)
                        memoryManager.storeMessage(loopMsg, sessionId)
                        _chatError.value = null
                        announce(harnessAnswer)
                        return
                    }
                    // v1.2.1 round-17: the snag message carries the same trace —
                    // when tools DID run before the loop gave out, showing the
                    // failed work is the honest surface, same as a succeeded turn.
                    val snagMsg = replyMsg.copy(
                        text = "I hit a snag completing that one — the model's reply came back " +
                            "in a shape I couldn't use. Please try again in a moment.",
                        thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                        stepsJson = com.tsfdroid.ai.core.harness.ActivitySteps.encode(
                            currentStepsSnapshot()
                        ),
                        // v1.4.0 chat export: the failed work is recorded too.
                        toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList())
                    )
                    conversationRepository.insertMessage(sessionId, snagMsg)
                    memoryManager.storeMessage(snagMsg, sessionId)
                    _chatError.value = null
                    _agentState.value = AgentState.Idle
                    return
                }
                val partialId = if (inserted && currentReplyText.isNotBlank()) {
                    incompleteMessageIds.add(replyId)
                    conversationRepository.insertMessage(
                        sessionId,
                        replyMsg.copy(
                            text = currentReplyText,
                            // v1.4.0 chat export: partial keeps its executed calls.
                            toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList())
                        )
                    )
                    replyId
                } else {
                    null
                }
                publishChatError(
                    ChatErrorUiState.fromException(
                        sessionId = sessionId,
                        requestId = requestId,
                        runId = runId,
                        failure = streamError,
                        partialMessageId = partialId
                    )
                )
                return
            }
            _liveThinking.value = null

            // v1.3.0: the measured thinking phase of this turn, rendered as a
            // leading "Thought for X" step (Claude-style) on every save path.
            // v1.3.0 round-8: var — the harness-fallback phases (tool loop,
            // forced search) extend it below, so those bubbles render
            // "THOUGHT FOR Xs" too instead of a bare THINKING header (the
            // round-3 critic's also-noted: harness-path duration was
            // unmeasured because firstReasoningAt/firstContentAt only move
            // in the stream collector).
            var thinkingDurationMs = if (firstReasoningAt > 0L) {
                (if (firstContentAt > 0L) firstContentAt else System.currentTimeMillis()) - firstReasoningAt
            } else {
                0L
            }

            /** v1.3.0: the step snapshot with the thinking-duration step prepended. */
            fun stepsSnapshotWithThinking(): List<ActivityStep> =
                currentStepsWithThinking(thinkingDurationMs)

            if (!inserted || currentReplyText.isBlank() ||
                // v1.2.1 round-6: a streamed reply that is pure internal
                // monologue ("Need to search… Let me search.") is the model
                // ANNOUNCING work instead of doing it — route the turn into
                // the harness tool loop; the final harness answer replaces
                // this bubble (same message id, Room REPLACE).
                (!lastFinishReason.isNullOrBlank() && lastFinishReason != HarnessLoop.FINISH_LENGTH &&
                    isMonologueShaped(currentReplyText))
            ) {
                // v1.0.6→v1.2.0: a blank streamed reply is usually the model
                // answering the harness contract with read/shell tool calls.
                // The harness executes the mappable tool calls through the
                // app's real action pipeline (with continuation support) and
                // finishes the turn with the model's grounded final answer.
                android.util.Log.i(
                    "AgentLoop",
                    "blank/monologue streamed reply (len=${currentReplyText.length}) — handing the turn to the harness"
                )
                val harnessStartAt = System.currentTimeMillis()
                val harnessTurn = harnessFallbackTurn(provider, turnConfig, lastMsgs)
                val harnessAnswer = harnessTurn?.content
                if (!harnessAnswer.isNullOrBlank()) {
                    // v1.4.0 chat export: usage stats from the harness phase.
                    turnTokensUsed = harnessTurn?.tokensUsed?.takeIf { it > 0 }
                    turnLatencyTotal = harnessTurn?.latencyMs?.takeIf { it > 0 }
                    // v1.3.0 round-8: the harness tool phase counts toward the
                    // thinking duration — Claude-style, tool time included.
                    val harnessDurationMs = thinkingDurationMs +
                        (System.currentTimeMillis() - harnessStartAt)
                    val handoffSteps = currentStepsWithThinking(harnessDurationMs)
                    val handoffEncoded = com.tsfdroid.ai.core.harness.ActivitySteps.encode(handoffSteps)
                    android.util.Log.i(
                        "AgentLoop",
                        "handoff reply save: steps=${handoffSteps.size} jsonLen=${handoffEncoded?.length ?: -1} id=$replyId"
                    )
                    val loopMsg = replyMsg.copy(
                        // v1.3.0: save-time stamp — must sort after any ask_user
                        // question bubble that landed mid-turn (ts ASC ordering).
                        timestamp = System.currentTimeMillis(),
                        text = harnessAnswer,
                        thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                        // v1.2.1: persist the visible step trace on the reply.
                        stepsJson = handoffEncoded,
                        // v1.4.0 chat export: measured thinking (incl. harness
                        // phase), usage, and the full tool-call log.
                        thinkingDurationMs = harnessDurationMs.takeIf { it > 0 },
                        toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList()),
                        tokensUsed = turnTokensUsed,
                        turnLatencyMs = turnLatencyTotal
                    )
                    conversationRepository.insertMessage(sessionId, loopMsg)
                    memoryManager.storeMessage(loopMsg, sessionId)
                    _chatError.value = null
                    announce(harnessAnswer)
                    scope.launch { memoryLearner.learnFromExchange(userMsg.text, harnessAnswer) }
                    return
                }
                // v1.0.6 (loop-16): a snagged tool loop must NOT surface the
                // scary "unreadable response" card in casual chat — deliver a
                // plain conversational retry prompt instead.
                val snagMsg = replyMsg.copy(
                    text = "I hit a snag completing that one — the model's reply came back " +
                        "in a shape I couldn't use. Please try again in a moment.",
                    thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                    // v1.4.0 chat export: whatever ran before the snag is kept.
                    toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList())
                )
                conversationRepository.insertMessage(sessionId, snagMsg)
                memoryManager.storeMessage(snagMsg, sessionId)
                _chatError.value = null
                _agentState.value = AgentState.Idle
                return
            }

            // v1.2.1 RESEARCH GUARANTEE — the tool contract, enforced. The chat
            // prompt mandates web_search for current-info asks, but prompt
            // compliance is never the only line of defense (v1.0.5 lesson):
            // when the model answered a fresh-data ask straight from memory
            // (zero tool events this turn), the harness runs the tool loop NOW
            // so the search actually executes, the answer is grounded in real
            // results, and the visible ACTIVITY trace exists — Claude/OpenCode
            // behavior, deterministic rather than model-dependent.
            if (lastFinishReason != HarnessLoop.FINISH_LENGTH &&
                currentStepsSnapshot().none { it.kind == ActivityStep.KIND_TOOL } &&
                requiresFreshData(userMsg.text)
            ) {
                android.util.Log.i("AgentLoop", "research guarantee: fresh-data ask answered with zero tool events — running the harness search")
                val researchStartAt = System.currentTimeMillis()
                val groundedTurn = harnessFallbackTurn(provider, turnConfig, lastMsgs)
                val grounded = groundedTurn?.content
                if (groundedTurn != null) {
                    // v1.4.0 chat export: usage from the re-ask phase.
                    turnTokensUsed = groundedTurn.tokensUsed.takeIf { it > 0 } ?: turnTokensUsed
                    turnLatencyTotal = groundedTurn.latencyMs.takeIf { it > 0 } ?: turnLatencyTotal
                }
                // v1.2.1 round-6 fix (cap15 failed both CI passes): the model
                // can DODGE the re-ask and answer from memory again (zero tool
                // events — exactly the field evidence). When it does — or when
                // the loop produced nothing — the harness runs the search
                // ITSELF (forcedSearchTurn): the tool executes for real, the
                // ACTIVITY trace is guaranteed, and the model only writes the
                // grounded answer on top of the real results.
                val groundedFinal = if (grounded.isNullOrBlank() ||
                    currentStepsSnapshot().none { it.kind == ActivityStep.KIND_TOOL }
                ) {
                    val forced = harnessLoop.forcedSearchTurn(
                        provider = provider,
                        config = turnConfig.copy(history = lastMsgs),
                        history = lastMsgs,
                        userQuery = userMsg.text
                    ) { status -> _liveThinking.value = status }
                    if (forced != null) {
                        // v1.4.0 chat export: the deterministic search's model
                        // calls count toward the turn's usage too.
                        turnTokensUsed = (turnTokensUsed ?: 0) + forced.tokensUsed
                        turnLatencyTotal = (turnLatencyTotal ?: 0L) + forced.latencyMs
                    }
                    forced?.content?.takeIf { it.isNotBlank() } ?: grounded
                } else {
                    grounded
                }
                // v1.3.0 round-8: the harness research phase counts toward the
                // thinking duration so the final bubble's header reads
                // "THOUGHT FOR Xs" on this path too.
                thinkingDurationMs += System.currentTimeMillis() - researchStartAt
                if (!groundedFinal.isNullOrBlank()) {
                    currentReplyText = groundedFinal
                    persistReply(force = true)
                } else if (currentStepsSnapshot().none { it.kind == ActivityStep.KIND_TOOL }) {
                    // v1.2.1 honesty boundary: the model answered a fresh-data
                    // ask from memory AND even the harness's own direct search
                    // could not be reached (rate limits, network failure). The
                    // user must know the figures above are unverified, never
                    // silent fabrication.
                    currentReplyText += "\n\n(Note: live search could not be reached for " +
                        "this one right now — treat the figures above as unverified, " +
                        "from general knowledge. Ask again in a moment for verified data.)"
                    persistReply(force = true)
                }
            }

            // v1.2.1 LENGTH CONTRACT — one bounded expansion pass. A model
            // that answers an explicit long-form ask ("essay of at least 600
            // words") with a few lazy sentences and finish_reason=stop never
            // triggers continuation (no output limit was hit), so the user
            // would get a thin answer. The harness re-asks for the FULL
            // requested length once; continuation segments still flow if the
            // expansion itself hits the budget.
            if (lastFinishReason != HarnessLoop.FINISH_LENGTH &&
                asksForLongForm(userMsg.text) &&
                currentReplyText.length < LONG_FORM_MIN_CHARS
            ) {
                _liveThinking.value = "[harness] expanding the answer to the requested length…"
                val expanded = try {
                    harnessLoop.expandShortAnswer(
                        provider = provider,
                        config = turnConfig.copy(history = lastMsgs),
                        history = lastMsgs,
                        userQuery = userMsg.text,
                        currentReply = currentReplyText
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                _liveThinking.value = null
                if (expanded != null && expanded.content.length > currentReplyText.length) {
                    publishStep(
                        ActivityStep(
                            kind = ActivityStep.KIND_EXPANSION,
                            label = "Expanded to full length",
                            detail = "first pass ${currentReplyText.length} chars -> ${expanded.content.length} chars"
                        )
                    )
                    currentReplyText = expanded.content
                    persistReply(force = true)
                }
            }

            // v1.2.0 CONTINUATION — the "no artificial output limit" guarantee.
            // The streamed answer hit the model's output budget mid-answer
            // (finish_reason "length"): immediately call the API again with a
            // CONTINUE instruction and append the segments until the answer is
            // genuinely complete. The user sees ONE full reply, never a
            // silently truncated one.
            if (lastFinishReason == HarnessLoop.FINISH_LENGTH) {
                _liveThinking.value = "[harness] output limit reached — continuing the answer…"
                val result = harnessLoop.continueAnswer(
                    provider = provider,
                    config = turnConfig.copy(history = lastMsgs),
                    historySoFar = lastMsgs,
                    firstSegment = currentReplyText
                ) { status -> _liveThinking.value = status }
                _liveThinking.value = null
                if (result.content.isNotBlank() && result.content != currentReplyText) {
                    currentReplyText = result.content
                    persistReply(force = true)
                }
                if (result.stillTruncated) {
                    // Honest boundary after the continuation budget: tell the
                    // user instead of pretending the essay finished.
                    currentReplyText += "\n\n[Answer continued across ${result.continuationSegments + 1} " +
                        "output segments and is still not complete — say \"continue\" and I'll keep going.]"
                    persistReply(force = true)
                }
            }

            // v1.6.0 B2 THE ANSWER-SHAPE GATE (field P0-4/5/6): tool-syntax
            // runs, harness stubs, raw JSON envelopes, and oversized inline
            // dumps never reach the user. sanitizeFinalAnswer returns null
            // for stub-only text - one harness rescue, then the honest note.
            val sanitizedReply = AnswerHygiene.sanitizeFinalAnswer(currentReplyText)
            if (sanitizedReply == null) {
                val rescued = harnessFallbackTurn(provider, turnConfig, lastMsgs)?.content
                currentReplyText = rescued?.takeIf { it.isNotBlank() }
                    ?: "I worked on that, but the reply came back in a shape I couldn't use. Could you ask me again?"
            } else {
                currentReplyText = sanitizedReply
            }

            // v1.6.0 (field P1-8): a CHAT-mode reply claiming file tools
            // aren't available against an artifact goal must carry the
            // switch-mode offer (the field's phone_prices/ai_policy turns
            // lacked it while the capability existed two minutes later).
            if (mode == ChatMode.CHAT &&
                PlanResponseSanitizer.goalWantsArtifact(userMsg.text) &&
                PlanResponseSanitizer.replyRefusesGoal(currentReplyText)
            ) {
                currentReplyText += "\n\nSwitch to Agent mode (the toggle at the top) and I'll create that file for you."
            }

            val stepsSnapshot = stepsSnapshotWithThinking()
            val stepsEncoded = com.tsfdroid.ai.core.harness.ActivitySteps.encode(stepsSnapshot)
            android.util.Log.i(
                "AgentLoop",
                "final reply save: steps=${stepsSnapshot.size} jsonLen=${stepsEncoded?.length ?: -1} id=$replyId " +
                    "tools=${turnToolRecords.size} thinkingMs=$thinkingDurationMs"
            )
            val finalReplyMsg = replyMsg.copy(
                // v1.3.0: save-time stamp — a reply that answered an ask_user
                // question must sort AFTER that question bubble, never before
                // it (Room orders conversations by timestamp ASC).
                timestamp = System.currentTimeMillis(),
                text = currentReplyText,
                thinkingText = currentThinkingText.takeIf { it.isNotBlank() },
                // v1.2.1: persist the visible step trace on the reply so the
                // ACTIVITY section survives app restarts.
                stepsJson = stepsEncoded,
                // v1.4.0: a file created mid-turn rides the final reply as a
                // ChatGPT-style end-of-chat card (extras follow as their own
                // card messages below).
                attachmentJson = drainCollectedArtifact()?.also {
                    android.util.Log.i("AgentLoop", "artifact card attached to harness reply: ${it.take(80)}")
                },
                // v1.4.0 chat export: the debugging-fidelity payload — the
                // measured thinking phase, the concrete model id (already on
                // replyMsg), harness usage when that phase ran, and the full
                // tool-call log (raw args, mapped params, capped-but-real
                // results, per-call durations).
                thinkingDurationMs = thinkingDurationMs.takeIf { it > 0 },
                toolCallsJson = ToolCallRecords.encode(turnToolRecords.toList()),
                tokensUsed = turnTokensUsed,
                turnLatencyMs = turnLatencyTotal,
                mode = mode.name,
                turnWallMs = System.currentTimeMillis() - turnStartWallAt
            )
            conversationRepository.insertMessage(sessionId, finalReplyMsg)
            memoryManager.storeMessage(finalReplyMsg, sessionId)
            emitCollectedArtifactCards(sessionId)
            _chatError.value = null
            announce(finalReplyMsg.text)
            // v1.2.1 Hermes-style memory learning over the completed exchange —
            // background, never blocks, never fails the turn.
            scope.launch {
                memoryLearner.learnFromExchange(userMsg.text, finalReplyMsg.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            // v1.4.0: files created before the failure still belong to the
            // user — surface their cards before the error state lands.
            runCatching { emitCollectedArtifactCards(sessionId) }
            publishChatError(
                ChatErrorUiState.fromException(
                    sessionId = sessionId,
                    requestId = requestId,
                    runId = runId,
                    failure = e
                )
            )
        } catch (e: Exception) {
            runCatching { emitCollectedArtifactCards(sessionId) }
            _agentState.value = AgentState.Error(NetworkErrorFormatter.toUserMessage(e))
        }
    }

    /**
     * Appended to the system prompt on the corrective re-ask after an
     * unparseable plan answer. Reasoning models occasionally return malformed
     * JSON or narrate around the schema; one zero-temperature retry with an
     * explicit output contract recovers the turn instead of surfacing a
     * planning error.
     */
    private val PLAN_RETRY_SUFFIX =
        "\n\nSTRICT OUTPUT MODE: Reply with ONLY the raw JSON plan object — " +
            "no markdown fences, no explanation, no tool calls, no text before " +
            "or after the JSON."

    /**
     * Appended after [PLAN_RETRY_SUFFIX] when the first answer was a short
     * prose commitment ("I am creating the HTML file for you.") instead of a
     * plan. Without this the model repeats the commitment in JSON clothing
     * and nothing is ever written — the v1.0.5 field failure.
     */
    private val CONTENT_PLAN_RETRY_SUFFIX =
        "\nIf the goal requires creating or saving anything (file, HTML page, " +
            "PDF, report), the plan MUST execute it NOW: include a WRITE_FILE or " +
            "CREATE_PDF step with the COMPLETE content inside params.content. " +
            "Never reply that you will create it later."

    /**
     * One LLM planning call plus a single corrective re-ask when the answer
     * cannot be parsed into a plan OR is a short prose commitment that defers
     * an artifact task ("I am creating the HTML file for you."). Bounded: at
     * most one extra request, and when the re-ask also fails, [synthesize]
     * builds an executable plan deterministically (v1.0.6) — a data goal
     * becomes a WEB_SEARCH/FETCH_URL step, an artifact goal gets one
     * CONTENT_NOW generation call and becomes a WRITE_FILE/CREATE_PDF step —
     * so the turn can no longer end as a bare "let me build it" commitment.
     *
     * v1.0.5: the planning call streams through [streamCompleteWithThinking] so
     * reasoning-model thinking is published to [liveThinking] while the plan
     * is being formed — the user watches the agent reason instead of staring
     * at an indeterminate dots bubble.
     */
    private suspend fun completeAndParsePlan(
        provider: LLMProvider,
        request: LLMRequest,
        userGoal: String,
        reportLatency: suspend (LLMResponse) -> Unit
    ): Plan {
        // v1.0.6 loop-18: the STREAMING planning call can itself fail with a
        // provider error (loop-17 CI evidence: a blank tool-call shell on the
        // search task surfaced an "unreadable response" card and the whole
        // turn died). Treat a failed first attempt exactly like an
        // unparseable answer: fall to the corrective path below, which now
        // uses the non-streaming chain-walking complete() (its own bounded
        // model-chain retries) before deterministic synthesis.
        var first = try {
            streamingCompleteWithThinking(provider, request)
        } catch (failure: LLMException) {
            if (failure.error == com.tsfdroid.ai.core.llm.error.LLMError.FreeTierBlocked) throw failure
            null
        } catch (failure: java.io.IOException) {
            null
        }
        if (first != null) {
            reportLatency(first)
            try {
                return parsePlanFromLlmResponse(first.content, userGoal)
            } catch (firstFailure: IllegalArgumentException) {
                return correctiveAndSynthesizedPlan(provider, request, userGoal, reportLatency, first, firstFailure)
            }
        }
        return correctiveAndSynthesizedPlan(
            provider, request, userGoal, reportLatency,
            first = null,
            firstFailure = IllegalArgumentException("streaming planning call failed")
        )
    }

    /** Corrective re-ask (non-streaming, chain-walking) then deterministic synthesis. */
    private suspend fun correctiveAndSynthesizedPlan(
        provider: LLMProvider,
        request: LLMRequest,
        userGoal: String,
        reportLatency: suspend (LLMResponse) -> Unit,
        first: LLMResponse?,
        firstFailure: IllegalArgumentException
    ): Plan {
        val corrective = request.copy(
            systemPrompt = request.systemPrompt + PLAN_RETRY_SUFFIX + CONTENT_PLAN_RETRY_SUFFIX,
            messages = request.messages + ChatMessage(
                id = UUID.randomUUID().toString(),
                text = "Your previous reply was not a valid executable plan. " +
                    "Respond with ONLY the JSON plan object now.",
                sender = ChatMessage.Sender.USER
            ),
            temperature = 0.0f
        )
        val second = try {
            provider.complete(corrective)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (second != null) {
            reportLatency(second)
            try {
                return parsePlanFromLlmResponse(second.content, userGoal)
            } catch (_: IllegalArgumentException) {
                // fall through to synthesis
            }
        }
        // Second attempt also failed to produce an executable plan.
        // v1.0.6: synthesize a REAL executable plan before ever falling
        // back to prose delivery — the user must never watch the agent
        // promise a file/fetch and then stop.
        synthesizeExecutablePlan(provider, userGoal)?.let { return it }
        // Last resort: if the FIRST answer was a genuine conversational
        // reply, deliver it instead of failing the whole turn — the
        // user asked something and the model answered; that is a valid
        // agent outcome even when the goal sounded actionable.
        first?.let {
            PlanResponseSanitizer.classifyProseReply(
                PlanResponseSanitizer.stripReasoningBlocks(it.content)
            )?.let { (action, params) ->
                // v1.3.0 round 21: the last-resort prose delivery gets the
                // same deferral/refusal gate as every other parse path — a
                // "no tool access in this session" prose reply to a data
                // or artifact goal must never be the final answer (the
                // 2026-10-03 PDF screenshot shipped exactly this way).
                // proseDeclinesAction covers refusal phrases at any length
                // for data/artifact goals.
                if (action == "CHAT" &&
                    PlanResponseSanitizer.proseDeclinesAction(params["response"], userGoal)
                ) {
                    throw firstFailure
                }
                return buildSingleStepPlan(userGoal, action, params)
            }
        }
        throw firstFailure
    }

    /**
     * v1.3.0 round 24: the synthesized WEB_SEARCH step's query, repaired by
     * one bounded model call. The planner's corrective fallback used to
     * search the RAW goal ("can give me 5y of India vixen stock details with
     * analysis") — every backend rejected it as off-topic and the turn died
     * on a garbage query. The model writes the 5-10 word query a person
     * would type; any failure (rate limit, timeout, empty) falls back to
     * [SearchQueryQuality.fromGoal]'s deterministic strip.
     */
    private suspend fun repairSearchQuery(provider: LLMProvider, userGoal: String): String {
        val deterministic = SearchQueryQuality.fromGoal(userGoal)
        return try {
            val response = withTimeout(SEARCH_QUERY_REPAIR_TIMEOUT_MS) {
                provider.complete(
                    LLMRequest(
                        systemPrompt = "You rewrite requests into web search queries. Output ONLY " +
                            "the query — 5 to 10 words, the way a person would type it into a search " +
                            "engine: key entity, topic, and qualifier (e.g. 'India VIX 5 year " +
                            "historical data', 'Nvidia NVDA stock price today'). No quotes, no " +
                            "sentence, no punctuation at the end.",
                        messages = listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = "Request: ${userGoal.take(300)}\nSearch query:",
                                sender = ChatMessage.Sender.USER
                            )
                        ),
                        temperature = 0.0f,
                        maxTokens = 60,
                        responseFormat = ResponseFormat.TEXT
                    )
                )
            }
            val repaired = PlanResponseSanitizer.stripReasoningBlocks(response.content)
                .trim().trim('"', '\'', '.', '!', '?', '\n')
                .replace(Regex("\\s+"), " ")
            // Sanity: a repaired query must be a real improvement, not a
            // restatement of a whole sentence or an empty echo.
            if (repaired.length in 4..80 && repaired.split(" ").size <= 12 &&
                !SearchQueryQuality.isDegenerate(repaired)
            ) {
                android.util.Log.i(
                    "AgentLoop",
                    "synthesized search query repaired: '${userGoal.take(60)}' -> '$repaired'"
                )
                repaired
            } else {
                deterministic
            }
        } catch (tce: TimeoutCancellationException) {
            android.util.Log.w("AgentLoop", "search query repair timed out — deterministic strip stays")
            deterministic
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "search query repair failed: ${e.localizedMessage}")
            deterministic
        }
    }

    /**
     * v1.0.6: deterministic plan synthesis for goals the model repeatedly
     * answered with prose. Artifact goals get one dedicated CONTENT_NOW
     * generation request whose output becomes the inline content of a
     * WRITE_FILE / CREATE_PDF step; data goals become a WEB_SEARCH (or
     * FETCH_URL when the goal carries a URL) step with no extra LLM call.
     * Returns null when the goal matches neither class — the caller then
     * keeps its existing prose fallback.
     */
    private suspend fun synthesizeExecutablePlan(provider: LLMProvider, userGoal: String): Plan? {
        val goal = userGoal.lowercase()

        // v1.6.0 (field P0-7): questions are never file asks (see the
        // generatePlan guard - this covers the execution-time deferral path).
        if (GoalContract.isInterrogativeAboutStorage(userGoal)) return null

        // v1.3.0 round 22 (cap3 E2E evidence, run on d697485): an EXPLICIT
        // URL in a fetch-flavored goal is a FETCH ask first. "Fetch
        // https://example.com and report the page's main heading" carries
        // the artifact word "report" as a VERB — the artifact heuristic
        // below read it as a PDF ask and synthesized a report file instead
        // of fetching the page the user named. The URL + fresh-data intent
        // wins over any artifact wording.
        val explicitUrl = Regex("https?://\\S+").find(userGoal)?.value
        val wantsFreshDataNow = PlanResponseSanitizer.goalWantsWebData(userGoal) ||
            PlanResponseSanitizer.goalDemandsFreshData(userGoal)
        if (explicitUrl != null && wantsFreshDataNow) {
            return buildSingleStepPlan(userGoal, "FETCH_URL", mapOf("url" to explicitUrl))
        }

        // Data goal → executable search step right now. v1.3.0 round-7: an
        // explicit lookup command ("google X", "look up X") counts as a data
        // goal even when no DATA_WORD matches — the command IS the task.
        // v1.3.0 round 24 (cap22 E2E evidence, runs on d697485/a7fc908): the
        // condition must be CONCRETE artifact asks only. The loose
        // goalWantsArtifact reads the VERB "report" ("...and report the
        // source URL", "...report the page's main heading") as the artifact
        // NOUN and synthesized a report PDF where the user asked for a
        // searched ANSWER — twice.
        val wantsFreshData = PlanResponseSanitizer.goalWantsWebData(userGoal) ||
            PlanResponseSanitizer.goalDemandsFreshData(userGoal)
        if (wantsFreshData && !PlanResponseSanitizer.goalWantsConcreteArtifact(userGoal)) {
            val url = Regex("https?://\\S+").find(userGoal)?.value
            return if (url != null) {
                buildSingleStepPlan(userGoal, "FETCH_URL", mapOf("url" to url))
            } else {
                // v1.3.0 round 24 (cap24 E2E evidence): the raw goal is often
                // a bad QUERY ("can give me 5y of India vixen stock details
                // with analysis" — every backend rejected it). One bounded
                // model call rewrites it into the short query a person would
                // type; any failure falls back to SearchQueryQuality's
                // deterministic repair.
                val query = repairSearchQuery(provider, userGoal)
                buildSingleStepPlan(userGoal, "WEB_SEARCH", mapOf("query" to query))
            }
        }

        // Artifact goal → generate the complete file content NOW.
        if (!PlanResponseSanitizer.goalWantsArtifact(userGoal)) return null

        // v1.6.0 (field P0-2/P0-3/P1-1/P1-6 — the deliverable contract): the
        // old synthesizer hardcoded Documents/report.pdf / document.txt /
        // data.json, ignored the user's requested names, shipped model
        // narration as content, emitted unfilled [placeholder] templates, and
        // collapsed multi-file asks into ONE wrong file. The contract now:
        //  1. parse the requested filename(s) from the goal (verbatim names
        //     like ondevice_llm_benchmark_2026.md / llm_perf_metrics.csv);
        //  2. one step PER requested file (a 4-file ask gets 4 steps);
        //  3. content gates before every write (narration, placeholders,
        //     extension-kind mismatches) with ONE repair re-ask — content
        //     that still fails the gate delivers an honest CHAT answer with
        //     the data inline, never a garbage file.
        val requestedFiles = GoalContract.parseRequestedFilenames(userGoal)
        val wantsPdf = goal.contains("pdf") || goal.contains("document") ||
            (goal.contains("report") && !goal.contains("html") && !goal.contains("website"))
        val deliverables = when {
            requestedFiles.isNotEmpty() -> requestedFiles
                // The user's own named files win; honor the requested folder
                // only when the goal names one AND no explicit filename rides it.
                .map { name ->
                    val dir = if (goal.contains("marketreports") || goal.contains("market reports")) "MarketReports/" else ""
                    "$dir$name"
                }
            goal.contains("website") || goal.contains("html") -> listOf("website.html")
            wantsPdf -> listOf("report.pdf")
            goal.contains("csv") -> listOf("data.csv")
            goal.contains("json") -> listOf("data.json")
            else -> listOf("document.txt")
        }
        val fileNameHint = deliverables.first().substringAfterLast('/')

        // v1.1.1 RESEARCH-GROUNDED content engine: the old engine wrote
        // artifacts from model memory alone — the user's "deep research PDF"
        // compared against OpenCode (which researches first) came out thin
        // and stale. Run real in-app searches FIRST (two query angles), then
        // hand the model the results as grounding for the final document.
        val researchQuery = userGoal.take(300)
        val searchOne = try {
            withContext(Dispatchers.IO) { com.tsfdroid.ai.actions.InformationActions.searchWeb(researchQuery) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "content-engine search 1 failed: ${e.localizedMessage}")
            null
        }
        val searchTwo = try {
            withContext(Dispatchers.IO) { com.tsfdroid.ai.actions.InformationActions.searchWeb("$researchQuery latest news facts 2026") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "content-engine search 2 failed: ${e.localizedMessage}")
            null
        }
        val researchGrounding = listOfNotNull(searchOne, searchTwo)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n") { it.take(4000) }

        // One content call per deliverable — a 2-file ask gets its two files,
        // not one wrong file plus a 45k-char dump in chat (field P1-6).
        val generatedContents = mutableListOf<Pair<String, String>>() // (fileName, content)
        for ((index, filePath) in deliverables.withIndex()) {
            val fileInstruction = if (deliverables.size > 1) {
                "This is deliverable ${index + 1} of ${deliverables.size}: the file '$filePath'."
            } else {
                "The file must be named exactly: $filePath"
            }
            val contentRequest = LLMRequest(
                systemPrompt = "You are TSF Droid's content engine. Produce the COMPLETE, final file content " +
                    "for the user's request — never a description, never a promise, never a plan. " +
                    (if (filePath.endsWith(".pdf") || wantsPdf)
                        "Plain readable report text (it will be typeset into a PDF): a title line, an intro, " +
                            "sections with headings, concrete facts and numbers from the research below, a short " +
                            "conclusion, and a final 'Sources:' list quoting the URLs you used. "
                    else "") +
                    "NEVER leave unfilled placeholders like [price] or [today's rate] — if a value is in the " +
                    "research below, USE THE REAL VALUE; if you truly don't have it, write that explicitly. " +
                    "Do NOT output a FILE: line — output ONLY the raw file content.",
                messages = listOf(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = buildString {
                            append("$userGoal\n\n$fileInstruction\n\nOutput the complete file content now — the raw content only, nothing else.")
                            if (researchGrounding.isNotBlank()) {
                                append("\n\nRESEARCH RESULTS (ground every factual claim in these; cite the URLs):\n")
                                append(researchGrounding)
                            } else {
                                append("\n\n(No live research was available — write from your best knowledge and say nothing about missing research.)")
                            }
                        },
                        sender = ChatMessage.Sender.USER
                    )
                ),
                temperature = 0.3f,
                // A full researched report is a large artifact: give it the full
                // artifact budget (the provider clamps to the model's context).
                maxTokens = 8192,
                responseFormat = ResponseFormat.TEXT
            )
            val generated = try {
                provider.complete(contentRequest)
            } catch (e: LLMException) {
                return null
            }
            var content = PlanResponseSanitizer.stripReasoningBlocks(generated.content).trim()
            // The old contract asked for a "FILE:" first line; tolerate and
            // strip it when the model still emits one.
            if (content.startsWith("FILE:", ignoreCase = true)) {
                val firstLineEnd = content.indexOf('\n')
                content = if (firstLineEnd > 0) content.substring(firstLineEnd + 1).trim() else content
            }
            if (content.isBlank()) return null

            // v1.6.0 THE CONTENT GATE (field P0-2/P0-3): narration, placeholder
            // templates, and extension-kind mismatches never ship as files.
            // One bounded repair re-ask; a second failure drops the FILE path
            // and returns null (the caller's answer-engine ladder delivers
            // the gathered data as an honest chat answer instead).
            val ext = filePath.substringAfterLast('.', "").lowercase()
            val gateReason = GoalContract.contentGate(content, ext)
            if (gateReason != null) {
                android.util.Log.w(
                    "AgentLoop",
                    "content gate rejected $filePath: $gateReason — one repair re-ask"
                )
                val repair = try {
                    provider.complete(
                        contentRequest.copy(
                            messages = listOf(
                                ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    text = "Your previous output was rejected: $gateReason. " +
                                        "Output the COMPLETE $filePath content now — real values from the research, " +
                                        "no placeholders, no narration, no description of what you will do. " +
                                        (if (researchGrounding.isNotBlank()) "\n\nRESEARCH RESULTS:\n$researchGrounding" else "") +
                                        "\n\nRaw file content only:",
                                    sender = ChatMessage.Sender.USER
                                )
                            )
                        )
                    )
                } catch (e: LLMException) {
                    null
                }
                val repaired = repair?.let { PlanResponseSanitizer.stripReasoningBlocks(it.content).trim() }
                if (!repaired.isNullOrBlank() && GoalContract.contentGate(repaired, ext) == null) {
                    content = repaired
                } else {
                    // v1.6.0 round 3 (critic-21): one bad deliverable no
                    // longer cancels the WHOLE shipment (the 2-file field
                    // goal used to lose BOTH files when one failed the gate).
                    // Drop the failing file, keep the ones that passed; the
                    // summary's claim audit names what never shipped.
                    android.util.Log.w(
                        "AgentLoop",
                        "content gate failed twice for $filePath - dropping that deliverable, keeping the rest"
                    )
                    continue
                }
            }
            generatedContents.add(filePath to content)
        }

        // Build one step per deliverable, in the order the goal named them.
        // v1.6.0 round 3: when EVERY deliverable failed the gate, no file
        // ships - return null so the caller's answer-engine ladder delivers
        // the gathered research as an honest chat answer instead.
        if (generatedContents.isEmpty()) {
            android.util.Log.w(
                "AgentLoop",
                "every deliverable failed the content gate - no files; the chat answer carries the data"
            )
            return null
        }
        val steps = generatedContents.mapIndexed { i, (filePath, content) ->
            PlanStep(
                stepId = "s${i + 1}",
                order = i + 1,
                description = "Create ${filePath.substringAfterLast('/')}",
                action = if (filePath.endsWith(".pdf")) "CREATE_PDF" else "WRITE_FILE",
                params = if (filePath.endsWith(".pdf")) {
                    mapOf(
                        "filePath" to filePath,
                        "title" to filePath.substringAfterLast('/').substringBeforeLast('.'),
                        "content" to content
                    )
                } else {
                    mapOf("filePath" to filePath, "content" to content)
                },
                fallback = ""
            )
        }
        return Plan(
            planId = UUID.randomUUID().toString(),
            goal = userGoal,
            estimatedDuration = "instant",
            estimatedSteps = steps.size,
            steps = steps
        )
    }

    /**
     * Aggregating completion that surfaces reasoning deltas on [liveThinking]
     * as they stream. Uses the provider's detailed streaming surface (content
     * wrapped by the default impl on providers without reasoning), so the
     * returned [LLMResponse] is wire-equivalent to provider.complete().
     */
    private suspend fun streamingCompleteWithThinking(
        provider: LLMProvider,
        request: LLMRequest
    ): LLMResponse {
        val startedAt = System.currentTimeMillis()
        var content = ""
        var thinking = ""
        try {
            provider.streamCompleteDetailed(request).collect { event ->
                when (event) {
                    is com.tsfdroid.ai.core.llm.LLMStreamEvent.Content -> content += event.text
                    is com.tsfdroid.ai.core.llm.LLMStreamEvent.Reasoning -> {
                        thinking = AnswerHygiene.joinThinkingSegments(thinking, event.text)
                        _liveThinking.value = thinking.takeLast(LIVE_THINKING_TAIL)
                    }
                    // v1.2.0: finish signal not needed here — planner-style
                    // aggregation ignores it.
                    is com.tsfdroid.ai.core.llm.LLMStreamEvent.Finished -> Unit
                }
            }
        } finally {
            _liveThinking.value = null
        }
        return LLMResponse(
            content = content,
            tokensUsed = 0,
            model = request.model ?: "",
            provider = provider.name,
            latencyMs = System.currentTimeMillis() - startedAt
        )
    }

    /**
     * v1.0.6: the chat-path TOOL LOOP. Reasoning models on the Zen free tier
     * answer the harness contract (`read`/`shell` tool calls) instead of
     * writing prose; instead of failing with MALFORMED_RESPONSE, the mappable
     * calls execute through the SAME action pipeline plans use (permissions,
     * internet checks, workspace sandbox), their results go back to the
     * model, and the turn finishes with the grounded final answer. Bounded
     * rounds; null when the loop cannot produce an answer.
     */
    /**
     * v1.2.0: runs one full harness turn (tool rounds + continuations) as the
     * chat fallback path. Replaces the old fixed-4-round runChatToolLoop.
     *
     * v1.3.0 ASK-RESUME: ask_user calls are SURFACED, not suspended inside
     * the wall-clock bound — the 15-minute turn timeout only ever bounds
     * MODEL work. Each resume segment (turn segment between user answers)
     * gets its own fresh bound, and the user can take as long as they like
     * to answer. Bounded by [MAX_ASKS_PER_TURN] resumes.
     */
    /**
     * v1.4.0 chat export: now returns the harness's FULL [HarnessLoop.TurnResult]
     * (content + accumulated tokens/latency), not just the answer string —
     * the caller persists the usage numbers on the reply for the export.
     */
    private suspend fun harnessFallbackTurn(
        provider: LLMProvider,
        turnConfig: HarnessLoop.TurnConfig,
        history: List<ChatMessage>
    ): HarnessLoop.TurnResult? {
        android.util.Log.i("AgentLoop", "harnessFallbackTurn begin (history=${history.size} msgs)")
        var currentHistory = history
        var asks = 0
        while (true) {
            val result = try {
                // v1.2.1 round-16: the TURN-level bound — now per SEGMENT:
                // each runTurn segment between user answers is bounded, the
                // ask parking between segments is NOT (a user thinking for
                // an hour must never kill the turn).
                withTimeout(HARNESS_TURN_TIMEOUT_MS) {
                    harnessLoop.runTurn(
                        provider,
                        turnConfig.copy(history = currentHistory, surfaceAsks = true)
                    ) { status -> _liveThinking.value = status }
                }
            } catch (tce: TimeoutCancellationException) {
                android.util.Log.w(
                    "AgentLoop",
                    "harness fallback exceeded ${HARNESS_TURN_TIMEOUT_MS / 1000}s — completing the turn without it"
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AgentLoop", "Harness fallback failed: ${e.localizedMessage}")
                null
            }
            if (result == null) {
                _liveThinking.value = null
                android.util.Log.i("AgentLoop", "harnessFallbackTurn returned len=-1")
                return null
            }
            val question = result.askQuestion
            if (question != null) {
                if (asks < MAX_ASKS_PER_TURN) {
                    asks++
                    // UNBOUNDED park: post the question, wait for the user's
                    // answer, then resume the turn with it as the tool result.
                    val answer = handleAskUser(question, result.askOptions, activeTaskSessionId)
                    currentHistory = result.resumedMessages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = HarnessLoop.askResultMessage(question, answer),
                        sender = ChatMessage.Sender.USER
                    )
                } else {
                    // Question budget spent: the model must decide itself.
                    android.util.Log.i("AgentLoop", "ask budget spent — telling the model to decide")
                    currentHistory = result.resumedMessages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = HarnessLoop.askResultMessage(
                            question,
                            "(question budget for this turn is spent — decide yourself and answer now)"
                        ),
                        sender = ChatMessage.Sender.USER
                    )
                }
                continue
            }
            _liveThinking.value = null
            val answer = result.content.takeIf { it.isNotBlank() }
            android.util.Log.i("AgentLoop", "harnessFallbackTurn returned len=${answer?.length ?: -1}")
            return result.takeIf { answer != null }
        }
    }

    /**
     * v1.2.0: the tool set advertised on chat requests, per mode (rides along
     * after the mandatory harness pair — the endpoint accepts extra tools).
     * CHAT mode advertises read-only tools only; the harness gate refuses
     * anything else even if the model calls it anyway.
     */
    private fun chatToolsFor(mode: ChatMode): List<Tool> = buildList {
        add(
            Tool(
                name = "web_search",
                description = "Live in-app web search WITHOUT a browser: returns top result titles, snippets and URLs.",
                parameters = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
            )
        )
        add(
            Tool(
                name = "fetch_url",
                description = "Fetch a URL's page text in-app (current data, prices, articles).",
                parameters = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
            )
        )
        add(
            Tool(
                name = "read_file",
                description = "Read a workspace file's content (text, code, CSV, JSON).",
                parameters = """{"type":"object","properties":{"path":{"type":"string","description":"relative path e.g. Documents/notes.txt"}},"required":["path"]}"""
            )
        )
        add(
            Tool(
                name = "list_files",
                description = "List the files in a workspace directory.",
                parameters = """{"type":"object","properties":{"path":{"type":"string","description":"optional directory path"}},"required":[]}"""
            )
        )
        // v1.3.0: ask the user — opencode's question tool. Available in BOTH
        // modes (asking mutates nothing); the app parks the turn on a visible
        // answer surface and the reply returns as the tool's result.
        add(
            Tool(
                name = HarnessLoop.ASK_USER_TOOL,
                description = "Ask the user a question when you need information, a choice, or " +
                    "a decision before you can proceed. The user sees the question with tappable " +
                    "option chips plus a free-text answer box; their answer comes back to you as " +
                    "this tool's result and you continue. Use it instead of guessing missing " +
                    "details (which contact, what date, what format, which option they prefer).",
                parameters = """{"type":"object","properties":{"question":{"type":"string","description":"The complete question, one clear sentence"},"header":{"type":"string","description":"Very short label for the answer box (max 30 chars), optional"},"options":{"type":"array","items":{"type":"string"},"description":"2-4 short tappable answer options (optional; the user can always type their own)"}},"required":["question"]}"""
            )
        )
        if (mode == ChatMode.AGENT) {
            add(
                Tool(
                    name = "write_file",
                    description = "Write a real file on the device (HTML page, CSV, text). Provide the COMPLETE content.",
                    parameters = """{"type":"object","properties":{"path":{"type":"string","description":"relative path e.g. Documents/website/index.html"},"content":{"type":"string","description":"the complete file content"}},"required":["path","content"]}"""
                )
            )
            add(
                Tool(
                    name = "create_pdf",
                    description = "Generate a real PDF document from text content.",
                    parameters = """{"type":"object","properties":{"path":{"type":"string"},"title":{"type":"string"},"content":{"type":"string"}},"required":["content"]}"""
                )
            )
        }
    }

    /** Localized date/time line for the harness system prompt. */
    private fun currentDateTimeLine(): String =
        SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.US).format(Date())

    /** v1.3.0: [currentStepsSnapshot] with the thinking-duration step prepended. */
    private fun currentStepsWithThinking(durationMs: Long): List<ActivityStep> {
        val base = currentStepsSnapshot()
        val thinkingStep = thinkingDurationStep(durationMs) ?: return base
        return listOf(thinkingStep) + base
    }

    /**
     * v1.3.0: recent conversation context for the planner — the agent-mode
     * "model not getting the context" fix. Bounded to the last
     * [PLANNING_HISTORY_MESSAGES] messages and an ADAPTIVE token budget
     * (25% of the model's registry window — the planning system prompt with
     * the full action schema is itself large, so history gets a modest
     * share). ON-DEVICE models get NO history: their 4k-token windows are
     * already consumed by the schema prompt (pre-v1.3.0 behavior), and any
     * history would overflow the request exactly where it used to fit.
     */
    private suspend fun planningHistory(
        sessionId: String,
        currentMsg: ChatMessage,
        modelSpec: com.tsfdroid.ai.core.llm.providers.ZenModelSpec?,
        onDeviceProvider: Boolean
    ): List<ChatMessage> {
        if (onDeviceProvider) return emptyList()
        val window = modelSpec?.contextWindow?.takeIf { it > 0 }
        val budgetTokens = window?.let { (it * 0.25).toInt() } ?: 4_000
        val capped = budgetTokens.coerceIn(1_000, 6_000)
        val recent = conversationRepository
            .getLastMessages(sessionId, PLANNING_HISTORY_MESSAGES + 1)
            .filter { it.id != currentMsg.id }
        return trimHistoryToTokenBudget(recent, capped)
    }

    /**
     * v1.2.0 vision degradation: when no vision-capable model is reachable,
     * strip the images a message carries and append an honest note — the
     * model must tell the user it cannot see them, never answer about
     * nothing.
     */
    private fun degradeImagesToNote(msg: ChatMessage): ChatMessage {
        val images = msg.allImages()
        if (images.isEmpty()) return msg
        val note = "\n\n[The user attached ${images.size} image(s) in this message, but no " +
            "vision-capable model is currently available to view them. If the request depends " +
            "on those images, say so honestly and ask the user to describe them or switch models.]"
        return msg.copy(
            imageBase64 = null,
            attachmentsJson = null,
            text = msg.text + note
        )
    }

    private suspend fun generatePlan(userMsg: ChatMessage, context: Context, sessionId: String) {
        try {
            val provider = llmProviderFactory.getActiveProvider()
            val relevantContext = memoryManager.getRelevantContext(userMsg.text)
            val sysPrompt = "${PlanningPrompts.PLANNING_SYSTEM_PROMPT}\n\nContext about user and device:\n$relevantContext"
            val config = settingsRepository.llmConfig.first()
            // v1.3.0: the agent-mode context fix — the planner previously saw
            // ONLY the current message, so contextual follow-ups ("send it to
            // him too", "same as last time but shorter") planned blind. Recent
            // conversation history now rides along, adaptively budgeted to the
            // active model's registry context window.
            val activeModelId = config.selectedModelFor(config.activeProvider)
            val planModelSpec = runCatching { modelsDevRegistry.modelInfo()[activeModelId] }.getOrNull()
            val onDeviceProvider = config.activeProvider.contains("device", ignoreCase = true) ||
                config.activeProvider.contains("gemma", ignoreCase = true) ||
                config.activeProvider.contains("litert", ignoreCase = true)
            val planHistory = planningHistory(sessionId, userMsg, planModelSpec, onDeviceProvider)
            val plan = if (config.multiAgentModeEnabled) {
                kotlinx.coroutines.coroutineScope {
                    val plannerDeferred = async(Dispatchers.Default) {
                        provider.complete(
                            LLMRequest(
                                systemPrompt = sysPrompt,
                                messages = planHistory + userMsg,
                                temperature = 0.2f,
                                maxTokens = PLANNING_MAX_TOKENS,
                                responseFormat = ResponseFormat.JSON
                            )
                        )
                    }

                    val criticDeferred = async(Dispatchers.Default) {
                        provider.complete(
                            LLMRequest(
                                systemPrompt = PlanningPrompts.CRITIC_SYSTEM_PROMPT,
                                messages = listOf(userMsg),
                                temperature = 0.2f,
                                maxTokens = 1000,
                                responseFormat = ResponseFormat.TEXT
                            )
                        )
                    }

                    val plannerResponse = plannerDeferred.await()
                    val criticResponse = criticDeferred.await()
                    reportLocalPlanningLatency(plannerResponse)
                    reportLocalPlanningLatency(criticResponse)

                    val mergePrompt = """
                        ${PlanningPrompts.MERGE_SYSTEM_PROMPT}
                        
                        User Goal: ${userMsg.text}
                        Initial Plan: ${plannerResponse.content}
                        Critic Safety & Edge Case Report: ${criticResponse.content}
                    """.trimIndent()

                    completeAndParsePlan(
                        provider,
                        LLMRequest(
                            systemPrompt = mergePrompt,
                            messages = planHistory + userMsg + ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = "Merge the plan and critique into the final JSON plan.",
                                sender = ChatMessage.Sender.USER,
                                imageBase64 = userMsg.imageBase64
                            ),
                            temperature = 0.1f,
                            maxTokens = PLANNING_MAX_TOKENS,
                            responseFormat = ResponseFormat.JSON
                        ),
                        userMsg.text,
                        ::reportLocalPlanningLatency
                    )
                }
            } else {
                completeAndParsePlan(
                    provider,
                    LLMRequest(
                        systemPrompt = sysPrompt,
                        messages = planHistory + userMsg,
                        temperature = 0.1f,
                        maxTokens = PLANNING_MAX_TOKENS,
                        responseFormat = ResponseFormat.JSON
                    ),
                    userMsg.text,
                    ::reportLocalPlanningLatency
                )
            }

            // v1.3.0 round 21: the LAST line of defense before a parsed plan
            // starts. Whatever parse path produced it — wrapper form, full
            // plan, corrective re-ask, prose classification — a plan whose
            // steps are ALL CHAT against a data or artifact goal never runs;
            // it is swapped for the deterministic executable plan (WEB_SEARCH
            // / FETCH_URL / CREATE_PDF with generated content). The 2026-10-03
            // field failures (Nvidia "no tool access in this session", the
            // PDF "file-generation tools aren't available") shipped through
            // exactly this gap.
            val parsedPlan = if (
                PlanResponseSanitizer.planDefersGoal(
                    plan.steps.map { it.action }, plan.goal
                )
            ) {
                android.util.Log.w(
                    "AgentLoop",
                    "parsed plan defers the goal (all-CHAT for a data/artifact ask) — synthesizing executable steps"
                )
                val provider2 = runCatching { llmProviderFactory.getActiveProvider() }.getOrNull()
                provider2?.let { synthesizeExecutablePlan(it, plan.goal) } ?: plan
            } else {
                plan
            }

            // v1.6.0 (field P0-7): the interrogative guard. A question about
            // where the app stores things never becomes a file-write - the
            // field's "can you tell me the location in device where the
            // export chats are saved" became a WRITE_FILE that overwrote the
            // user's scrap_titles.py. Route it to the chat path, whose prompt
            // now carries the app's real storage facts.
            if (GoalContract.isInterrogativeAboutStorage(userMsg.text) &&
                parsedPlan.steps.any {
                    it.action.trim().uppercase() in setOf("WRITE_FILE", "CREATE_PDF")
                }
            ) {
                android.util.Log.w(
                    "AgentLoop",
                    "storage question misrouted into a file-write plan - answering in chat instead"
                )
                executeSimpleQuery(userMsg, sessionId)
                return
            }

            planManager.startNewPlan(parsedPlan, context, PlanStatus.PROPOSED)
            // Re-read after LLM work: user may have flipped mode or revoked grants
            // while planning was in flight; stale pre-LLM config must not auto-run.
            val liveConfig = settingsRepository.llmConfig.first()
            val approval = liveConfig.approvalSettings()
            if (AutoApprovalPolicy.shouldAutoApprove(approval.mode, approval.grantedActions, parsedPlan)) {
                recordAutoApprovedTrace(parsedPlan, approval.mode, sessionId)
                executePlanLoop(parsedPlan, context, sessionId, autoApproved = true)
            } else {
                proposePlan(parsedPlan, sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            publishChatError(
                ChatErrorUiState.fromException(
                    sessionId = sessionId,
                    requestId = userMsg.id,
                    runId = UUID.randomUUID().toString(),
                    failure = e
                )
            )
        } catch (e: Exception) {
            fallbackOrError(userMsg, context, e, sessionId)
        }
    }

    private suspend fun reportLocalPlanningLatency(response: LLMResponse) {
        val result = llmProviderFactory.recordPlanningLatency(response)
        if (result?.status == LatencyBudgetStatus.EXCEEDED && result.message != null) {
            onSpeakCallback?.invoke(result.message)
        }
    }

    /**
     * Non-LLM planning failures may still degrade to alias/simple chat. Typed
     * [LLMException]s are handled above and must never fall through here.
     */
    private suspend fun fallbackOrError(userMsg: ChatMessage, context: Context, cause: Throwable, sessionId: String) {
        android.util.Log.e("AgentLoop", "Plan generation failed: ${cause.localizedMessage}", cause)

        val lastExecuted = lastExecutedActionsBySession[sessionId]
        val alias = AliasResolver.resolve(userMsg.text)
            ?: AliasResolver.resolveContextual(userMsg.text, lastExecuted?.action, lastExecuted?.params)
        if (alias != null) {
            executeAliasDirect(alias, userMsg.text, context, sessionId)
            return
        }

        try {
            memoryManager.logTaskExecution(
                stepId = "plan-gen",
                planId = "n/a",
                description = userMsg.text,
                actionType = "PLAN_GENERATION",
                params = emptyMap(),
                success = false,
                resultData = null,
                errorMessage = cause.localizedMessage?.take(200)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AgentLoop", "Failed to log plan generation failure: ${e.localizedMessage}")
        }
        executeSimpleQuery(userMsg, sessionId)
    }

    fun approveProposedPlan(context: Context, grantActions: Set<String> = emptySet()) {
        currentJob?.cancel()
        val job = scope.launch {
            // v1.2.1: a plan approval starts a fresh visible-step trace.
            _activitySteps.value = emptyList()
            // "Always allow" checkboxes from the approval modal: persist BEFORE
            // executing so a crash mid-plan can't lose an explicit user grant.
            // Filter through isGrantable - neverAutoApprove actions can never
            // enter the allowlist no matter what the UI sends.
            val toGrant = grantActions.filter { AutoApprovalPolicy.isGrantable(it) }
            if (toGrant.isNotEmpty()) {
                val grantedAt = System.currentTimeMillis()
                settingsRepository.updateConfig { current ->
                    current.copy(
                        grantedActions = current.effectiveGrantedActions() + toGrant.associateWith { grantedAt }
                    )
                }
            }
            // Approving a proposed plan starts a new task in its own right (the plan may
            // have been proposed in an earlier, now-idle turn), so pin its session here too -
            // same rationale as processQuery, see activeTaskSessionId. Executes in the
            // session the plan was actually PROPOSED for, never wherever the user happens
            // to be looking right now - falls back to "current" only if somehow nothing
            // was pinned (there is no proposal to misattribute in that case anyway).
            val sessionId = (proposedPlanSessionId ?: conversationRepository.ensureCurrentSessionId())
                .also { activeTaskSessionId = it }
            proposedPlanSessionId = null
            queryMutex.withLock {
                val plan = planManager.currentPlan.value ?: return@withLock
                executePlanLoop(plan, context, sessionId)
            }
        }
        currentJob = job
    }

    fun rejectProposedPlan() {
        proposedPlanSessionId = null
        planManager.clearPlan()
        _agentState.value = AgentState.Idle
    }

    /**
     * Forgets a session's contextual follow-up state (see
     * [lastExecutedActionsBySession]). Call this once a session is gone for good
     * (ChatViewModel.deleteChat -> ConversationRepository.deleteSession) so its
     * entry doesn't sit in the map forever - a deleted chat can never come back
     * to ask "turn it off" against whatever it last did. Harmless no-op if the
     * session never executed an action, or is already gone.
     */
    fun forgetSession(sessionId: String) {
        lastExecutedActionsBySession.remove(sessionId)
    }

    /**
     * Cancels whatever the agent is currently doing - a running plan, a query in
     * flight, or a pending "waiting for user input" prompt - and returns the loop
     * to Idle. Safe to call when nothing is running: when there is no job to
     * cancel there is nothing in flight for planManager.cancelPlan() to affect
     * either, so it's skipped entirely rather than invoked pointlessly - it would
     * otherwise land on whatever plan is still sitting in PlanManager from the
     * last finished task (kept there deliberately so the Plan tab can keep
     * showing it - see PlanManager.updatePlanStatus) and relabel it, which is
     * exactly what PlanManager's own terminal-status guard on cancelPlan() also
     * defends against - see that doc comment for why that guard is the
     * load-bearing one.
     *
     * The join-then-cancel below runs on its own coroutine, so it can finish
     * arbitrarily later - potentially after a brand-new, unrelated task has
     * already started (see the class-level race this guards against). It must
     * NOT resolve "the plan to cancel" by asking PlanManager what's current at
     * that later point; the only trustworthy identity is whatever plan was
     * actually current RIGHT NOW, captured synchronously before [jobToCancel]
     * even starts unwinding. [planManager.currentPlan] is a plain StateFlow
     * read, not suspending, so this capture is safe to do here.
     */
    fun cancelCurrentTask() {
        val jobToCancel = currentJob
        currentJob = null
        waitingSessionId = null
        proposedPlanSessionId = null
        _agentState.value = AgentState.Idle
        jobToCancel?.cancel() ?: return
        val planIdToCancel = planManager.currentPlan.value?.planId
        scope.launch {
            // Wait for the cancelled job to actually finish unwinding before marking
            // the plan cancelled, so its own (best-effort) status writes can't race
            // ahead of and overwrite the CANCELLED status set here.
            jobToCancel.join()
            if (planIdToCancel != null) {
                planManager.cancelPlan(planIdToCancel)
            }
        }
    }

    /**
     * Called right before a genuinely new task starts (see processQuery), never for a
     * reply to a pending awaitUserResponse() prompt. If [newSessionId] is about to take
     * over agentState/planManager's single global slots and some OTHER session still has
     * a plan sitting in AgentState.PlanProposed - proposed, but never approved, rejected,
     * or cancelled - resolve it explicitly right now instead of letting it be silently
     * destroyed: cancel it in planManager and leave a short, honest message in ITS OWN
     * session, mirroring the treatment abandonWaitingTask() gives an abandoned prompt.
     * A no-op when there is nothing stale to resolve, or when the proposal already
     * belongs to [newSessionId] (a new task in the SAME chat legitimately supersedes it).
     */
    private suspend fun resolveStaleProposedPlan(newSessionId: String) {
        val staleSessionId = proposedPlanSessionId
        if (staleSessionId == null || staleSessionId == newSessionId) return
        val proposedState = _agentState.value as? AgentState.PlanProposed ?: return

        proposedPlanSessionId = null
        _agentState.value = AgentState.Idle
        // Target the exact plan this state was carrying, not "whatever is
        // current" - see cancelPlan's expectedPlanId doc comment. There is no
        // job-join race here (the task that proposed this plan already
        // finished), but pinning the identity explicitly keeps this call
        // consistent with cancelCurrentTask/abandonWaitingTask rather than
        // relying on a coincidence that nothing else changed it in between.
        planManager.cancelPlan(proposedState.plan.planId)

        val stoppedMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "I cancelled the plan I proposed here since you started a new task elsewhere.",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(staleSessionId, stoppedMsg)
        memoryManager.storeMessage(stoppedMsg, staleSessionId)
    }

    /**
     * Cancels a task that is parked in awaitUserResponse() for [staleSessionId] because a
     * genuinely new message just arrived in a different session - nobody in
     * [staleSessionId] is ever going to answer that prompt now. Mirrors
     * [cancelCurrentTask]'s join-then-cancel-plan ordering, and additionally leaves a
     * short, honest message in the abandoned prompt's OWN session so that chat doesn't
     * just sit there silently forever.
     */
    private fun abandonWaitingTask(staleSessionId: String) {
        val jobToCancel = currentJob
        currentJob = null
        waitingSessionId = null
        _agentState.value = AgentState.Idle
        jobToCancel?.cancel()
        // Same identity-pinning rationale as cancelCurrentTask: capture now,
        // synchronously, before jobToCancel unwinds - never resolve "the plan
        // to cancel" from whatever PlanManager holds once the join below
        // finally completes.
        val planIdToCancel = planManager.currentPlan.value?.planId
        scope.launch {
            jobToCancel?.join()
            if (planIdToCancel != null) {
                planManager.cancelPlan(planIdToCancel)
            }

            val stoppedMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = "I stopped waiting for your reply here since you started a new conversation elsewhere.",
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "System"
            )
            conversationRepository.insertMessage(staleSessionId, stoppedMsg)
            memoryManager.storeMessage(stoppedMsg, staleSessionId)
        }
    }

    /**
     * v1.4.0: artifacts created during the CURRENT turn, collected by
     * [emitArtifactCardIfNeeded] and drained by the final reply save — the first
     * attaches to the summary message itself (ChatGPT-style end-of-chat
     * card), extras become follow-up card messages. Queue-based so the
     * plan path and the harness path share one mechanism.
     */
    private val collectedArtifacts = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Pops the first collected artifact (for the summary message itself). */
    private fun drainCollectedArtifact(): String? = collectedArtifacts.poll()

    /** Emits any leftover artifacts as standalone card messages. */
    private suspend fun emitCollectedArtifactCards(sessionId: String) {
        while (true) {
            val json = collectedArtifacts.poll() ?: break
            try {
                val name = org.json.JSONObject(json).optString("name", "file")
                val cardMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "Also created $name — open it below.",
                    sender = ChatMessage.Sender.AGENT,
                    modelBadge = "Agent",
                    attachmentJson = json
                )
                conversationRepository.insertMessage(sessionId, cardMsg)
            } catch (e: Exception) {
                android.util.Log.w("AgentLoop", "leftover artifact card failed: ${e.localizedMessage}")
            }
        }
    }

    /**
     * v1.0.6 → v1.4.0: after a WRITE_FILE / CREATE_PDF step succeeds, the
     * file becomes an attachment card on the turn's FINAL reply (was: an
     * immediate separate message — the card appeared mid-conversation and
     * the summary still dumped the file path as text). The card renders
     * with the real file name/size and Open/Share actions backed by
     * FileProvider; collection is validated NOW (file exists) and the
     * drain happens at the reply save, so the card sits at the end of the
     * chat exactly like ChatGPT / Claude / Gemini deliver created files.
     */
    private suspend fun emitArtifactCardIfNeeded(
        canonicalAction: String,
        params: Map<String, String>,
        actionResult: ActionResult,
        sessionId: String
    ) {
        if (canonicalAction != "WRITE_FILE" && canonicalAction != "CREATE_PDF") return
        try {
            val pathHint = when (actionResult) {
                is ActionResult.Success -> actionResult.dataMap["path"]
                else -> null
            } ?: params["filePath"] ?: params["path"] ?: run {
                android.util.Log.w("AgentLoop", "artifact card skipped: no path on $canonicalAction result/params")
                return
            }
            // v1.4.0 (cap9 forensics): some legacy results carry message-shaped
            // data ("File saved at /path", "PDF created: /path") in the path
            // slot — strip the known prefixes so the card survives them.
            val cleanPath = pathHint
                .replace(Regex("""^(File saved at|PDF created:|Folder created at|File created at)\s+"""), "")
                .trim()
            val context = contextOrNull() ?: run {
                android.util.Log.w("AgentLoop", "artifact card skipped: no app context")
                return
            }
            // v1.6.0 (field P1-3): WRITE_FILE now reports SAF content:// URIs
            // for custom-folder writes - resolve those through DocumentFile,
            // not java.io.File (the old file.exists() check silently skipped
            // ALL 12/12 field WRITE_FILE cards).
            if (cleanPath.startsWith("content://")) {
                try {
                    val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(
                        context, android.net.Uri.parse(cleanPath)
                    )
                    if (doc != null && doc.exists() && (doc.length() ?: 0L) > 0L) {
                        val attachmentJson = org.json.JSONObject()
                            .put("name", doc.name ?: "file")
                            .put("path", cleanPath)
                            .put("mime", doc.type ?: "application/octet-stream")
                            .put("size", doc.length() ?: 0L)
                            .toString()
                        collectedArtifacts.add(attachmentJson)
                        android.util.Log.i("AgentLoop", "artifact collected (SAF) for end-of-chat card: ${doc.name}")
                    } else {
                        android.util.Log.i("AgentLoop", "artifact card skipped: SAF doc missing: '$cleanPath'")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AgentLoop", "SAF artifact card failed: ${e.localizedMessage}")
                }
                return
            }
            val file = com.tsfdroid.ai.core.storage.StorageWorkspaceProvider.resolveFile(context, cleanPath)
            if (!file.exists() || file.length() == 0L) {
                android.util.Log.i("AgentLoop", "artifact card skipped: not on disk: '$cleanPath'")
                return
            }
            val mime = when {
                file.name.endsWith(".pdf", true) -> "application/pdf"
                file.name.endsWith(".html", true) || file.name.endsWith(".htm", true) -> "text/html"
                file.name.endsWith(".css", true) -> "text/css"
                file.name.endsWith(".js", true) -> "text/javascript"
                file.name.endsWith(".json", true) -> "application/json"
                file.name.endsWith(".csv", true) -> "text/csv"
                file.name.endsWith(".md", true) -> "text/markdown"
                else -> "text/plain"
            }
            val attachmentJson = org.json.JSONObject()
                .put("name", file.name)
                .put("path", file.absolutePath)
                .put("mime", mime)
                .put("size", file.length())
                .toString()
            collectedArtifacts.add(attachmentJson)
            android.util.Log.i("AgentLoop", "artifact collected for end-of-chat card: ${file.name}")
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "Artifact card emission failed: ${e.localizedMessage}")
        }
    }

    /** Application context captured from the latest agent entry point. */
    @Volatile
    private var appContext: android.content.Context? = null

    private fun contextOrNull(): android.content.Context? = appContext

    // (formatFileSize was removed in v1.4.0 — the card sizes come from the
    // FileAttachmentCard renderer, not the collector.)

    private fun proposePlan(plan: Plan, sessionId: String) {
        proposedPlanSessionId = sessionId
        _agentState.value = AgentState.PlanProposed(plan)
    }

    private suspend fun executePlanLoop(plan: Plan, context: Context, sessionId: String, autoApproved: Boolean = false) {
        planManager.updatePlanStatus(PlanStatus.RUNNING)
        // v1.3.0 round-7: fresh ask bookkeeping per plan run - a previous
        // plan's ask must not leak its confirmation into this one.
        askedUserDuringPlan = false
        // v1.6.0 (field P0-8e): fresh per-plan needs-input budget too.
        needsInputPromptsThisPlan = 0
        // v1.6.0 (field P2-5): wall-clock origin for this plan's summary.
        activePlanWallStartAt = System.currentTimeMillis()

        // v1.3.0 round 21: the execution-time deferral guard — covers every
        // path into this loop that skipped the parse-time gates (a plan
        // approved from the Plan tab after an edit, a MODIFY replan, an
        // older proposal re-approved after a mode flip). An all-CHAT plan
        // against a data or artifact goal never executes its canned slop:
        // it is swapped for the deterministic executable plan. When
        // synthesis is impossible (no goal class matched) the original
        // proceeds unchanged — conversational plans are legitimate.
        val effectivePlan = if (
            PlanResponseSanitizer.planDefersGoal(
                plan.steps.map { it.action }, plan.goal
            )
        ) {
            android.util.Log.w(
                "AgentLoop",
                "executePlanLoop: plan defers the goal — swapping in deterministic executable steps"
            )
            runCatching { llmProviderFactory.getActiveProvider() }.getOrNull()
                ?.let { synthesizeExecutablePlan(it, plan.goal) }
                ?: plan
        } else {
            plan
        }
        // v1.4.0 chat export: fresh tool-call log per plan run — the full
        // record of every dispatched action (params, results, durations)
        // rides the final summary message for the raw-text export. The
        // epoch mechanism guarantees one active plan loop at a time, so an
        // instance-level collector is race-safe under the same invariant
        // the rest of this loop already relies on.
        activePlanToolRecords.clear()
        activePlanModelId = runCatching {
            val cfg = settingsRepository.llmConfig.first()
            cfg.selectedModelFor(cfg.activeProvider)
        }.getOrNull()
        var currentPlanState = planManager.currentPlan.value ?: return
        if (effectivePlan !== plan) {
            planManager.startNewPlan(effectivePlan, context, PlanStatus.RUNNING)
            currentPlanState = effectivePlan
        }

        // v1.3.0 round 18: the plan-stall watchdog, DECOUPLED from the plan
        // coroutine's lifetime. Round 16 cancelled it in a finally — which
        // meant any silent coroutine death (the observed wedge!) took the
        // watchdog down with it before the 7-minute threshold. Now the
        // watchdog self-terminates on its own observations only: the plan
        // went terminal, or a newer epoch superseded it. It polls on a
        // dedicated single thread (immune to pool exhaustion) and logs a
        // heartbeat every cycle so the next run's evidence shows exactly
        // what it saw. ASK parking is exempt (user thinking time is
        // unbounded by design).
        // v1.3.0 round 19: the plan's params on the record at execution
        // start — run-123's degenerate WEB_SEARCH query could only be read
        // off a screenshot because nothing logged the params the planner
        // actually wrote.
        android.util.Log.i(
            "AgentLoop",
            "plan start: goal='${effectivePlan.goal.take(60)}' steps=" + effectivePlan.steps.joinToString("; ") { st ->
                "${st.action}(" + st.params.entries.joinToString(",") { "${it.key}=${it.value.take(40)}" } + ")"
            }.take(900)
        )
        val myEpoch = planExecutionEpoch.incrementAndGet()
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val armedAt = System.currentTimeMillis()
        planWatchdogScope.launch {
            android.util.Log.i("PlanStallWatchdog", "armed: epoch=$myEpoch goal='${effectivePlan.goal.take(48)}'")
            while (true) {
                delay(PLAN_STALL_CHECK_INTERVAL_MS)
                // Retire after 30 minutes — an indefinitely parked ask (the
                // user walked away) must not heartbeat forever.
                if (System.currentTimeMillis() - armedAt > 1_800_000L) {
                    android.util.Log.i("PlanStallWatchdog", "retiring after 30min (epoch=$myEpoch)")
                    break
                }
                val current = planManager.currentPlan.value
                if (planExecutionEpoch.get() != myEpoch || current == null ||
                    current.status != PlanStatus.RUNNING
                ) {
                    android.util.Log.i(
                        "PlanStallWatchdog",
                        "exiting: epoch=${planExecutionEpoch.get()}/$myEpoch status=${current?.status}"
                    )
                    break
                }
                val parkedOnAsk = waitingSessionId != null || _pendingAsk.value != null
                val ageMs = System.currentTimeMillis() - lastProgressAt.get()
                android.util.Log.i(
                    "PlanStallWatchdog",
                    "alive: epoch=$myEpoch ageMs=$ageMs parked=$parkedOnAsk"
                )
                if (parkedOnAsk) continue
                if (ageMs > PLAN_STALL_WATCHDOG_MS) {
                    salvageStalledPlan(sessionId, myEpoch, lastProgressAt.get())
                    break
                }
            }
        }

        while (true) {
            // v1.3.0 round 16: a superseded loop (stall-watchdog salvage, or a
            // newer plan) must exit silently the moment it regains control.
            if (planExecutionEpoch.get() != myEpoch) {
                android.util.Log.w("AgentLoop", "plan loop superseded (epoch advanced) — exiting without summary")
                return
            }
            lastProgressAt.set(System.currentTimeMillis())
            // Cooperative cancellation checkpoint: ensures a cancelCurrentTask() call
            // stops this loop promptly even on iterations that finish without ever
            // hitting a suspending call that would otherwise surface the cancellation.
            currentCoroutineContext().ensureActive()

            val nextStep = planManager.getActiveStep()
            if (nextStep == null) {
                // If there are any failed steps, plan is failed. Otherwise, completed!
                val hasFailed = currentPlanState.steps.any { it.status == StepStatus.FAILED }
                if (hasFailed) {
                    planManager.updatePlanStatus(PlanStatus.FAILED)
                    speakAndSaveSummary(currentPlanState, false, sessionId)
                } else {
                    planManager.updatePlanStatus(PlanStatus.COMPLETED)
                    // Successful completion supersedes any error card still showing.
                    _chatError.value = null
                    // A purely conversational plan (every step CHAT) already
                    // delivered its reply as a chat bubble during execution —
                    // the generic success summary would only parrot it.
                    val conversationalOnly = currentPlanState.steps
                        .all { it.action.trim().uppercase() == "CHAT" }
                    if (conversationalOnly) {
                        _agentState.value = AgentState.Idle
                    } else {
                        speakAndSaveSummary(currentPlanState, true, sessionId)
                    }
                }
                break
            }

            planManager.updateStepStatus(nextStep.stepId, StepStatus.RUNNING)

            // Re-read the step's current description/action/params immediately before
            // dispatching. getStepSnapshot takes the same mutex the Plan-tab editor's
            // mutators (updateStepDescription / updateStepParams) hold, so this always
            // observes whichever edit last committed to the DB - never the stale copy
            // getActiveStep() handed back at the top of this loop iteration, which a
            // concurrent edit landing in between would otherwise silently outrun.
            val stepToExecute = planManager.getStepSnapshot(nextStep.stepId) ?: nextStep
            _agentState.value = AgentState.ExecutingPlan(stepToExecute.description)
            // v1.2.1: publish the todo step as a visible activity entry while
            // it runs (the chat-side checklist + persisted trace read these).
            publishStep(
                ActivityStep(
                    kind = ActivityStep.KIND_PLAN_STEP,
                    label = stepToExecute.description.ifBlank { stepToExecute.action },
                    detail = stepToExecute.action,
                    status = ActivityStep.STATUS_RUNNING
                )
            )

            // v1.0.5: conversational answers are delivered as agent chat
            // messages. Prose plan replies are classified into CHAT steps
            // (PlanResponseSanitizer.classifyProseReply), but the dispatcher
            // never had a CHAT handler — every conversational answer died with
            // "Action 'CHAT' is not registered in ActionDispatcher" (v1.0.4
            // field failure: the capability-audit question FAILED as a plan,
            // and "ok start" → "Let's build it!" was followed by silence).
            // Deliver the reply right here: chat bubble + TTS + COMPLETED.
            if (stepToExecute.action.trim().uppercase() == "CHAT") {
                // v1.6.0 round 3 (critic-21): CHAT steps deliver model text
                // verbatim - the answer-shape gate applies here too (a leading
                // tool-syntax run in a plan's chat reply shipped as-is).
                val rawResponse = stepToExecute.params["response"]
                    ?: stepToExecute.params["message"]
                    ?: stepToExecute.params["text"]
                    ?: ""
                val response = AnswerHygiene.sanitizeFinalAnswer(rawResponse)
                    ?: "I worked on that, but the reply came back in a shape I couldn't use. Could you ask me again?"
                if (response.isNotBlank()) {
                    val chatMsg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = response,
                        sender = ChatMessage.Sender.AGENT
                    )
                    memoryManager.storeMessage(chatMsg, sessionId)
                    conversationRepository.insertMessage(sessionId, chatMsg)
                    _chatError.value = null
                    announce(response)
                }
                completeLastRunningStep(ActivityStep.STATUS_DONE, "reply delivered")
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.COMPLETED,
                    result = response.ifBlank { "Reply delivered." }
                )
                currentPlanState = planManager.currentPlan.value ?: break
                continue
            }

            // Resolve parameters from prior step results
            val resolvedParams = actionSequenceExecutor.resolveParameters(
                params = stepToExecute.params,
                priorSteps = currentPlanState.steps
            )

            // Execute the action dispatcher
            // v1.3.0 (run-107): every non-interactive step is bounded by a hard
            // wall clock — a stalled network path fails the step honestly
            // instead of holding the turn (and the E2E driver) hostage for
            // minutes. ASK_USER parks on user response BY DESIGN (round-6)
            // and must stay unbounded.
            val isUserInteractionStep =
                stepToExecute.action.trim().uppercase() == "ASK_USER"
            // v1.4.0 chat export: wall-clock start for the per-step record.
            val stepExecStartAt = System.currentTimeMillis()
            var actionResult = try {
                var result = if (isUserInteractionStep) {
                    actionSequenceExecutor.dispatch(stepToExecute.action, resolvedParams, context)
                } else {
                    withTimeout(ACTION_STEP_TIMEOUT_MS) {
                        actionSequenceExecutor.dispatch(stepToExecute.action, resolvedParams, context)
                    }
                }

                resolveNeedsInput(result, stepToExecute.action, resolvedParams, context, sessionId)
            } catch (e: TimeoutCancellationException) {
                // OUR step bound fired (the only withTimeout in this scope):
                // convert to an honest step failure, not a turn cancellation.
                android.util.Log.w(
                    "AgentLoop",
                    "step '${stepToExecute.action}' exceeded ${ACTION_STEP_TIMEOUT_MS / 1000}s — abandoning it"
                )
                ActionResult(
                    false,
                    null,
                    "The ${stepToExecute.action} step did not finish within " +
                        "${ACTION_STEP_TIMEOUT_MS / 1000} seconds — the network path may be stalled. " +
                        "The step was abandoned so the plan can continue."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AgentLoop", "Exception executing action ${stepToExecute.action}: ${e.localizedMessage}", e)
                ActionResult(false, null, e.localizedMessage ?: "Unknown execution error")
            }

            // v1.2.1: complete the visible step as soon as the outcome exists —
            // every branch below maps onto done or error for the activity trace.
            completeLastRunningStep(
                if (actionResult.success || actionResult is ActionResult.PendingUserAction) {
                    ActivityStep.STATUS_DONE
                } else {
                    ActivityStep.STATUS_ERROR
                },
                (actionResult.data ?: actionResult.error ?: "").toString()
            )

            // Redaction must be based on the dispatcher's canonical mapped action,
            // not the raw plan action string — the dispatcher accepts non-canonical
            // names (e.g. "EMAIL", "send-email") that still execute as SEND_EMAIL.
            val canonicalActionName = actionDispatcher.canonicalActionName(stepToExecute.action)

            // v1.4.0 chat export: the full per-step record — resolved params
            // (sanitized like task_history), redacted + capped result, real
            // duration. Same fidelity as the chat-path harness records, so the
            // export's toolCalls array reads identically for plan-mode turns.
            run {
                val capped = ToolCallRecords.capResult(
                    actionResult.data?.let(com.tsfdroid.ai.core.crash.CrashLogRedactor::redact)
                )
                activePlanToolRecords.add(
                    ToolCallRecord(
                        tool = stepToExecute.action,
                        action = canonicalActionName,
                        argumentsRaw = stepToExecute.params.entries.joinToString(", ") {
                            "${it.key}=${it.value.take(120)}"
                        },
                        params = ExecutionHistoryPrivacy.sanitizeParams(canonicalActionName, resolvedParams),
                        success = actionResult.success,
                        result = capped.first,
                        truncated = capped.second,
                        error = actionResult.error?.let(com.tsfdroid.ai.core.crash.CrashLogRedactor::redact),
                        startedAt = stepExecStartAt,
                        durationMs = System.currentTimeMillis() - stepExecStartAt
                    )
                )
            }

            try {
                withTimeout(MEMORY_LOG_TIMEOUT_MS) {
                    memoryManager.logTaskExecution(
                        stepId = stepToExecute.stepId,
                        planId = currentPlanState.planId,
                        description = ExecutionHistoryPrivacy.sanitizeDescription(
                            canonicalActionName,
                            stepToExecute.description
                        ),
                        actionType = stepToExecute.action,
                        params = ExecutionHistoryPrivacy.sanitizeParams(canonicalActionName, resolvedParams),
                        success = actionResult.success,
                        resultData = actionResult.data?.let(com.tsfdroid.ai.core.crash.CrashLogRedactor::redact),
                        errorMessage = actionResult.error?.let(com.tsfdroid.ai.core.crash.CrashLogRedactor::redact)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AgentLoop", "Failed to log task execution: ${e.localizedMessage}")
            }

            if (actionResult.success) {
                lastExecutedActionsBySession[sessionId] = LastExecutedAction(stepToExecute.action, resolvedParams)
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.COMPLETED,
                    result = actionResult.data ?: "Completed successfully."
                )
                // v1.0.6: created artifacts are FILES, not log lines — emit a
                // chat attachment card so the user can open the HTML/PDF the
                // agent just built directly from the conversation.
                emitArtifactCardIfNeeded(canonicalActionName, resolvedParams, actionResult, sessionId)
            } else if (actionResult is ActionResult.PendingUserAction) {
                // The action handed control to the user (e.g. the dialer is open awaiting a
                // tap). Nothing failed, so no fallback and no failure replan - surface the
                // hand-off in chat and speech, record it as the step result, and let the
                // normal re-evaluation path decide what remains.
                handlePendingUserAction(actionResult, sessionId)
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.COMPLETED,
                    result = actionResult.message
                )
            } else if (actionResult is ActionResult.UserActionRequired) {
                // A user-facing flow (for example email compose) is not an
                // executed side effect. Do not run an automated fallback or
                // allow the plan to treat the step as completed.
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.FAILED,
                    error = actionResult.error ?: "User action is required before this step can complete."
                )
                // Skip the generic re-evaluation below: its prompt allows adding
                // alternative steps, which would let the agent launch another
                // action or duplicate composer while the user is still reviewing
                // the one just opened. Move on to the next plan step (if any)
                // instead of triggering an automated replan for this failure.
                currentPlanState = planManager.currentPlan.value ?: break
                continue
            } else if (actionResult is ActionResult.UnknownAction) {
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.FAILED,
                    error = actionResult.error ?: "Action execution failed."
                )

                // Update current state of plan to include the failed step status
                currentPlanState = planManager.currentPlan.value ?: break

                // Trigger learning extraction
                reEvalEngine.get().extractLearning(stepToExecute.action, currentPlanState.goal)

                // Trigger silent replanning
                val completed = currentPlanState.steps.filter { it.status == StepStatus.COMPLETED }
                val remaining = currentPlanState.steps.filter { it.status == StepStatus.PENDING }

                val replan = try {
                    withTimeout(ADVISORY_CALL_TIMEOUT_MS) {
                        reEvalEngine.get().replanAfterUnknownAction(
                            originalGoal = currentPlanState.goal,
                            failedStep = stepToExecute,
                            completedSteps = completed,
                            remainingSteps = remaining,
                            planId = currentPlanState.planId
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    // v1.3.0 run-113: a wedged replan call degrades to null —
                    // the same honest path as the LLMException catch below.
                    android.util.Log.w(
                        "AgentLoop",
                        "Replan after unknown action exceeded ${ADVISORY_CALL_TIMEOUT_MS / 1000}s — continuing"
                    )
                    null
                } catch (e: LLMException) {
                    // v1.0.6: a failed REPLANNER must not kill the plan — the
                    // failed step is already FAILED; remaining steps are still
                    // PENDING and remain executable. Log and fall through to
                    // the normal loop so the plan finishes its own steps.
                    android.util.Log.w("AgentLoop", "Replan after unknown action failed: ${e.localizedMessage}")
                    null
                }

                if (replan == null) {
                    currentPlanState = planManager.currentPlan.value ?: break
                    continue
                }

                if (replan.speech.isNotEmpty()) {
                    onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(replan.speech))
                }

                when (replan.decision.uppercase()) {
                    "ABANDON" -> {
                        planManager.updatePlanStatus(PlanStatus.FAILED)
                        speakAndSaveSummary(currentPlanState, false, sessionId)
                        return
                    }
                    "MODIFY" -> {
                        if (replan.updatedPlan != null) {
                            val mergedSteps = currentPlanState.steps.filter { it.status != StepStatus.PENDING } +
                                    replan.updatedPlan.steps.filter { step ->
                                        currentPlanState.steps.none { it.stepId == step.stepId }
                                    }
                            planManager.startNewPlan(currentPlanState.copy(steps = mergedSteps), context)
                            // Auto-approved plans: silently injected steps must not run
                            // past the allowlist. Re-check the merged plan's pending
                            // steps; if any is blocked, park the WHOLE remainder back
                            // in the PlanProposed gate. YOLO auto-approves everything
                            // by design, so this re-check only gates AUTO mode.
                            if (autoApproved) {
                                val liveConfig = settingsRepository.llmConfig.first()
                                val approval = liveConfig.approvalSettings()
                                val mergedPlan = planManager.currentPlan.value
                                val pending = mergedPlan?.steps?.filter { it.status == StepStatus.PENDING }.orEmpty()
                                if (mergedPlan != null && !AutoApprovalPolicy.shouldAutoApprove(
                                        approval.mode,
                                        approval.grantedActions,
                                        mergedPlan.copy(steps = pending)
                                    )) {
                                    // startNewPlan persisted the merged plan as RUNNING;
                                    // reflect the approval gate in the stored status too so
                                    // the Plan tab and history match the PlanProposed state.
                                    planManager.updatePlanStatus(PlanStatus.PROPOSED)
                                    proposePlan(
                                        planManager.currentPlan.value ?: mergedPlan.copy(status = PlanStatus.PROPOSED),
                                        sessionId
                                    )
                                    return
                                }
                            }
                        }
                    }
                    else -> {
                        planManager.updatePlanStatus(PlanStatus.FAILED)
                        speakAndSaveSummary(currentPlanState, false, sessionId)
                        return
                    }
                }

                // Refresh current state of plan and continue
                currentPlanState = planManager.currentPlan.value ?: break
                continue
            } else {
                // Try fallback action
                if (actionSequenceExecutor.shouldAttemptFallback(actionResult, stepToExecute)) {
                    val fallbackResult = try {
                        actionSequenceExecutor.dispatch(stepToExecute.fallback, resolvedParams, context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ActionResult(false, null, e.localizedMessage ?: "Unknown execution error")
                    }
                    if (fallbackResult.success) {
                        planManager.updateStepStatus(
                            stepToExecute.stepId,
                            StepStatus.COMPLETED,
                            result = "Primary failed: ${actionResult.error}. Fallback execution succeeded: ${fallbackResult.data}"
                        )
                    } else {
                        planManager.updateStepStatus(
                            stepToExecute.stepId,
                            StepStatus.FAILED,
                            error = "Primary failed: ${actionResult.error}. Fallback failed: ${fallbackResult.error}"
                        )
                    }
                } else {
                    planManager.updateStepStatus(
                        stepToExecute.stepId,
                        StepStatus.FAILED,
                        error = actionResult.error ?: "Action execution failed."
                    )
                }
            }

            // Refresh current state of plan
            // v1.3.0 run-113 forensic: a NULL plan state here ends the turn
            // SILENTLY (no summary, no reply — the gold-test symptom). Log it
            // so the next run's evidence distinguishes this from a wedge.
            currentPlanState = planManager.currentPlan.value ?: run {
                android.util.Log.w("AgentLoop", "plan state vanished after step '${stepToExecute.action}' — ending plan silently")
                break
            }

            // v1.6.0 round 3 (critic-21): a user-CANCELLED step is a HARD
            // abort. The advisory re-eval LLM must never resurrect a plan the
            // user stopped (the field's four ignored stop commands were
            // exactly this class - a CONTINUE verdict after "stop").
            val userCancelled = currentPlanState.steps.any {
                it.status == StepStatus.FAILED && it.error?.contains("Cancelled by user") == true
            }
            if (userCancelled) {
                android.util.Log.w("AgentLoop", "plan aborted by user stop - skipping re-evaluation entirely")
                planManager.updatePlanStatus(PlanStatus.FAILED)
                speakAndSaveSummary(currentPlanState, false, sessionId)
                return
            }

            // Re-evaluate Plan Loop
            val completed = currentPlanState.steps.filter { it.status == StepStatus.COMPLETED }
            val failed = currentPlanState.steps.filter { it.status == StepStatus.FAILED }
            val remaining = currentPlanState.steps.filter { it.status == StepStatus.PENDING }

            if (failed.isEmpty() && remaining.isEmpty()) {
                continue
            }

            val reEval = try {
                withTimeout(ADVISORY_CALL_TIMEOUT_MS) {
                    reEvalEngine.get().evaluateStepResult(
                        originalGoal = currentPlanState.goal,
                        completedSteps = completed,
                        failedSteps = failed,
                        remainingSteps = remaining,
                        planId = currentPlanState.planId
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // v1.3.0 run-113 (gold turn, both passes): the evaluator's
                // reply came back 2-chars-with-heavy-reasoning, the
                // post-call tail then wedged the plan coroutine SILENTLY for
                // 10 minutes — no advisory-failure log ever fired. A wedged
                // evaluator now degrades to the exact same advisory-CONTINUE
                // path a malformed reply takes: the plan marches on.
                android.util.Log.w(
                    "AgentLoop",
                    "Step re-evaluation exceeded ${ADVISORY_CALL_TIMEOUT_MS / 1000}s — treating as CONTINUE"
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // v1.0.6: the evaluator is ADVISORY. A malformed/failed
                // evaluation call used to fail the WHOLE plan here — the PDF
                // field failure died exactly at this boundary (step 1 done,
                // step 2 still PENDING, plan FAILED, MALFORMED_RESPONSE card).
                // The plan must march on deterministically: log and treat as
                // CONTINUE so every remaining step still executes.
                android.util.Log.w("AgentLoop", "Step re-evaluation failed (treating as CONTINUE): ${e.localizedMessage}")
                null
            }

            if (reEval == null) {
                currentPlanState = planManager.currentPlan.value ?: run {
                    android.util.Log.w(
                        "AgentLoop",
                        "plan state vanished after null re-eval — ending plan silently"
                    )
                    break
                }
                continue
            }

            // Speak post-step evaluation speech if any
            if (reEval.speech.isNotEmpty()) {
                onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(reEval.speech))
            }

            when (reEval.decision.uppercase()) {
                "ABANDON" -> {
                    planManager.updatePlanStatus(PlanStatus.FAILED)
                    speakAndSaveSummary(currentPlanState, false, sessionId)
                    return
                }
                "MODIFY" -> {
                    if (reEval.updatedPlan != null) {
                        val mergedSteps = currentPlanState.steps.filter { it.status != StepStatus.PENDING } +
                                reEval.updatedPlan.steps.filter { step ->
                                    currentPlanState.steps.none { it.stepId == step.stepId }
                                }
                        planManager.startNewPlan(currentPlanState.copy(steps = mergedSteps), context)
                    }
                }
                "CONTINUE" -> {
                    // Do nothing, continue to next step
                }
            }
        }
    }

    /**
     * v1.3.0 round 16/18: the plan-stall salvage. Runs on the watchdog's
     * dedicated thread — fully INDEPENDENT of the (possibly never-resuming)
     * plan coroutine and of every dispatcher pool — so it
     * works no matter where the wedge sits. Everything it does is bounded,
     * mutex-free, and deterministic (no LLM call: a stalling free tier must
     * not delay the salvage too). The reply quotes the completed steps'
     * results verbatim, so the user — and the E2E — get the substance the
     * turn already gathered (e.g. the gold-price search listing) instead of
     * a silent wedge.
     */
    private suspend fun salvageStalledPlan(sessionId: String, stalledEpoch: Int, stalledSince: Long) {
        // Authority: only the watchdog for the CURRENT epoch salvages.
        if (planExecutionEpoch.get() != stalledEpoch) return
        val plan = planManager.currentPlan.value ?: return

        android.util.Log.w(
            "AgentLoop",
            "PLAN STALL WATCHDOG: no plan-loop progress for " +
                "${(System.currentTimeMillis() - stalledSince) / 1000}s on goal '${plan.goal.take(60)}' — " +
                "salvaging the turn with the completed steps' results"
        )

        // Supersede the wedged loop before touching shared state: if it ever
        // resumes, its very next iteration-head check exits without a summary.
        planExecutionEpoch.incrementAndGet()

        // The salvage itself must never wedge (e.g. the chat DB write could
        // be what's stuck): bound the whole recovery so the state transitions
        // and the log evidence always land.
        try {
            withTimeout(30_000L) {
                // Terminal-mark the plan WITHOUT the plan mutex — the wedged
                // coroutine may be suspended inside `withLock` holding it.
                planManager.forceStatusFromWatchdog(PlanStatus.FAILED)

                val results = AnswerEngine.extractiveAnswer(plan.goal, plan.steps)
                    ?: PlanResponseSanitizer.stepResultSummary(plan.steps)
                val summaryText = if (!results.isNullOrBlank()) {
                    "I hit a stall mid-turn and had to recover, but I had already gathered this:\n\n$results"
                } else {
                    "I hit a stall mid-turn and couldn't finish \"${plan.goal.take(80)}\". " +
                        "Nothing half-finished is still running — please ask again."
                }

                val assistantMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = summaryText,
                    sender = ChatMessage.Sender.AGENT,
                    modelBadge = "System",
                    stepsJson = com.tsfdroid.ai.core.harness.ActivitySteps.encode(currentStepsSnapshot())
                )
                try {
                    memoryManager.storeMessage(assistantMsg, sessionId)
                } catch (e: Exception) {
                    android.util.Log.w("AgentLoop", "stall salvage: memory store failed: ${e.localizedMessage}")
                }
                conversationRepository.insertMessage(sessionId, assistantMsg)

                _liveThinking.value = null
                announce(summaryText)
                android.util.Log.i("AgentLoop", "PLAN STALL WATCHDOG: salvaged reply saved (${summaryText.length}c)")
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.e("AgentLoop", "stall salvage itself timed out — forcing terminal state")
            planManager.forceStatusFromWatchdog(PlanStatus.FAILED)
            _liveThinking.value = null
            _agentState.value = AgentState.Idle
        } catch (e: Exception) {
            android.util.Log.e("AgentLoop", "stall salvage failed: ${e.localizedMessage}")
            planManager.forceStatusFromWatchdog(PlanStatus.FAILED)
            _liveThinking.value = null
            _agentState.value = AgentState.Idle
        }
    }

    /**
     * True only when the user's query refers to what is currently on screen,
     * so a screenshot is genuinely needed as context.
     */
    private fun needsScreenContext(query: String): Boolean {
        val lower = query.lowercase()
        val screenPhrases = listOf(
            "screen", "screenshot", "looking at", "this page", "this app",
            "what am i", "what's this", "what is this", "read this",
            "displayed", "showing", "what do you see", "remember this",
            "save this", "save meeting details", "save the meeting", "important information",
            "save to notes", "add to notes", "add this to my notes", "read and remember"
        )
        return screenPhrases.any { lower.contains(it) }
    }

    private data class NeedsInputRetry(
        val result: ActionResult,
        val params: Map<String, String>
    )

    private suspend fun resolveNeedsInput(
        initialResult: ActionResult,
        actionName: String,
        initialParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): ActionResult {
        var result = initialResult
        var params = initialParams

        repeat(MAX_NEEDS_INPUT_PROMPTS) {
            val needsInput = result as? ActionResult.NeedsInput ?: return result
            val retry = if (needsInput.metadata["type"] == "contact_picker") {
                handleContactPicker(needsInput, actionName, params, context, sessionId)
            } else {
                handleNeedsInput(needsInput, actionName, params, context, sessionId)
            }
            result = retry.result
            params = retry.params
        }

        return ActionResult.Failure(
            errorMsg = "Too many input prompts for $actionName",
            fallback = "Try the command again with all required details."
        )
    }

    /**
     * Handle contact disambiguation when an action returns NeedsInput with contact_picker metadata.
     * Shows options to user, waits for selection, stores preference, re-executes action.
     */
    private suspend fun handleContactPicker(
        pickerResult: ActionResult.NeedsInput,
        actionName: String,
        originalParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): NeedsInputRetry {
        val meta = pickerResult.metadata
        val matchesJson = meta["matches"] ?: return NeedsInputRetry(pickerResult, originalParams)
        val query = meta["query"] ?: ""

        // Parse the matches back from JSON
        val matches: List<Map<String, String>> = try {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<Map<String, String>>>(matchesJson)
        } catch (e: Exception) {
            android.util.Log.e("AgentLoop", "Failed to parse contact matches: ${e.message}")
            return NeedsInputRetry(pickerResult, originalParams)
        }

        if (matches.isEmpty()) return NeedsInputRetry(pickerResult, originalParams)

        // Show picker question to user via chat
        val optionsText = pickerResult.options.joinToString("\n")
        val pickerMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "${pickerResult.question}\n\n$optionsText",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System",
            contactPickerData = matchesJson
        )
        conversationRepository.insertMessage(sessionId, pickerMsg)
        onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(pickerResult.question))

        // Wait for user response
        val userSelection = awaitUserResponse(sessionId)

        // v1.6.0 (field P1-4): the reply is already persisted by processQuery
        // (single-write); P0-8b: a stop reply cancels the plan.
        if (GoalContract.isStopCommand(userSelection)) {
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "Cancelled by user",
                    fallback = "The user said stop - the task was dropped."
                ),
                originalParams
            )
        }

        // Resolve user selection to a contact
        val selectedContact = when {
            // User typed a number: "1", "2", "3"
            userSelection.trim().toIntOrNull() != null -> {
                val index = userSelection.trim().toInt() - 1
                matches.getOrNull(index)
            }

            // User said "first", "second", "third"
            userSelection.lowercase().contains("first") -> matches.getOrNull(0)
            userSelection.lowercase().contains("second") -> matches.getOrNull(1)
            userSelection.lowercase().contains("third") -> matches.getOrNull(2)
            userSelection.lowercase().contains("fourth") -> matches.getOrNull(3)
            userSelection.lowercase().contains("fifth") -> matches.getOrNull(4)

            // User typed the name
            else -> {
                matches.find { contact ->
                    userSelection.contains(contact["name"] ?: "", ignoreCase = true)
                } ?: matches.find { contact ->
                    (contact["name"] ?: "").contains(userSelection.trim(), ignoreCase = true)
                }
            }
        }

        if (selectedContact == null) {
            // Couldn't match — tell user and fail gracefully
            val failMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = "I didn't catch that. Please try again with the contact's name.",
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "System"
            )
            conversationRepository.insertMessage(sessionId, failMsg)
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "Contact selection not understood",
                    fallback = "Please try the command again"
                ),
                originalParams
            )
        }

        val phone = selectedContact["phone"] ?: return NeedsInputRetry(
            ActionResult.Failure(
                errorMsg = "No phone number for selected contact",
                fallback = "Try again"
            ),
            originalParams
        )
        val name = selectedContact["name"] ?: "Contact"

        // Remember this choice for next time
        memoryManager.storeContactPreference(
            query = query,
            contact = Contact(name = name, phoneNumber = phone)
        )

        // Confirm selection to user
        val confirmMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "Got it! Using $name.",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(sessionId, confirmMsg)

        // Re-execute the original action with resolved contact
        val resolvedParams = originalParams.toMutableMap()
        resolvedParams["contact"] = phone
        if (meta.containsKey("message")) {
            resolvedParams["message"] = meta["message"]!!
        }

        return NeedsInputRetry(
            actionDispatcher.execute(actionName, resolvedParams, context),
            resolvedParams
        )
    }

    /**
     * Generic missing-parameter prompt. Shows the question, waits for the user's
     * reply, and re-executes the action with the answer injected.
     */
    private suspend fun handleNeedsInput(
        needsInput: ActionResult.NeedsInput,
        actionName: String,
        originalParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): NeedsInputRetry {
        // v1.3.0: pre-arm the answer routing (same race guard as
        // askUserQuestion — an answer typed against the freshly posted
        // question must never register as a new query).
        waitingSessionId = sessionId
        // v1.3.0 round-7: a NeedsInput re-ask is still the plan asking the
        // user — the final summary must acknowledge the answer.
        askedUserDuringPlan = true
        val optionsText = if (needsInput.options.isNotEmpty()) {
            "\n\n" + needsInput.options.joinToString("\n") { "- $it" }
        } else {
            ""
        }
        // v1.6.0 (field P0-8d): prompt copy speaks USER language - the
        // field prompts exposed internal param names ("I need the
        // searchText... Text to find the field").
        val paramKey = paramKeyForNeedsInput(needsInput, actionName)
        val humanQuestion = GoalContract.humanizeParamPrompt(actionName, paramKey, needsInput.question)
        val promptMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = humanQuestion + optionsText,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System",
            mode = "AGENT",
            // v1.3.0: the plan-path ask gets the SAME tappable chips and the
            // SAME answer surface as the chat-path ask_user tool — the user
            // must never have to guess where to type the answer, whichever
            // pipeline asked the question (E2E cap21 evidence: the planner
            // routes "ask me" requests to the ASK_USER action, and the old
            // bare-text prompt left the input bar looking like normal chat).
            askOptionsJson = if (needsInput.options.isNotEmpty()) {
                com.tsfdroid.ai.data.models.serializeAskOptions(
                    com.tsfdroid.ai.data.models.AskOptions(
                        header = needsInput.question.take(30).ifBlank { "Quick answer" },
                        options = needsInput.options
                    )
                )
            } else {
                null
            }
        )
        conversationRepository.insertMessage(sessionId, promptMsg)
        onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(needsInput.question))

        // v1.3.0: publish the answer surface while the turn is parked on
        // this question — cleared when the answer lands (or the task dies).
        _pendingAsk.value = PendingAsk(sessionId, needsInput.question, needsInput.options)
        val answer = try {
            awaitUserResponse(sessionId).trim()
        } finally {
            _pendingAsk.value = null
            // Pre-arm cleanup for the cancelled-before-park case.
            if (pendingUserInput == null && waitingSessionId == sessionId) {
                waitingSessionId = null
            }
        }
        if (answer.isEmpty()) {
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "No value provided for $actionName",
                    fallback = "Try the command again with the missing detail."
                ),
                originalParams
            )
        }

        // v1.6.0 (field P0-8b): the cancel lexicon. Four explicit field stop
        // commands ("stop don't need to do anything") were consumed as
        // parameter text while the plan kept running for 28 minutes. A stop
        // reply aborts the WHOLE plan with an honest summary.
        if (GoalContract.isStopCommand(answer)) {
            android.util.Log.w("AgentLoop", "needs-input answer is a stop command - aborting plan")
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "Cancelled by user",
                    fallback = "The user said stop - the task was dropped."
                ),
                originalParams
            )
        }

        // v1.6.0 (field P0-8e): the per-PLAN prompt budget. The per-action
        // cap of 5 never bit in the field because broken steps cycled the
        // prompts ACROSS steps (searchText -> content -> direction ->
        // searchText...). Eight prompts per plan is generous for legitimate
        // use and fatal for a loop.
        needsInputPromptsThisPlan++
        if (needsInputPromptsThisPlan > MAX_NEEDS_INPUT_PROMPTS_PER_PLAN) {
            android.util.Log.w("AgentLoop", "plan exceeded $MAX_NEEDS_INPUT_PROMPTS_PER_PLAN needs-input prompts - aborting")
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "Too many prompts for this plan",
                    fallback = "I asked too many times and stopped - could you rephrase the request with all details?"
                ),
                originalParams
            )
        }

        // v1.3.0 round-8: an ask answer is often a durable preference
        // ("Which city do you prefer?" -> "Pune") — exactly what the Hermes
        // memory is for. The normal learnFromExchange hook lives at the end
        // of a chat turn, and the answer path returns BEFORE it, so ask
        // answers were never learned. Feed the Q&A pair to the learner in
        // the background (never blocks, never fails the ask).
        scope.launch {
            runCatching {
                memoryLearner.learnFromExchange(
                    userText = "The assistant asked me: \"${needsInput.question}\"\nMy answer: $answer",
                    assistantText = needsInput.question
                )
            }
        }

        // v1.6.0 (field P1-4): NO echo insert here. processQuery already
        // persisted this reply exactly once when it routed it into the park
        // (the double-persist produced 10 duplicate USER rows across the
        // field exports, 15-30 ms apart).
        val newParams = originalParams.toMutableMap().apply { put(paramKey, answer) }
        return NeedsInputRetry(
            actionDispatcher.execute(actionName, newParams, context),
            newParams
        )
    }

    /**
     * Tell the user that a step is now waiting on them. The agent cannot observe the
     * user's tap, so this is a hand-off notice, not a prompt that blocks the plan.
     */
    private suspend fun handlePendingUserAction(
        pending: ActionResult.PendingUserAction,
        sessionId: String
    ) {
        val pendingMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = pending.message,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(sessionId, pendingMsg)
        onSpeakCallback?.invoke(com.tsfdroid.ai.core.util.SpeechText.forSpeech(pending.message))
    }

    /**
     * Auto-approved plans never show the approval modal, so leave a badged
     * trace message in the chat instead - the audit trail the spec requires.
     * Speech leads with "Running:" so a hands-free user knows no approval
     * prompt is coming.
     */
    private suspend fun recordAutoApprovedTrace(plan: Plan, mode: AutoMode, sessionId: String) {
        val badge = if (mode == AutoMode.YOLO) "YOLO" else "Auto-approved"
        val stepLines = plan.steps.joinToString("\n") { "• ${it.description}" }
        // v1.6.0 (field P2-3): "Running:" + raw echo of the user query read
        // like log noise; the trace keeps its audit content but opens like a
        // person, and the unfulfillable-goal note (B1) rides it too.
        val capabilityNote = GoalContract.unfulfillableGoalNote(plan.goal)
        val traceText = "On it - ${plan.goal} (${plan.steps.size} steps)\n$stepLines" +
            (capabilityNote?.let { "\nNote: $it" } ?: "")
        val traceMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = traceText,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = badge,
            mode = "AGENT"
        )
        conversationRepository.insertMessage(sessionId, traceMsg)
        memoryManager.storeMessage(traceMsg, sessionId)
        onSpeakCallback?.invoke("On it: ${plan.goal}")
    }

    private suspend fun speakAndSaveSummary(plan: Plan, isSuccess: Boolean, sessionId: String) {
        // v1.4.0: the reply badge reflects WHO wrote the final answer —
        // the model when synthesis ran, System for deterministic paths.
        var summaryBadge = "System"
        // v1.4.0: drain the turn's artifacts BEFORE composing — a pure
        // artifact turn (CREATE_PDF/WRITE_FILE only) answers with the clean
        // file line, never the canned "/storage/..." path text (the third
        // field complaint). Data turns keep the synthesis ladder, which
        // names files per the contract.
        // v1.6.0 (field P1-6): the names this plan ACTUALLY wrote — captured
        // before the drain so the claim audit below can compare against them.
        val writtenThisPlan = collectedArtifacts.mapNotNull { json ->
            runCatching { org.json.JSONObject(json).optString("name") }.getOrNull()
        }.map { it.lowercase() }.toSet()
        val artifactJson = drainCollectedArtifact()
        val artifactName = artifactJson?.let {
            runCatching { org.json.JSONObject(it).optString("name") }.getOrNull()
        }
        val summaryText = if (isSuccess) {
            // v1.6.0 B1 TRUTHFULNESS GATE (field P0-1 — the cab): a plan whose
            // steps contain NO world-changing action can never earn a
            // completion claim. The ask-only honest summary outranks the LLM
            // confirmation, which "confirmed" a booking that never happened.
            val askOnlyPlan = plan.steps.isNotEmpty() && plan.steps.all {
                it.status != StepStatus.FAILED &&
                    it.action.trim().uppercase() in ASK_ONLY_ACTIONS
            }
            val honestAskOnly = if (askOnlyPlan) honestAskOnlySummary(plan) else null
            // v1.3.0 round-7: when the plan asked the user something, the
            // answer must round-trip back through the MODEL for the final
            // reply (the opencode question-tool contract). Run-102 cap21
            // evidence: the ask step completed with "Pune" in its result,
            // then humanizeGoalDone rendered "All done!" — the user's answer
            // was swallowed by string heuristics. Try the LLM confirmation
            // first; any failure falls to the deterministic assembly, which
            // now quotes the answers too.
            //
            // v1.4.0 Hermes answer engine (the 2026-10-03 field screenshots):
            // for every OTHER plan the final reply is SYNTHESIZED by the
            // model from the step results — raw tool output (search
            // listings, "PDF created: /storage/..." paths) is context,
            // never the deliverable. Ladder: ask-confirmation → clean
            // artifact line → synthesis → extractive fallback → canned
            // listing (last resort).
            val confirmed = if (honestAskOnly != null) null else askConfirmedSummary(plan)
            when {
                honestAskOnly != null -> honestAskOnly
                confirmed != null -> confirmed
                // Pure artifact turn: the card carries the file; the reply
                // is one clean sentence, exactly like ChatGPT's delivery.
                artifactName != null && !AnswerEngine.needsSynthesis(plan.steps) ->
                    "I've created $artifactName — it's ready to open below."
                else -> {
                    val synthesized = synthesizedSummary(plan)
                    if (synthesized != null) {
                        summaryBadge = synthesized.second
                        synthesized.first
                    } else {
                        AnswerEngine.extractiveAnswer(plan.goal, plan.steps)
                            ?: cannedSuccessSummary(plan)
                    }
                }
            }
        } else {
            // Log the technical errors but DON'T show them to the user
            val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
            failedSteps.forEach { step ->
                android.util.Log.e("AgentLoop",
                    "Step '${step.action}' failed: ${step.error ?: "unknown"}")
            }
            
            // Check if any failed step has a user-friendly error message
            // (e.g. "I've opened the chat... please tap send")
            val userFacingError = failedSteps.firstNotNullOfOrNull { step ->
                step.error?.takeIf { error ->
                    // Include errors that contain actionable guidance for the user
                    error.contains("opened", ignoreCase = true) ||
                    error.contains("please", ignoreCase = true) ||
                    error.contains("check", ignoreCase = true) ||
                    error.contains("tap", ignoreCase = true) ||
                    error.contains("couldn't confirm", ignoreCase = true)
                }
            }
            
            // v1.6.0 (field P0-8): a plan the user STOPPED gets the honest
            // stopped message, never a failure apology for work they ended.
            val cancelledByUser = failedSteps.any { it.error?.contains("Cancelled by user") == true }
            if (cancelledByUser) {
                "Stopped - I dropped that task; nothing else will run." +
                    (partialFindingsSummary(plan)?.let { "\n\n$it" } ?: "")
            } else
            // v1.4.0 (run-37106169790 cap24 forensics): a FAILED plan that
            // already gathered real data must DELIVER it — the India-VIX turn
            // completed a legitimate search, two over-specific follow-ups
            // got rejected, and the user's data vanished behind "Sorry, that
            // didn't work out". The honest partial answer (what was found +
            // what failed) always beats the generic failure.
            userFacingError ?: partialFindingsSummary(plan) ?: humanizeFailure(plan.goal)
        }

        // v1.6.0 (field P1-6): the CLAIM AUDIT — every filename the summary
        // promises must exist among the artifacts this plan actually wrote.
        // The field's "Here are both files, complete and ready to use." said
        // so while the second file was never written.
        val claimedFiles = Regex("\\b[A-Za-z0-9_][A-Za-z0-9_\\-]*\\.(?:md|csv|txt|json|pdf|py|html?|yaml|js)\\b")
            .findAll(summaryText).map { it.value.lowercase() }.toSet()
        val missingFiles = claimedFiles - writtenThisPlan
        val finalSummary = if (missingFiles.isNotEmpty()) {
            summaryText + "\n\n(Honesty note: I mentioned " +
                missingFiles.joinToString(", ") { "'$it'" } +
                " but didn't actually create " +
                (if (missingFiles.size == 1) "it" else "them") +
                " - the write didn't happen.)"
        } else {
            summaryText
        }
        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = finalSummary,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = summaryBadge,
            mode = "AGENT",
            // v1.4.0: created files ride the summary as a ChatGPT-style
            // card at the END of the chat — the drained artifact attaches
            // to this message itself; extras land as follow-up cards.
            attachmentJson = artifactJson?.also {
                android.util.Log.i("AgentLoop", "artifact card attached to summary: ${it.take(80)}")
            },
            // v1.2.1: the plan's full visible-step trace rides the summary so
            // the ACTIVITY section in chat shows exactly what the todo list did.
            stepsJson = com.tsfdroid.ai.core.harness.ActivitySteps.encode(currentStepsSnapshot()),
            // v1.4.0 chat export: the plan's tool-call log (one record per
            // dispatched action: resolved params, redacted + capped results,
            // real durations) plus the model id that ran the loop — the
            // plan-mode counterpart of the chat path's persistence.
            modelId = activePlanModelId,
            toolCallsJson = ToolCallRecords.encode(activePlanToolRecords.toList()),
            // v1.6.0 (field P2-5): the plan path's wall-clock duration.
            turnWallMs = activePlanWallStartAt.takeIf { it > 0 }
                ?.let { System.currentTimeMillis() - it }
        )
        memoryManager.storeMessage(assistantMsg, sessionId)
        conversationRepository.insertMessage(sessionId, assistantMsg)
        // v1.4.0: any artifacts beyond the first land as their own card
        // messages immediately after the summary — ChatGPT-style end-of-
        // chat file cards, never a path dumped as prose.
        emitCollectedArtifactCards(sessionId)
        // v1.3.0 round-7: the ask bookkeeping is consumed with the summary —
        // the NEXT plan (or chat turn) starts from a clean slate.
        askedUserDuringPlan = false

        announce(summaryText)
        // v1.2.1 Hermes-style memory learning over the agent turn too: goals
        // often carry durable facts ("my cat Luna...", "for my shop...").
        scope.launch {
            memoryLearner.learnFromExchange(plan.goal, summaryText)
        }
    }

    /**
     * v1.4.0 Hermes answer engine: the post-tool SYNTHESIS call. When a
     * completed plan produced data (search results, quotes, fetches, files),
     * the final reply is WRITTEN BY THE MODEL from the step digest under the
     * answer contract — answer-first, numbers with units, no raw dumps, no
     * file-system paths. One retry with the harder nudge if the first draft
     * echoes a listing back. Returns (answer, modelBadge) or null on any
     * failure/timeout — the caller then falls to the deterministic ladder.
     *
     * Field evidence (2026-10-03): "what is the current price of xauusd"
     * executed WEB_SEARCH correctly, the snippets carried $4,199.40/oz, and
     * the user still got the raw link listing — the data arrived, the
     * answer never did.
     */
    private suspend fun synthesizedSummary(plan: Plan): Pair<String, String>? {
        if (!AnswerEngine.needsSynthesis(plan.steps)) return null
        val digest = AnswerEngine.stepDigest(plan.steps)
        if (digest.isBlank()) return null
        // v1.4.0: the turn is NOT silent while the model writes — the same
        // live-thinking surface the chat path uses tells the user the results
        // are being turned into their answer (perceived latency: tools done
        // -> "writing your answer" -> answer, never a dead pause).
        _liveThinking.value = "[answer] turning the results into your answer…"
        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val userPayload = "User's request: ${plan.goal.take(400)}\n\nTool results (raw):\n$digest"
            var answer: String? = null
            for (attempt in 1..2) {
                val response = withTimeout(SYNTHESIS_TIMEOUT_MS) {
                    provider.complete(
                        LLMRequest(
                            systemPrompt = AnswerEngine.ANSWER_CONTRACT +
                                if (attempt == 1) "" else "\n\n" + AnswerEngine.SYNTHESIS_RETRY_NUDGE,
                            messages = listOf(
                                ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    text = userPayload,
                                    sender = ChatMessage.Sender.USER
                                )
                            ),
                            temperature = 0.3f,
                            maxTokens = 900,
                            responseFormat = ResponseFormat.TEXT
                        )
                    )
                }
                val text = PlanResponseSanitizer.stripReasoningBlocks(response.content).trim()
                if (text.length >= 12 && !AnswerEngine.looksLikeRawDump(text)) {
                    answer = text
                    break
                }
                android.util.Log.w(
                    "AgentLoop",
                    "synthesis attempt $attempt rejected (len=${text.length} dump=${AnswerEngine.looksLikeRawDump(text)}) — ${
                        if (attempt == 1) "retrying with harder nudge" else "falling to extractive"
                    }"
                )
            }
            answer?.takeIf { it.isNotBlank() }?.let { it to provider.name }
                ?.also {
                    android.util.Log.i(
                        "AgentLoop",
                        "synthesized summary: steps=${plan.steps.size} len=${it.first.length} model=${it.second}"
                    )
                }
        } catch (tce: TimeoutCancellationException) {
            // The synthesis must never delay the final bubble unboundedly —
            // the extractive fallback answers from the same results.
            android.util.Log.w("AgentLoop", "synthesized summary timed out — extractive fallback")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "synthesized summary failed: ${e.localizedMessage}")
            null
        } finally {
            _liveThinking.value = null
        }
    }

    /**
     * v1.3.0 round-7: the deterministic success summary, extracted from
     * speakAndSaveSummary into [PlanResponseSanitizer.stepResultSummary] for
     * unit testing. Identical to the old assembly except ASK_USER steps now
     * contribute their answer — the old `result.length > 5` filter dropped
     * "Pune" (4 chars) and the summary collapsed to "All done!".
     */
    private fun cannedSuccessSummary(plan: Plan): String =
        PlanResponseSanitizer.stepResultSummary(plan.steps) ?: humanizeGoalDone(plan.goal)

    /** Steps that never change the world (see [honestAskOnlySummary]). */
    private val ASK_ONLY_ACTIONS = setOf("ASK_USER", "CHAT")

    /**
     * v1.6.0 (field P0-1): deterministic honest summary for plans that only
     * ASKED questions. The cab-plan field failure reached the user as "Your
     * cab is booked" because the ask-confirmation LLM was instructed to
     * "confirm the completed task". When the goal demands an outcome NO
     * registered action can produce (booking / ordering / paying), the app
     * says so itself - the model's own field confession ("I don't have a
     * platform to report - I jumped ahead earlier. No booking was actually
     * completed.") is the bar this summary must meet unprompted.
     */
    private fun honestAskOnlySummary(plan: Plan): String? {
        val note = GoalContract.unfulfillableGoalNote(plan.goal) ?: return null
        val answers = plan.steps
            .filter { it.action.trim().uppercase() == "ASK_USER" && it.status == StepStatus.COMPLETED }
            .mapNotNull { it.result?.trim()?.take(80) }
        return buildString {
            append("I've noted your answers")
            if (answers.isNotEmpty()) {
                append(" (you said \"" + answers.joinToString("\", \"") + "\")")
            }
            append(". To be honest: $note")
        }
    }

    /**
     * v1.4.0: the honest partial answer for a FAILED plan that still
     * gathered data. Runs the same answer-engine ladder (synthesis →
     * extractive) over the COMPLETED steps' results, prefixed with what
     * went wrong — never a silent data loss. Returns null when nothing
     * completed with a result (the generic humanizeFailure path stays).
     * Deliberately wider than [AnswerEngine.needsSynthesis]: on a failure
     * turn, even a single clean step result is worth delivering — the
     * speed gate's "already answer-shaped → skip" optimization belongs
     * to the SUCCESS path only.
     */
    private suspend fun partialFindingsSummary(plan: Plan): String? {
        val hasCompletedData = plan.steps.any {
            it.status == StepStatus.COMPLETED && !it.result.isNullOrBlank() &&
                it.action.trim().uppercase() != "CHAT"
        }
        if (!hasCompletedData) return null
        val failed = plan.steps.count { it.status == StepStatus.FAILED }
        val preface = "I couldn't fully complete this — " +
            "$failed step${if (failed == 1) "" else "s"} hit dead ends — " +
            "but here's what I found:\n\n"
        val body = synthesizedSummary(plan)?.first
            ?: AnswerEngine.extractiveAnswer(plan.goal, plan.steps)
            ?: return null
        return preface + body
    }

    /**
     * v1.3.0 round-7: the ask-answer round-trip contract (the opencode
     * question tool): when a plan asked the user something and the answers
     * landed in the step results, the FINAL reply is written by the model
     * with the Q&A in context — "You prefer Pune — noted!" — never by a
     * canned heuristic. One bounded non-streaming call; ANY failure (or the
     * dedicated 30s bound — a stalling free tier must never delay the
     * final bubble by minutes) returns null and the caller falls to
     * [cannedSuccessSummary], which quotes the answers deterministically.
     */
    private suspend fun askConfirmedSummary(plan: Plan): String? {
        if (!askedUserDuringPlan) return null
        return try {
            val qaLines = plan.steps
                .filter { it.action.trim().uppercase() == "ASK_USER" && it.status == StepStatus.COMPLETED }
                .mapIndexedNotNull { index, step ->
                    val answer = step.result?.trim()?.take(300) ?: return@mapIndexedNotNull null
                    val question = step.description.take(200).ifBlank { "your question" }
                    "Q${index + 1}: $question\nUser answered: $answer"
                }
            // NeedsInput re-asks do NOT record the user's answer as an
            // ASK_USER step (the answer is injected into the retried
            // action's params and the step result is that action's own
            // output) — those plans take the canned path; an empty Q&A list
            // from a NeedsInput-only ask means exactly that.
            if (qaLines.isEmpty()) return null
            val stepLines = plan.steps.joinToString("\n") { step ->
                val detail = (step.result ?: "").trim().take(160).ifBlank { "done" }
                "- ${step.description.take(200).ifBlank { step.action }} -> $detail"
            }
            val provider = llmProviderFactory.getActiveProvider()
            val response = withTimeout(ASK_CONFIRM_TIMEOUT_MS) {
                provider.complete(
                    LLMRequest(
                        systemPrompt = "You are TSF Droid, an Android agent finishing a task. " +
                            "The plan asked the user question(s) and the user answered. " +
                            "Write ONE short natural sentence (max 30 words) that reflects " +
                            "what was ACTUALLY done and mentions what the user answered. " +
                            "HONESTY FIRST: only claim a task was completed if one of the steps " +
                            "actually performed it. If the steps only asked questions and never " +
                            "performed the goal action (a booking, order, payment, sending), say " +
                            "so - e.g. 'Noted your answers - nothing has been booked yet, I " +
                            "can't complete bookings on my own.' Never invent an outcome. " +
                            "No preamble, no quotes around the sentence, no lists.",
                        messages = listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = "Goal: ${plan.goal}\nSteps:\n$stepLines\n\n" +
                                    "User answers:\n${qaLines.joinToString("\n")}\n\n" +
                                    "Write the single confirmation sentence now.",
                                sender = ChatMessage.Sender.USER
                            )
                        ),
                        temperature = 0.3f,
                        maxTokens = 120,
                        responseFormat = ResponseFormat.TEXT
                    )
                )
            }
            val text = PlanResponseSanitizer.stripReasoningBlocks(response.content).trim()
            // v1.6.0 round 3 (critic-21): the confirmation sentence itself
            // passes the answer-shape gate - an LLM confirming in raw
            // tool syntax or a JSON wrapper never ships.
            AnswerHygiene.sanitizeFinalAnswer(text)
                .takeIf { !it.isNullOrEmpty() && it.length in 4..400 }
                ?.also {
                    android.util.Log.i(
                        "AgentLoop",
                        "ask-confirmed summary: qa=${qaLines.size} len=${it.length}"
                    )
                }
        } catch (tce: TimeoutCancellationException) {
            // TimeoutCancellationException IS a CancellationException — it
            // must be caught BEFORE the rethrow below or a slow confirmation
            // would kill the whole summary save (the round-11 lesson).
            android.util.Log.w("AgentLoop", "ask-confirmed summary timed out — canned fallback")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AgentLoop", "ask-confirmed summary failed: ${e.localizedMessage}")
            null
        }
    }

    private fun formatStreamedReply(text: String): String {
        if (!text.startsWith("Error streaming")) return text
        val technical = text.substringAfter(": ", text)
        return NetworkErrorFormatter.toUserMessage(technical)
    }

    /**
     * Parses the model's answer into an executable [Plan].
     *
     * v1.0.4 hardening for reasoning models on the free tier: answers may
     * arrive wrapped in `<think>` blocks, fenced, or preceded by narration,
     * and a genuinely ambiguous goal can legitimately come back as a
     * clarifying question. Parsing therefore walks progressively looser
     * candidates, and a prose answer is routed into CHAT/ASK_USER instead of
     * failing the turn with "Could not parse a valid plan".
     */
    private fun parsePlanFromLlmResponse(raw: String, userGoal: String): Plan {
        val sanitized = PlanResponseSanitizer.stripReasoningBlocks(raw)
        val stripped = stripMarkdownFences(sanitized)

        // Richest first: the sanitized text itself, then the first balanced
        // JSON object found inside mixed prose.
        val candidates = linkedSetOf(stripped)
        extractFirstJsonObject(stripped)?.let { candidates.add(it) }

        for (candidate in candidates.filter { it.isNotBlank() }) {
            // Prefer wrapper action before unwrapping plan — handles {"action":"...","plan":null}.
            try {
                val root = json.parseToJsonElement(candidate)
                if (root is JsonObject) {
                    val action = root["action"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    val planElement = root["plan"]
                    val hasPlanObject = planElement is JsonObject

                    if (action != null && !hasPlanObject) {
                        val params = jsonObjectToStringMap(root["params"]?.jsonObject)
                        // v1.3.0 round 21 (the 2026-10-03 field evidence — both
                        // Nvidia turns and the PDF turn): the WRAPPER form
                        // {"action":"CHAT","params":{"response":"I can't pull
                        // a live quote right now (no tool access in this
                        // session)…"}} is the ONE plan shape the loop-17
                        // deferral gate never covered — the planner wrote the
                        // response at PLAN TIME, without its tools, and the
                        // CHAT step delivered that canned slop verbatim. Apply
                        // the same gate the full-plan branches get, plus the
                        // refusal-shape check on the response text itself.
                        val refusalShaped = action.trim().uppercase() == "CHAT" &&
                            PlanResponseSanitizer.replyRefusesGoal(params["response"])
                        if (refusalShaped ||
                            PlanResponseSanitizer.planDefersGoal(listOf(action), userGoal)
                        ) {
                            throw IllegalArgumentException(
                                "Wrapper-form plan deferred the goal (refusal-shaped or no executable action for it)"
                            )
                        }
                        return buildSingleStepPlan(userGoal, action, params)
                    }

                    if (hasPlanObject) {
                        val plan = normalizePlan(json.decodeFromString<Plan>(planElement.toString()))
                        // v1.0.6 loop-17: a well-formed plan can still REFUSE
                        // the goal (the field evidence: a one-step CHAT plan
                        // reading "I don't have live market data access").
                        // Throw so the corrective re-ask / deterministic
                        // synthesis turns it into real executable steps.
                        if (PlanResponseSanitizer.planDefersGoal(
                                plan.steps.map { it.action }, userGoal
                            )
                        ) {
                            throw IllegalArgumentException(
                                "Plan deferred the goal (no executable action for it)"
                            )
                        }
                        return plan
                    }

                    // A bare plan object at the root.
                    val barePlan = normalizePlan(json.decodeFromString<Plan>(candidate))
                    if (PlanResponseSanitizer.planDefersGoal(
                            barePlan.steps.map { it.action }, userGoal
                        )
                    ) {
                        throw IllegalArgumentException(
                            "Plan deferred the goal (no executable action for it)"
                        )
                    }
                    return barePlan
                }
            } catch (_: Exception) {
                // Fall through to the cleaner / next candidate
            }

            val cleaned = cleanPlanJson(candidate)
            try {
                val parsed = normalizePlan(json.decodeFromString<Plan>(cleaned))
                if (PlanResponseSanitizer.planDefersGoal(
                        parsed.steps.map { it.action }, userGoal
                    )
                ) {
                    throw IllegalArgumentException(
                        "Plan deferred the goal (no executable action for it)"
                    )
                }
                return parsed
            } catch (_: Exception) {
                // Continue with remaining candidates
            }
        }

        // Prose answer that never became JSON: the model either asked a
        // clarifying question or answered conversationally. Both are valid
        // agent outcomes — surface them through the action protocol instead
        // of an error. JSON-shaped text that failed every parse above still
        // throws: converting corrupt JSON into a fake reply would hide bugs.
        // Classified on [stripped] (fences already removed) so corrupt
        // fenced JSON still starts with "{" and is never mistaken for prose.
        PlanResponseSanitizer.classifyProseReply(stripped)?.let { (action, params) ->
            // EXCEPT: a prose reply that defers the goal is NOT a valid
            // outcome — nothing would ever be done. v1.0.5/v1.0.6: the
            // short-commitment deferral ("I am creating the HTML file for
            // you." against a "create an HTML website" goal). v1.3.0
            // round-7, from run-102 evidence: (a) ANY prose against a
            // CONCRETE artifact goal ("website"/"html") — the model returned
            // a 10k-char essay ABOUT the site and no file was ever written;
            // (b) ANY prose against an explicit lookup command ("Search the
            // web for the current Bitcoin price…") — the model answered from
            // its memory of an earlier turn with zero fresh search. Both now
            // live inside proseDeclinesAction. Throw so the corrective
            // re-ask / deterministic synthesis produces real executable
            // steps.
            if (action == "CHAT" &&
                PlanResponseSanitizer.proseDeclinesAction(params["response"], userGoal)
            ) {
                throw IllegalArgumentException(
                    "Prose reply deferred the goal instead of planning it"
                )
            }
            return buildSingleStepPlan(userGoal, action, params)
        }

        throw IllegalArgumentException("Could not parse a valid plan from LLM response")
    }

    /**
     * Text-shaping helpers (reasoning-strip, balanced-JSON extraction, prose
     * classification) live in [PlanResponseSanitizer] so they can be unit
     * tested without constructing the full agent loop.
     */
    private fun extractFirstJsonObject(text: String): String? =
        PlanResponseSanitizer.extractFirstJsonObject(text)

    private fun stripMarkdownFences(raw: String): String {
        var content = raw.trim()
        if (content.startsWith("```json")) {
            content = content.removePrefix("```json")
        } else if (content.startsWith("```")) {
            content = content.removePrefix("```")
        }
        if (content.endsWith("```")) {
            content = content.removeSuffix("```")
        }
        return content.trim()
    }

    private fun normalizePlan(plan: Plan): Plan {
        return plan.copy(
            planId = plan.planId.ifBlank { UUID.randomUUID().toString() },
            goal = plan.goal.ifBlank { "User request" },
            estimatedSteps = if (plan.estimatedSteps > 0) plan.estimatedSteps else plan.steps.size.coerceAtLeast(1),
            steps = plan.steps.map { step ->
                step.copy(
                    stepId = step.stepId.ifBlank { "s${step.order}" },
                    fallback = step.fallback
                )
            }
        )
    }

    private fun buildSingleStepPlan(goal: String, action: String, params: Map<String, String>): Plan {
        return Plan(
            planId = UUID.randomUUID().toString(),
            goal = goal,
            estimatedDuration = "instant",
            estimatedSteps = 1,
            steps = listOf(
                PlanStep(
                    stepId = "s1",
                    order = 1,
                    description = "Execute $action",
                    action = action,
                    params = params,
                    fallback = ""
                )
            )
        )
    }

    private fun jsonObjectToStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: value.toString().trim('"')
        }
    }

    private fun cleanPlanJson(raw: String): String {
        var content = stripMarkdownFences(raw)
        try {
            val jsonElement = json.parseToJsonElement(content)
            if (jsonElement is JsonObject) {
                val planElement = jsonElement["plan"]
                // Only unwrap when plan is an object — preserve wrappers with "plan": null.
                if (planElement is JsonObject) {
                    return planElement.toString()
                }
            }
        } catch (e: Exception) {
            // Silently ignore parsing errors here and let downstream deserialization report them if needed
        }
        return content
    }

    /**
     * Convert raw technical error messages into friendly, user-facing text.
     * Prevents raw Java stack traces, permission denial strings, and
     * intent resolution errors from being spoken to the user.
     */
    private fun formatErrorForUser(action: String, rawError: String): String {
        // Log the technical error for debugging
        android.util.Log.e("AgentLoop", "Action $action error: $rawError")

        // Return only a short, friendly message
        return when {
            rawError.contains("Permission", ignoreCase = true) ||
            rawError.contains("SecurityException", ignoreCase = true) ->
                "I need a permission for that. Check your app settings and try again."

            rawError.contains("ActivityNotFound", ignoreCase = true) ->
                "Looks like the app I need isn't installed."

            rawError.contains("IOException", ignoreCase = true) ->
                "I'm having trouble connecting. Check your internet?"

            else ->
                "Something didn't work out. Mind trying again?"
        }
    }

    /**
     * Generate a natural pre-execution speech line based on the action.
     */
    private fun humanizePreSpeech(action: String): String {
        return when (action) {
            "TOGGLE_FLASHLIGHT" -> "Got it, toggling your flashlight."
            "SET_ALARM" -> "Sure, setting that alarm for you."
            "SET_TIMER" -> "Alright, starting a timer."
            "TAKE_SCREENSHOT" -> "Taking a screenshot now."
            "LOCK_SCREEN" -> "Locking your screen."
            "TOGGLE_WIFI" -> "Alright, switching your WiFi."
            "TOGGLE_BLUETOOTH" -> "On it, toggling Bluetooth."
            "TOGGLE_DND" -> "Got it, changing Do Not Disturb."
            "TOGGLE_HOTSPOT" -> "Sure, toggling your hotspot."
            "TOGGLE_MOBILE_DATA" -> "Alright, switching mobile data."
            "SET_VOLUME" -> "Got it, adjusting the volume."
            "SET_BRIGHTNESS" -> "Sure, adjusting brightness."
            "OPEN_APP" -> "Opening that for you."
            "ANALYZE_SCREENSHOT" -> "Let me take a look at your screen."
            "READ_AND_REMEMBER_SCREEN" -> "Reading your screen and saving the important details."
            "READ_NOTES" -> "Let me look up your notes."
            "RECALL_MEMORY" -> "Searching your saved memories."
            "ADD_NOTE" -> "Saving that note for you."
            "SET_RINGER_MODE" -> "Changing your ringer mode."
            "PLAY_MUSIC" -> "Let me play that for you."
            "MAKE_CALL" -> "Calling now."
            else -> {
                val readable = action.lowercase().replace("_", " ")
                "On it! Let me $readable."
            }
        }
    }

    /**
     * Generate a natural success message when no step result is available.
     */
    private fun humanizeGoalDone(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("alarm") -> "All set! Your alarm is ready."
            lower.contains("flash") || lower.contains("torch") -> "Done! Flashlight's been toggled."
            lower.contains("wifi") -> "WiFi's been updated."
            lower.contains("bluetooth") -> "Bluetooth's been switched."
            lower.contains("volume") -> "Volume's adjusted."
            lower.contains("brightness") -> "Brightness updated."
            lower.contains("screenshot") -> "Screenshot taken!"
            lower.contains("timer") -> "Timer's set and running."
            lower.contains("open") -> "Done, it should be open now."
            lower.contains("call") -> "Calling now."
            lower.contains("message") || lower.contains("whatsapp") -> "Message sent!"
            else -> "All done!"
        }
    }

    /**
     * Generate a natural failure message. Technical details go to logs only.
     */
    private fun humanizeFailure(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("alarm") -> "Sorry, I couldn't set that alarm. Maybe check your Clock app?"
            lower.contains("flash") || lower.contains("torch") -> "Hmm, the flashlight didn't work. Try again?"
            lower.contains("call") -> "I wasn't able to make that call. Want to try again?"
            lower.contains("message") || lower.contains("whatsapp") -> "The message didn't go through. Want me to retry?"
            lower.contains("wifi") || lower.contains("bluetooth") -> "Couldn't change that setting. You might need to do it manually."
            else -> "Sorry, that didn't work out. Want me to try again?"
        }
    }
}
