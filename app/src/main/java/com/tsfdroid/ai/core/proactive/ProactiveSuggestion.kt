package com.tsfdroid.ai.core.proactive

import com.tsfdroid.ai.core.agent.AutoApprovalPolicy
import com.tsfdroid.ai.data.db.entities.ProactiveSuggestionEntity

/**
 * God-Tier Phase 2, ticket 5.4 — a proactive suggestion.
 *
 * A suggestion is a PIECE OF ADVICE the agent drafts from local patterns,
 * not an action it performs. [suggestedAction] names what the USER could do
 * next (e.g. CREATE_MACRO); it is presentational data for whatever surface
 * renders suggestions, and it can never be executed by the worker.
 *
 * [isSideEffectFree] is the worker's safety gate: a suggestion whose
 * suggested action is policy-critical ([AutoApprovalPolicy]) is discarded
 * BEFORE persistence. The worker has no dispatch path at all — this gate
 * exists so that even the DATA cannot smuggle a critical action into
 * storage, and so any future surface that reads suggestions back can apply
 * the same test.
 */
data class ProactiveSuggestion(
    val title: String,
    val body: String,
    val suggestedAction: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** True when the suggestion carries no policy-critical action. */
    fun isSideEffectFree(): Boolean =
        suggestedAction?.let { !AutoApprovalPolicy.isPolicyCriticalAction(it) } ?: true

    /** Persists as a PENDING row — the only thing the worker does with a suggestion. */
    fun toEntity() = ProactiveSuggestionEntity(
        title = title,
        body = body,
        suggestedAction = suggestedAction,
        createdAt = createdAt,
        status = ProactiveSuggestionEntity.STATUS_PENDING
    )
}
