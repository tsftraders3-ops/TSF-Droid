package com.tsfdroid.ai.core.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * v1.2.0: one visible step the agent took while producing a reply — the
 * Claude / OpenCode "show your work" surface.
 *
 * Steps are published LIVE while the turn runs (the chat screen renders them
 * under the thinking indicator, growing in real time) and persisted on the
 * final agent message as a JSON array ([com.tsfdroid.ai.data.models.ChatMessage.stepsJson]),
 * so opening the chat later still shows exactly how the answer was produced.
 *
 * Kinds (kept as strings for forward-compatible persistence):
 *  - "tool"        a tool call: label = tool name, detail = one-line result
 *  - "continuation" the answer hit the output limit and the harness
 *                  automatically continued it (OpenCode behavior)
 *  - "compaction"  the conversation history was summarized to stay inside
 *                  the context window (the 75% rule)
 *  - "plan_step"   one todo-list step of an agent-mode plan executed
 *  - "vision"      an image was described by a vision-capable fallback model
 *                  because the active model cannot see images
 */
@Serializable
data class ActivityStep(
    val kind: String,
    val label: String,
    val detail: String = "",
    /** "running" | "done" | "error" */
    val status: String = "done",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_TOOL = "tool"
        const val KIND_CONTINUATION = "continuation"
        const val KIND_COMPACTION = "compaction"
        const val KIND_PLAN_STEP = "plan_step"
        const val KIND_VISION = "vision"
        /** The model answered a long-form ask too briefly; one bounded expansion pass. */
        const val KIND_EXPANSION = "expansion"

        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_ERROR = "error"
    }
}

/**
 * JSON (de)serialization for the persisted step trace. Defensive: a corrupt
 * array decodes to whatever parses instead of breaking the chat renderer.
 */
object ActivitySteps {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(steps: List<ActivityStep>): String? =
        if (steps.isEmpty()) {
            null
        } else {
            runCatching { json.encodeToString(steps) }.getOrElse { error ->
                android.util.Log.w("ActivitySteps", "step trace encode failed: ${error.localizedMessage}")
                null
            }
        }

    fun decode(encoded: String?): List<ActivityStep> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ActivityStep>>(encoded) }
            .getOrElse { emptyList() }
    }
}
