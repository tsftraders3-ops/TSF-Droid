package com.tsfdroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val planId: String,
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val stepsJson: String, // Serialized List<PlanStep>
    val status: String, // PENDING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLED
    val createdAt: Long
)
