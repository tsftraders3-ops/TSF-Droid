package com.tsfdroid.ai.data.models

import kotlinx.serialization.Serializable

/**
 * v1.2.0: the two operating modes of the assistant.
 *
 * [CHAT] is the safe conversational mode: the model can read and understand
 * (workspace files, web pages, search results, uploaded attachments) but can
 * NEVER modify anything — no file writes, no device control, no messaging.
 * Every non-read-only tool call is refused at the execution gate, not just
 * hidden from the prompt.
 *
 * [AGENT] is the full OpenCode-style harness: everything CHAT has, plus real
 * writes (files, PDFs), device actions, and the planning pipeline. This is
 * the default so a fresh install keeps the full assistant capability.
 */
@Serializable
enum class ChatMode {
    CHAT,
    AGENT;

    companion object {
        /** Null-tolerant resolution: never-set configs behave as AGENT. */
        fun fromNullable(mode: ChatMode?): ChatMode = mode ?: AGENT
    }
}
