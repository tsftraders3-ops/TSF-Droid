package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val modelBadge: String? = null,
    val imageBase64: String? = null,
    val contactPickerData: String? = null,
    /**
     * Reasoning-model "thinking" trace (v1.0.5): what the model was thinking
     * while it produced [text], rendered as a collapsible THINKING section on
     * agent bubbles. Null for messages without a reasoning surface; safe to
     * ignore everywhere except the chat renderer.
     */
    val thinkingText: String? = null
) {
    enum class Sender {
        USER, AGENT
    }
}
