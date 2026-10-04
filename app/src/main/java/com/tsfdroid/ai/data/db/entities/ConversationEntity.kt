package com.tsfdroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["sessionId"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val text: String,
    val sender: String, // "USER" or "AGENT"
    val timestamp: Long,
    val modelBadge: String? = null,
    val contactPickerData: String? = null,
    // v1.0.5: reasoning-model thinking trace, rendered as a collapsible
    // THINKING section on agent bubbles. Null for non-reasoning messages.
    val thinkingText: String? = null,
    // v1.0.6: file attachment card JSON {"name","path","mime","size"} for
    // artifacts the agent created (WRITE_FILE / CREATE_PDF). Null otherwise.
    val attachmentJson: String? = null,
    // v1.2.0: user uploads (images / documents / processed PDF & video frames)
    // as JSON [MessageAttachments]. Null for messages without uploads.
    val attachmentsJson: String? = null,
    // v1.2.1: agent-activity trace (ActivityStep records) for the visible
    // steps this reply took. Null for replies without recorded steps.
    val stepsJson: String? = null,
    // v1.3.0: ask_user question options (AskOptions JSON) — persisted so the
    // tappable chips survive process death and history reloads.
    val askOptionsJson: String? = null,
    // v1.4.0 chat export: the measured reasoning phase of this reply in ms
    // (null when unmeasured). Numeric counterpart of the "Thought for Xs" label.
    val thinkingDurationMs: Long? = null,
    // v1.4.0 chat export: the concrete model id that answered this turn.
    val modelId: String? = null,
    // v1.4.0 chat export: provider-reported token usage for this turn (nullable).
    val tokensUsed: Int? = null,
    // v1.4.0 chat export: total harness model-call latency for this turn, ms (nullable).
    val turnLatencyMs: Long? = null,
    // v1.4.0 chat export: JSON array of ToolCallRecord — the full tool-call
    // log behind this reply (params, capped results, per-call durations).
    val toolCallsJson: String? = null,
    // Which chat history this message belongs to. See ChatSessionEntity.
    val sessionId: String
)
