package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val name: String? = null,
    val age: Int? = null,
    val location: String? = null,
    val timezone: String? = null,
    val language: String? = null,
    val contacts: Map<String, String> = emptyMap(), // Nickname -> Phone
    val preferences: Map<String, String> = emptyMap(), // key -> value
    val routines: Map<String, String> = emptyMap(), // trigger -> action
    val behaviorPatterns: List<String> = emptyList()
)
