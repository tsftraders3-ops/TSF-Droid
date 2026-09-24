package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class PlanStep(
    val stepId: String,
    val order: Int,
    val description: String,
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val canParallelize: Boolean = false,
    val fallback: String = "",
    /**
     * Intent-segmentation policy flag (TSF Droid Phase 2.4). Set by the planner
     * for steps that transmit SMS, place calls, modify system state, or trigger
     * UPI payment intents. A critical step ALWAYS passes through the explicit
     * user confirmation gate before execution, regardless of auto mode.
     */
    val critical: Boolean = false,
    /** Segmentation target: package id, element id, or free-form target string. */
    val target: String? = null,
    var status: StepStatus = StepStatus.PENDING,
    var result: String? = null,
    var error: String? = null
)

enum class StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}
