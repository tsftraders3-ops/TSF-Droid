package com.tsfdroid.ai.core.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * v1.4.0 chat export: the FULL record of one tool call the agent executed —
 * the debugging counterpart of the 160-char [ActivityStep] the chat UI shows.
 *
 * The visible ACTIVITY trace is deliberately terse (one line per call) so the
 * chat stays readable. But "what exactly did the agent do" — the question a
 * user debugging a bad answer needs answered — requires the real parameters
 * the model sent, the action they were mapped onto, the full result (not a
 * snippet), whether the call succeeded, and how long it took. Those live here
 * and are persisted per reply as [com.tsfdroid.ai.data.models.ChatMessage.toolCallsJson],
 * then rendered into the raw-text chat export ([format]) alongside the
 * thinking trace and the answer itself.
 *
 * [result] is capped at [ToolCallRecords.RESULT_CAP] characters per call
 * (a FETCH_URL page dump can be hundreds of kilobytes) with [truncated]
 * flagging the cut honestly; the raw model arguments are kept verbatim
 * ([argumentsRaw]) next to the mapped execution params ([params]) so plan vs
 * dispatch divergence is visible in one glance.
 */
@Serializable
data class ToolCallRecord(
    /** The model-facing tool name as called ("web_search", "shell", "ask_user"). */
    val tool: String,
    /** The app action the call was mapped onto ("WEB_SEARCH"), or "" when unmapped. */
    val action: String = "",
    /** The model's raw JSON arguments, verbatim as received. */
    val argumentsRaw: String = "",
    /** The params actually dispatched (post ToolCallBridge / plan resolution). */
    val params: Map<String, String> = emptyMap(),
    val success: Boolean,
    /** Redacted, capped output; null when the call produced no data. */
    val result: String? = null,
    /** True when [result] was cut at [ToolCallRecords.RESULT_CAP]. */
    val truncated: Boolean = false,
    /** Error text on failure; null on success. */
    val error: String? = null,
    /** Wall-clock start of the execution, epoch ms. */
    val startedAt: Long = System.currentTimeMillis(),
    /** Execution duration, ms — how long the tool itself took. */
    val durationMs: Long = 0
)

/**
 * JSON (de)serialization for the persisted per-reply tool-call log. Defensive
 * like [ActivitySteps]: a corrupt array decodes to whatever parses instead of
 * breaking the history loader.
 */
object ToolCallRecords {

    /** Per-call result cap for persistence — far beyond the 160-char UI snippet. */
    const val RESULT_CAP = 20_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(ToolCallRecord.serializer())

    /** Caps [raw] at [RESULT_CAP]; returns (text, truncated). */
    fun capResult(raw: String?): Pair<String?, Boolean> {
        if (raw == null) return null to false
        if (raw.length <= RESULT_CAP) return raw to false
        return raw.take(RESULT_CAP) to true
    }

    fun encode(records: List<ToolCallRecord>): String? =
        if (records.isEmpty()) {
            null
        } else {
            // Silent-null on failure (no android.util.Log — this file is pure
            // Kotlin so the JVM test rig can compile it; a corrupt encode
            // simply persists no tool log, the UI trace is unaffected).
            runCatching { json.encodeToString(serializer, records) }.getOrNull()
        }

    fun decode(encoded: String?): List<ToolCallRecord> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, encoded) }
            .getOrElse { emptyList() }
    }
}
