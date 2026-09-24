package com.tsfdroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trigger: String,
    val stepsJson: String, // Serialized List<PlanStep>
    val isSystem: Boolean,
    val isEnabled: Boolean
)
