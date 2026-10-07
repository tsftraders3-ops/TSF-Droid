package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M-01 (audit fc9ea97): the outcome of delegating one bounded task to a
 * specialist sub-agent. Failures are VALUES, never exceptions — the CEO
 * (AgentLoop) receives them and degrades gracefully.
 */
sealed class SubAgentResult {
    data class Success(
        val output: String,
        val tokensUsed: Int,
        val latencyMs: Long
    ) : SubAgentResult()

    /** The circuit breaker killed the sub-agent: it exceeded its deadline. */
    data class TimedOut(val role: String, val budgetMs: Long) : SubAgentResult()

    /** The response exceeded the token budget and was discarded. */
    data class OverBudget(val role: String, val tokensUsed: Int, val tokenBudget: Int) : SubAgentResult()

    /** The specialist failed (provider error, empty output, ...). */
    data class Failed(val role: String, val error: String) : SubAgentResult()
}

/** The two specialist roles the CEO delegates to. */
enum class SubAgentRole { RESEARCH, EXECUTOR }

/**
 * The circuit-breaker envelope for every delegation: a hard deadline and a
 * hard token budget. Defaults are the audit's numbers (60 seconds, 4k tokens
 * — the research specialist's context cap).
 */
data class SubAgentBudget(
    val timeoutMs: Long = 60_000L,
    val maxTokens: Int = 4_000
)

/**
 * M-01 (audit fc9ea97): the CEO's delegation surface.
 *
 * The router runs specialist sub-agents under a circuit breaker: a specialist
 * that exceeds its 60-second deadline is killed (its coroutine is cancelled)
 * and a response over the 4k token budget is discarded. Every failure mode is
 * reported as a [SubAgentResult] value — the router never throws to the CEO.
 * The CEO-safe variants ([researchSafely], [draftSafely]) map any non-Success
 * to null so AgentLoop proceeds without the sub-agent's contribution.
 *
 * The EXECUTOR specialist only DRAFTS step fragments as JSON — it never
 * dispatches actions itself. Everything it drafts flows back through the
 * CEO's normal plan pipeline and the AutoApprovalPolicy confirmation gate;
 * sub-agents cannot route around the consent boundary (C-01).
 */
@Singleton
class SubAgentRouter @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {
    // Test seam (M-01): default is the real factory; tests substitute a
    // controllable provider. Never set by production code.
    internal var providerAccess: suspend () -> LLMProvider =
        { llmProviderFactory.getActiveProvider() }

    /** The circuit-breaker envelope applied to every delegation. */
    internal var budget: SubAgentBudget = SubAgentBudget()

    /** Delegates a research task to the research specialist. */
    suspend fun research(goal: String): SubAgentResult =
        delegate(
            role = SubAgentRole.RESEARCH.name,
            systemPrompt = RESEARCH_SYSTEM_PROMPT,
            task = "Research the context needed for this user goal and condense the key facts:\n$goal",
            responseFormat = ResponseFormat.TEXT
        )

    /** CEO-safe research: any non-Success degrades to null. */
    suspend fun researchSafely(goal: String): String? = when (val r = research(goal)) {
        is SubAgentResult.Success -> r.output.takeIf { it.isNotBlank() }
        else -> null
    }

    /** Delegates execution-step drafting to the executor specialist. */
    suspend fun draftExecution(goal: String, research: String): SubAgentResult =
        delegate(
            role = SubAgentRole.EXECUTOR.name,
            systemPrompt = EXECUTOR_SYSTEM_PROMPT,
            task = buildString {
                append("Draft the execution steps for this user goal as a JSON steps fragment.\n")
                append("Goal: $goal\n")
                if (research.isNotBlank()) {
                    append("Research specialist findings:\n$research\n")
                }
            },
            responseFormat = ResponseFormat.JSON
        )

    /** CEO-safe execution drafting: any non-Success degrades to null. */
    suspend fun draftSafely(goal: String, research: String): String? =
        when (val r = draftExecution(goal, research)) {
            is SubAgentResult.Success -> r.output.takeIf { it.isNotBlank() }
            else -> null
        }

    /**
     * The circuit breaker itself: runs one specialist call under the hard
     * deadline, discards over-budget responses, and reports every failure as
     * a value. NEVER throws to the CEO.
     */
    private suspend fun delegate(
        role: String,
        systemPrompt: String,
        task: String,
        responseFormat: ResponseFormat
    ): SubAgentResult {
        val started = System.currentTimeMillis()
        return try {
            val response: LLMResponse? = withTimeoutOrNull(budget.timeoutMs) {
                providerAccess().complete(
                    LLMRequest(
                        systemPrompt = systemPrompt,
                        messages = listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = task,
                                sender = ChatMessage.Sender.USER
                            )
                        ),
                        temperature = 0.2f,
                        maxTokens = budget.maxTokens,
                        responseFormat = responseFormat
                    )
                )
            }
            when {
                response == null -> SubAgentResult.TimedOut(role, budget.timeoutMs)
                response.tokensUsed > budget.maxTokens -> SubAgentResult.OverBudget(
                    role, response.tokensUsed, budget.maxTokens
                )
                response.content.isBlank() -> SubAgentResult.Failed(role, "specialist returned empty output")
                else -> SubAgentResult.Success(
                    output = response.content.trim(),
                    tokensUsed = response.tokensUsed,
                    latencyMs = System.currentTimeMillis() - started
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SubAgentResult.Failed(role, e.message ?: "sub-agent failure")
        }
    }

    private val RESEARCH_SYSTEM_PROMPT = """You are the research specialist of an autonomous Android agent team.
Your job is to gather and condense the factual context the team needs before planning.
Work only from the conversation and your own knowledge; be concise, factual, and structured.
Stay within your budget: a short brief beats an exhaustive essay.
Return plain text: the 5-12 most decision-relevant facts, findings, or caveats."""

    private val EXECUTOR_SYSTEM_PROMPT = """You are the executor specialist of an autonomous Android agent team.
Given a user goal and the research findings, draft the ordered execution steps as a JSON fragment:
{"steps":[{"action":"<ACTION_NAME>","description":"<what this step does>","params":{}}]}
Constraints:
- Use ONLY non-critical actions in your draft. Critical actions (SEND_SMS, MAKE_CALL, PAY_UPI,
  CLICK_COORDINATES, destructive file or system operations) are FORBIDDEN in sub-agent drafts —
  they require the CEO's interactive user confirmation and can never come from a specialist.
- You only DRAFT steps; you never execute anything. The CEO owns the plan and the approval gate.
- Keep it small: 1-7 steps, each independently checkable."""
}
