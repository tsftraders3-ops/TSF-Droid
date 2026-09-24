package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.data.models.AutoMode
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStep

/**
 * Pure decision logic for Auto mode (upstream issue 18 spec, YOLO semantics
 * revised per #52 discussion, superseded by the TSF Droid Phase 2.4 policy
 * confirmation gate). Mixed plans are all-or-nothing in AUTO: a plan auto-runs
 * only when EVERY step's action is granted and none is neverAutoApprove or
 * policy-critical. YOLO auto-runs everything EXCEPT policy-critical steps —
 * the user opted out of routine approval gates, but the confirmation gate for
 * critical actions is absolute (see [isCritical]), which also resolves the
 * old doc/code contradiction about YOLO and destructive actions. No mid-plan
 * pause states — a blocked plan falls back to the normal PlanProposed gate whole.
 */
object AutoApprovalPolicy {

    /**
     * Policy Confirmation Gate (Phase 2.4): actions that segment into
     * critical steps and always require explicit user confirmation —
     * SMS transmission, dialer calls, system modifications, and UPI
     * payment intents — independent of what the planner flagged.
     */
    val POLICY_CRITICAL_ACTIONS: Set<String> = setOf(
        // Communication that leaves the device on the user's behalf
        "SEND_SMS", "MAKE_CALL", "MAKE_VIDEO_CALL",
        // Money movement
        "PAY_UPI",
        // System state modifications
        "RESTART_DEVICE", "INSTALL_APP", "CLEAR_BROWSER_DATA", "SET_WALLPAPER",
        "TOGGLE_WIFI", "TOGGLE_BLUETOOTH", "TOGGLE_MOBILE_DATA", "TOGGLE_HOTSPOT",
        "TOGGLE_DND", "SET_BRIGHTNESS", "SET_VOLUME", "SET_RINGER_MODE", "LOCK_SCREEN",
        // Destructive / persistent file operations
        "DELETE_FILE", "WRITE_FILE", "MOVE_FILE", "CREATE_DIRECTORY", "COPY_FILE"
    )

    /**
     * A step is critical when the planner flagged it, OR its primary/fallback
     * action is policy-critical. The planner flag is belt; the static set is
     * braces — a hallucinating or lazy planner cannot waive the gate.
     */
    fun isCritical(step: PlanStep): Boolean =
        step.critical ||
            step.action in POLICY_CRITICAL_ACTIONS ||
            (step.fallback.isNotBlank() && step.fallback in POLICY_CRITICAL_ACTIONS)

    fun shouldAutoApprove(mode: AutoMode, granted: Set<String>, plan: Plan): Boolean = when (mode) {
        AutoMode.OFF -> false
        AutoMode.YOLO -> plan.steps.none { isCritical(it) }
        AutoMode.AUTO -> plan.steps
            .flatMap {
                step -> listOfNotNull(step.action.takeIf { it.isNotBlank() }, step.fallback.takeIf { it.isNotBlank() })
            }
            .none { ActionSchema.isNeverAutoApprove(it) || it !in granted } &&
            plan.steps.none { isCritical(it) }
    }

    /**
     * Distinct actions (in step order) that keep this plan from auto-running.
     * Includes each step's non-blank [PlanStep.fallback] — AgentLoop executes
     * fallbacks on primary failure, so they must pass the same allowlist gate.
     */
    fun blockedActions(granted: Set<String>, steps: List<PlanStep>): List<String> =
        steps.flatMap { step ->
            listOfNotNull(step.action.takeIf { it.isNotBlank() }, step.fallback.takeIf { it.isNotBlank() })
        }
            .distinct()
            .filter { it !in granted || ActionSchema.isNeverAutoApprove(it) }

    /** Only known, non-neverAutoApprove actions may ever be granted. */
    fun isGrantable(actionName: String): Boolean =
        ActionSchema.ALL_ACTIONS.any { it.name == actionName } && !ActionSchema.isNeverAutoApprove(actionName)
}
