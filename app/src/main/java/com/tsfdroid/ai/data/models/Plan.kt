package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val planId: String,
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    /** Intent-segmentation envelope: stable id for this segmented task. */
    val taskId: String? = null,
    /** True when the planner segmented a compound (multi-action) request. */
    val isCompound: Boolean = false
)

enum class PlanStatus {
    PROPOSED, PENDING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLED
}
