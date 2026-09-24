package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class Macro(
    val id: String,
    val name: String,
    val trigger: String, // voice trigger or cron expression
    val steps: List<PlanStep>,
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true
)
