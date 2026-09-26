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
    // Which chat history this message belongs to. See ChatSessionEntity.
    val sessionId: String
)
