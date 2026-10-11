package com.tsfdroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * God-Tier Phase 2 (5.4): one persisted proactive suggestion — the ONLY
 * output of the opt-in background worker.
 *
 * Suggestions are DATA, never instructions: [suggestedAction] is the action
 * the user could take next (e.g. CREATE_MACRO), not an action the app takes
 * on its own. The worker's safety gate refuses to persist any suggestion
 * whose action is policy-critical (see
 * [com.tsfdroid.ai.core.agent.AutoApprovalPolicy]) — a suggestion that
 * names SEND_SMS or DELETE_FILE is discarded before it reaches this table.
 */
@Entity(tableName = "proactive_suggestions")
data class ProactiveSuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val suggestedAction: String?,
    val createdAt: Long,
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_READ = "READ"
        const val STATUS_DISMISSED = "DISMISSED"
    }
}
