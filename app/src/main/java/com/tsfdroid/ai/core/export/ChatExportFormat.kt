package com.tsfdroid.ai.core.export

import com.tsfdroid.ai.core.harness.ActivityStep
import com.tsfdroid.ai.core.harness.ToolCallRecord
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.repository.ChatSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * v1.4.0 chat export: the raw-text export document builder — PURE Kotlin,
 * no Android imports, so the JVM test rig compiles and round-trips it.
 *
 * THE FORMAT (deliberately chosen over plain text / markdown):
 * JSON is lossless, machine-parseable, and diffable — a user debugging a bad
 * answer can hand ONE file to anyone (or any tool) and the whole turn is
 * there: the exact text, the model's full reasoning trace, how long it
 * thought, every tool call with the raw arguments the model sent, the
 * params actually dispatched, the real (redacted, capped) results, per-call
 * durations, and the visible step trace.
 *
 * The bar this format is held against (gauntlet):
 *  - ChatGPT's official `conversations.json` export: same coverage (roles,
 *    content, timestamps, model, tool calls) plus what theirs lacks —
 *    thinking traces, thinking duration, per-call timings.
 *  - OpenCode's session JSONL storage: same per-message completeness
 *    (text / reasoning / tool parts) plus numeric durations.
 *
 * Honest fidelity limits, stated IN the document itself:
 *  - image payloads (base64) are NOT embedded — counts and metadata only
 *  - tool results are capped at [ToolCallRecord] RESULT_CAP chars per call,
 *    flagged `truncated: true`
 *  - secrets are stripped at capture time (CrashLogRedactor), not here.
 */
object ChatExportFormat {

    const val FORMAT_ID = "tsfdroid-chat-export"
    const val FORMAT_VERSION = 1

    /** App identity carried in the document header. */
    data class AppInfo(val versionName: String, val versionCode: Int)

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prettyJson = Json { prettyPrint = true }
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

    // ---------- document assembly ----------

    fun buildSessionDocument(
        session: ChatSession,
        messages: List<ChatMessage>,
        app: AppInfo,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val root = buildJsonObject {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            putJsonObject("exportedAt") { putStamp(exportedAtMs) }
            putJsonObject("app") {
                put("versionName", app.versionName)
                put("versionCode", app.versionCode)
            }
            putJsonObject("session") {
                put("id", session.id)
                put("title", session.title)
                putJsonObject("createdAt") { putStamp(session.createdAt) }
                putJsonObject("updatedAt") { putStamp(session.updatedAt) }
                put("messageCount", messages.size)
            }
            putJsonArray("fidelityNotes") {
                add(str("Image payloads are not embedded; counts and file metadata only."))
                add(str("Tool results are capped at 20000 chars per call; truncated=true flags the cut."))
                add(str("Credential material is stripped at capture time before storage."))
            }
            putJsonArray("messages") {
                messages.forEach { msg -> add(messageObject(msg)) }
            }
        }
        // prettyPrinted: the file doubles as human-readable raw text.
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun messageObject(msg: ChatMessage): JsonObject = buildJsonObject {
        put("id", msg.id)
        put("role", if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant")
        putJsonObject("timestamp") { putStamp(msg.timestamp) }
        put("text", msg.text)
        // Model identity: the provider badge (UI chip) + the concrete model id
        // when the v1.4.0 capture recorded it. Both nullable, honestly.
        putJsonObject("model") {
            put("provider", msg.modelBadge.orJsonNull())
            put("modelId", msg.modelId.orJsonNull())
        }
        // The reasoning trace + its measured duration.
        if (msg.thinkingText != null || msg.thinkingDurationMs != null) {
            putJsonObject("thinking") {
                put("text", msg.thinkingText.orJsonNull())
                put("durationMs", msg.thinkingDurationMs.orJsonNull())
            }
        } else {
            put("thinking", JsonNull)
        }
        // Harness usage for the turn (null when the streamed path produced the
        // answer alone and no usage was surfaced).
        if (msg.tokensUsed != null || msg.turnLatencyMs != null) {
            putJsonObject("usage") {
                put("tokens", msg.tokensUsed.orJsonNull())
                put("latencyMs", msg.turnLatencyMs.orJsonNull())
            }
        } else {
            put("usage", JsonNull)
        }
        putJsonArray("toolCalls") {
            msg.toolCallRecords().forEach { record -> add(toolCallObject(record)) }
        }
        putJsonArray("activitySteps") {
            msg.activitySteps().forEach { step -> add(stepObject(step)) }
        }
        put("artifact", artifactObject(msg.attachmentJson))
        put("attachments", attachmentsObject(msg))
        put("screenshotAttached", msg.imageBase64 != null)
        msg.askOptions()?.let { options ->
            putJsonObject("askOptions") {
                put("header", options.header)
                putJsonArray("options") { options.options.forEach { add(str(it)) } }
            }
        }
    }

    private fun toolCallObject(record: ToolCallRecord): JsonObject = buildJsonObject {
        put("tool", record.tool)
        put("action", record.action)
        put("argumentsRaw", record.argumentsRaw)
        putJsonObject("params") {
            record.params.forEach { (k, v) -> put(k, v) }
        }
        put("success", record.success)
        put("result", record.result.orJsonNull())
        put("truncated", record.truncated)
        put("error", record.error.orJsonNull())
        putJsonObject("startedAt") { putStamp(record.startedAt) }
        put("durationMs", record.durationMs)
    }

    private fun stepObject(step: ActivityStep): JsonObject = buildJsonObject {
        put("kind", step.kind)
        put("label", step.label)
        put("detail", step.detail)
        put("status", step.status)
        putJsonObject("timestamp") { putStamp(step.timestamp) }
    }

    /** The agent-created file card: {"name","path","mime","size"}. */
    private fun artifactObject(attachmentJson: String?): JsonElement {
        return runCatching {
            if (attachmentJson.isNullOrBlank()) return JsonNull
            val obj = lenientJson.parseToJsonElement(attachmentJson) as? JsonObject ?: return JsonNull
            buildJsonObject {
                put("name", obj.strOrNull("name").orJsonNull())
                put("path", obj.strOrNull("path").orJsonNull())
                put("mime", obj.strOrNull("mime").orJsonNull())
                put("size", obj["size"]?.toString()?.trim('"')?.toLongOrNull().orJsonNull())
            }
        }.getOrDefault(JsonNull)
    }

    /**
     * User uploads: metadata only — image counts (never base64 payloads) and
     * the file list with names/mimes/sizes. The processing notes ride along;
     * they explain what the model was actually given.
     */
    private fun attachmentsObject(msg: ChatMessage): JsonElement {
        val attachments = msg.attachments() ?: return JsonNull
        return buildJsonObject {
            put("imageCount", attachments.images.size)
            put("images", "not embedded (base64 payloads excluded from exports)")
            putJsonArray("files") {
                attachments.files.forEach { file ->
                    add(buildJsonObject {
                        put("name", file.name)
                        put("mime", file.mime)
                        put("size", file.size)
                    })
                }
            }
            putJsonArray("notes") { attachments.notes.forEach { add(str(it)) } }
        }
    }

    // ---------- helpers ----------

    private fun str(value: String): JsonPrimitive = JsonPrimitive(value)

    private fun String?.orJsonNull(): JsonElement =
        this?.let { JsonPrimitive(it) } ?: JsonNull

    private fun Long?.orJsonNull(): JsonElement =
        this?.let { JsonPrimitive(it) } ?: JsonNull

    private fun Int?.orJsonNull(): JsonElement =
        this?.let { JsonPrimitive(it) } ?: JsonNull

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObjectBuilder.putStamp(epochMs: Long) {
        put("epochMs", epochMs)
        put("iso8601", isoFormatter.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC)))
    }

    /**
     * Filesystem-safe export file name for a session, e.g.
     * `chat-gold-price-question-20261004-063012.json`. Pure so the rig tests it.
     */
    fun suggestFileName(sessionTitle: String, exportedAtMs: Long): String {
        val sanitized = sessionTitle.trim()
            .replace(Regex("[^a-zA-Z0-9 ]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .take(48)
            .lowercase()
            .ifBlank { "chat" }
        val stamp = LocalDateTime
            .ofInstant(Instant.ofEpochMilli(exportedAtMs), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "chat-$sanitized-$stamp.json"
    }
}
