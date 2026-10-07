package com.tsfdroid.ai.core.agent

import android.content.Context
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.crash.CrashLogRedactor
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The common ordered action-dispatch seam used by Plans and saved Macros.
 *
 * Macro execution is deliberately linear: a failed step stops the sequence.
 * A step may run one configured fallback action, but a fallback never gets a
 * fallback of its own.
 */
class ActionSequenceExecutor(
    private val executeAction: suspend (String, Map<String, String>, Context) -> ActionResult,
    private val hasAction: (String) -> Boolean
) {

    data class StepExecution(
        val step: PlanStep,
        val resolvedParams: Map<String, String>,
        val primaryResult: ActionResult,
        val fallbackResult: ActionResult? = null
    ) {
        val finalResult: ActionResult
            get() = fallbackResult ?: primaryResult

        val usedFallback: Boolean
            get() = fallbackResult != null
    }

    /** Dispatches one primary action through the normal ActionDispatcher path. */
    suspend fun dispatch(
        action: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult = try {
        executeAction(action, params, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult.Failure(
            errorMsg = e.localizedMessage ?: "Unknown execution error"
        )
    }

    /**
     * Resolves both `$stepId` and `$$stepId` references from completed steps.
     * The double-dollar form is retained for compatibility with the existing
     * AgentLoop executor and is replaced before the single-dollar form.
     */
    fun resolveParameters(
        params: Map<String, String>,
        completedResults: Map<String, String>
    ): Map<String, String> = params.mapValues { (_, value) ->
        completedResults.entries
            .sortedByDescending { it.key.length }
            .fold(value) { resolved, (stepId, result) ->
                val doubleReference = "$" + "$" + stepId
                val singleReference = "$" + stepId
                resolved
                    .replace(doubleReference, result)
                    .replace(singleReference, result)
            }
    }

    /** Resolves references using only completed PlanStep results. */
    fun resolveParameters(
        params: Map<String, String>,
        priorSteps: List<PlanStep>
    ): Map<String, String> = resolveParameters(
        params = params,
        completedResults = priorSteps.asSequence()
            .filter { it.status == StepStatus.COMPLETED && it.result != null }
            .associate { it.stepId to it.result!! }
    )

    /** Executes a step and, when appropriate, its single fallback action. */
    suspend fun executeStep(
        step: PlanStep,
        completedResults: Map<String, String>,
        context: Context
    ): StepExecution {
        val resolvedParams = resolveParameters(step.params, completedResults)
        val primaryResult = dispatch(step.action, resolvedParams, context)
        val fallbackResult = if (shouldAttemptFallback(primaryResult, step)) {
            dispatch(step.fallback, resolvedParams, context)
        } else {
            null
        }
        return StepExecution(
            step = step,
            resolvedParams = resolvedParams,
            primaryResult = primaryResult,
            fallbackResult = fallbackResult
        )
    }

    /** Whether the existing Plan fallback behavior applies to this result. */
    fun shouldAttemptFallback(result: ActionResult, step: PlanStep): Boolean =
        !result.success &&
            step.fallback.isNotBlank() &&
            hasAction(step.fallback) &&
            result !is ActionResult.UnknownAction &&
            result !is ActionResult.PendingUserAction &&
            result !is ActionResult.UserActionRequired

    /**
     * Runs a complete saved macro. Later steps are not dispatched after a
     * failure, so the returned result cannot claim work that did not happen.
     *
     * C-01 (audit fc9ea97): this is the shared batch seam for every execution
     * path WITHOUT a human at fire time — MacroSchedulerWorker (cron),
     * RUN_MACRO, and HabitRoutineEngine.executeRoutine. None of those callers
     * can show the interactive approval modal, so policy-critical steps are
     * refused HERE, at the last point before dispatch. The AgentLoop plan path
     * (which does gate on AutoApprovalPolicy before each dispatch) remains the
     * only route through which a critical action may ever run, after the user
     * approved exactly that plan.
     */
    suspend fun execute(
        steps: List<PlanStep>,
        context: Context
    ): ActionResult {
        if (steps.isEmpty()) {
            return ActionResult.Failure("Macro contains no executable steps.")
        }

        val orderedSteps = steps.sortedWith(compareBy<PlanStep> { it.order })
        val completedResults = linkedMapOf<String, String>()

        orderedSteps.forEachIndexed { index, step ->
            currentCoroutineContext().ensureActive()

            // Background consent boundary: a critical step (planner-flagged,
            // policy-critical primary, or policy-critical fallback) is refused
            // before ANY dispatch for this step. Macro semantics stay linear —
            // the refusal stops the sequence exactly like a failed step, so a
            // later step can never consume the output of a critical step that
            // was allowed to half-run.
            if (AutoApprovalPolicy.isCritical(step)) {
                val refused = when {
                    step.action.isNotBlank() && AutoApprovalPolicy.isPolicyCriticalAction(step.action) ->
                        step.action
                    step.fallback.isNotBlank() && AutoApprovalPolicy.isPolicyCriticalAction(step.fallback) ->
                        "${step.fallback} (fallback of ${step.action.ifBlank { "unknown" }})"
                    else -> step.action.ifBlank { "unknown action" }
                }
                return ActionResult.Failure(
                    "Macro stopped at step ${index + 1} ($refused): policy-critical " +
                        "actions require interactive confirmation and cannot run " +
                        "from background or batch execution."
                )
            }

            val execution = executeStep(step, completedResults, context)
            val result = execution.finalResult
            if (!result.success) {
                val actionName = step.action.ifBlank { "unknown action" }
                val detail = result.error
                    ?.let(CrashLogRedactor::redact)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Action execution failed."
                return ActionResult.Failure(
                    "Macro stopped at step ${index + 1} ($actionName): $detail"
                )
            }

            completedResults[step.stepId] = result.data ?: "Completed successfully."
        }

        return ActionResult.Success(
            dataMap = mapOf(
                "message" to "Macro completed successfully (${orderedSteps.size} steps)."
            )
        )
    }
}
