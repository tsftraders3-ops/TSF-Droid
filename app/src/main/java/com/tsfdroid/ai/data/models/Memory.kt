package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class Memory(
    val key: String,
    val value: String,
    val type: MemoryType,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MemoryType {
    WORKING, EPISODIC, SEMANTIC, PROCEDURAL
}
