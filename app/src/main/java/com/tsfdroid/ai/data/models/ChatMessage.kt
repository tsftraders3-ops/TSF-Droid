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
    val thinkingText: String? = null,
    /**
     * File attachment card (v1.0.6): JSON {"name","path","mime","size"} for
     * a REAL file the agent created (WRITE_FILE / CREATE_PDF). Rendered as a
     * tappable file card with Open/Share — created artifacts are files a
     * user can open directly from the chat, never just a path in text.
     */
    val attachmentJson: String? = null,
    /**
     * v1.2.0 user uploads: JSON [MessageAttachments] carried on the message
     * the user attached files/images to. Images are pre-processed, send-ready
     * base64 JPEGs; text files carry their inline text; PDFs/videos arrive as
     * rendered page/frame images plus a note. Null for messages without uploads.
     */
    val attachmentsJson: String? = null,
    /**
     * v1.2.1 Hermes loop: JSON array of ActivityStep records — the VISIBLE
     * steps the agent took to produce this reply (tool calls with one-line
     * results, output-limit continuations, context compactions, plan steps,
     * vision routing). Rendered as a collapsible ACTIVITY section, Claude /
     * OpenCode style. Null for replies without recorded steps.
     */
    val stepsJson: String? = null,
    /**
     * v1.3.0 ask_user tool: JSON [AskOptions] payload for a question the
     * agent asked the user mid-turn (options they can tap + the live answer
     * surface). Persisted to Room (v14) so the tappable chips survive
     * process death and reload with the history.
     */
    val askOptionsJson: String? = null,
    /**
     * v1.4.0 chat export: the measured reasoning phase of this reply in
     * milliseconds (first reasoning delta -> first content delta, plus any
     * harness tool phase, Claude-style). The UI renders it as the
     * "Thought for Xs" step label; the number itself is persisted so the
     * raw-text export carries it without label parsing. Null when the turn
     * had no measured thinking phase.
     */
    val thinkingDurationMs: Long? = null,
    /**
     * v1.4.0 chat export: the concrete model id that answered this turn
     * (e.g. "qwen/qwen3-coder"). [modelBadge] stays the provider name for
     * the UI chip; this is the machine-readable identity for the export.
     */
    val modelId: String? = null,
    /** v1.4.0 chat export: tokens reported by the provider for this turn's model calls (null when the streamed path did not surface usage). */
    val tokensUsed: Int? = null,
    /** v1.4.0 chat export: total model-call latency of the turn's harness phase in ms (null when the streamed path produced the answer alone). */
    val turnLatencyMs: Long? = null,
    /**
     * v1.4.0 chat export: JSON array of [ToolCallRecord] — the FULL log of
     * every tool call behind this reply (raw arguments, mapped params,
     * capped-but-real results, per-call duration). The 160-char UI trace
     * stays in [stepsJson]; this is the debugging-fidelity record the
     * raw-text export is built on.
     */
    val toolCallsJson: String? = null,
    /**
     * v1.6.0 (field P2-6): the mode this turn ran in - "CHAT" or "AGENT".
     * The v1.5.0 field analysis could not tell which mode produced a turn
     * (its section 10 limitation #1); now every persisted message carries it.
     * Null on legacy rows and non-turn messages (asks, traces).
     */
    val mode: String? = null,
    /**
     * v1.6.0 (field P2-5): the turn's WALL-CLOCK duration in ms - from the
     * user's send to the final save, including every expansion/continuation
     * phase. The field report's timing profile found ~9 minutes unaccounted
     * on long turns because latencyMs only covers harness model calls; this
     * closes that gap.
     */
    val turnWallMs: Long? = null
) {
    enum class Sender {
        USER, AGENT
    }

    /** Parsed [askOptionsJson]; null when this message is not an ask_user question. */
    fun askOptions(): AskOptions? = parseAskOptions(askOptionsJson)

    /** Parsed activity trace for this reply; empty when none was recorded. */
    fun activitySteps(): List<com.tsfdroid.ai.core.harness.ActivityStep> =
        com.tsfdroid.ai.core.harness.ActivitySteps.decode(stepsJson)

    /** Parsed [attachmentsJson]; null when the message carries no uploads. */
    fun attachments(): MessageAttachments? = parseMessageAttachments(attachmentsJson)

    /** Every send-ready image on this message: screenshot + uploaded images. */
    fun allImages(): List<String> = buildList {
        imageBase64?.let(::add)
        attachments()?.images?.let { addAll(it) }
    }

    /** Parsed tool-call log for this reply; empty when none was recorded. */
    fun toolCallRecords(): List<com.tsfdroid.ai.core.harness.ToolCallRecord> =
        com.tsfdroid.ai.core.harness.ToolCallRecords.decode(toolCallsJson)
}

/** v1.2.0: user-uploaded content carried on a [ChatMessage]. */
@Serializable
data class MessageAttachments(
    /** Send-ready base64 JPEG images (already downscaled/normalized). */
    val images: List<String> = emptyList(),
    /** Metadata + (optionally) inline text for document/code uploads. */
    val files: List<AttachmentFile> = emptyList(),
    /** Human/model-readable processing notes ("PDF page images", "video frames"). */
    val notes: List<String> = emptyList()
) {
    val hasImages: Boolean get() = images.isNotEmpty()
    val isEmpty: Boolean get() = images.isEmpty() && files.isEmpty() && notes.isEmpty()
}

/**
 * v1.3.0: payload of an ask_user tool question — the options the user can
 * tap and the header line for the answer surface. Mirrors opencode's
 * question tool contract (question + options + free-text always allowed).
 */
@Serializable
data class AskOptions(
    /** Short header shown on the answer surface (defaults to "Quick answer"). */
    val header: String = "Quick answer",
    /** Tappable answer options; may be empty when the answer is free-text. */
    val options: List<String> = emptyList()
)

private val askOptionsJsonFormat = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun parseAskOptions(json: String?): AskOptions? {
    if (json.isNullOrBlank()) return null
    return runCatching { askOptionsJsonFormat.decodeFromString<AskOptions>(json) }.getOrNull()
}

fun serializeAskOptions(options: AskOptions): String =
    askOptionsJsonFormat.encodeToString(AskOptions.serializer(), options)

@Serializable
data class AttachmentFile(
    val name: String,
    val mime: String,
    val size: Long = 0,
    /** Extracted text content (capped) for text-like files; null otherwise. */
    val inlineText: String? = null
)

private val attachmentsJsonFormat = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun parseMessageAttachments(json: String?): MessageAttachments? {
    if (json.isNullOrBlank()) return null
    return runCatching { attachmentsJsonFormat.decodeFromString<MessageAttachments>(json) }.getOrNull()
}

fun serializeMessageAttachments(attachments: MessageAttachments): String =
    attachmentsJsonFormat.encodeToString(MessageAttachments.serializer(), attachments)
