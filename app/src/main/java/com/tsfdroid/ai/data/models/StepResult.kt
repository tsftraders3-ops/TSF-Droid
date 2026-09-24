package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class StepResult(
    val stepId: String,
    val success: Boolean,
    val data: String? = null,
    val error: String? = null
)
