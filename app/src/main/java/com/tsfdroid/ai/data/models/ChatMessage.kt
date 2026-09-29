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
    val stepsJson: String? = null
) {
    enum class Sender {
        USER, AGENT
    }

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
