package com.tsfdroid.ai.core.llm

import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.parseMessageAttachments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

@Serializable
sealed class StreamChunk {
    @Serializable
    data class Content(val text: String) : StreamChunk()
    @Serializable
    data class ToolCall(val name: String, val arguments: String) : StreamChunk()
}

/**
 * Detailed stream surface for chat UIs (v1.0.5): content deltas plus the
 * "thinking" deltas reasoning models emit (`reasoning` / `reasoning_content`
 * on the OpenCode Zen free tier). Reasoning events are never answer content —
 * the UI renders them in a separate collapsible THINKING section so the user
 * can watch what the agent is thinking while it works.
 */
sealed class LLMStreamEvent {
    data class Content(val text: String) : LLMStreamEvent()
    data class Reasoning(val text: String) : LLMStreamEvent()

    /**
     * v1.2.0: emitted once at the end of a successful stream with the final
     * OpenAI `finish_reason` ("stop", "length", "tool_calls", ...). The
     * harness loop keys auto-continuation on `"length"` — the signal that
     * the model hit its output budget mid-answer and the app must call the
     * API again to finish the reply. Providers without a finish surface
     * simply never emit it; consumers must treat absence as "stop".
     */
    data class Finished(val reason: String?) : LLMStreamEvent()
}

interface AIProvider {
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamChunk>
}

interface LLMProvider : AIProvider {
    val name: String
    val availableModels: List<String>
    suspend fun complete(request: LLMRequest): LLMResponse
    fun streamComplete(request: LLMRequest): Flow<String>
    suspend fun isAvailable(): Boolean

    /**
     * Detailed streaming: content plus reasoning-model thinking deltas.
     * Default implementation wraps [streamComplete] as Content-only events,
     * so providers without a reasoning surface need no override.
     */
    fun streamCompleteDetailed(request: LLMRequest): Flow<LLMStreamEvent> = flow {
        streamComplete(request).collect { text -> emit(LLMStreamEvent.Content(text)) }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> {
        val systemPrompt = "You are an autonomous AI agent for Android."
        val request = LLMRequest(
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools.map { Tool(it.name, it.description, it.parameters) }
        )
        return kotlinx.coroutines.flow.flow {
            streamComplete(request).collect { text ->
                emit(StreamChunk.Content(text))
            }
        }
    }
}

/**
 * Ephemeral provider configuration captured with a resolved request. It is
 * deliberately excluded from serialization and renders only as redacted text.
 */
class ProviderRequestConfig(
    val apiKey: String,
    val endpoint: String
) {
    override fun toString(): String = "<redacted provider configuration>"
}

@Serializable
data class LLMRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    /**
     * Resolved by [WrappedLLMProvider] from the provider/model pairing. Direct
     * provider callers may leave it null and receive the catalog default.
     */
    val model: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val responseFormat: ResponseFormat = ResponseFormat.JSON,
    val tools: List<Tool>? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    /**
     * v1.0.6: when true, a tool-call answer is surfaced immediately as
     * [LLMResponse.toolCalls] instead of triggering the provider's internal
     * no-tools corrective re-ask. The chat tool loop sets this so the model's
     * `read`/`shell` calls execute natively; the planner keeps the default
     * (false) because it must answer with a JSON plan, not tool calls.
     */
    @Transient val allowToolCalls: Boolean = false,
    /**
     * v1.2.0 reasoning-effort selection (the OpenCode "variant" mechanism):
     * when non-null AND the model's registry entry lists this level, the
     * provider sends `reasoning_effort` on the wire. Silently ignored for
     * models without reasoning levels — the selector is real, not cosmetic.
     */
    @Transient val reasoningEffort: String? = null,
    /**
     * v1.2.0: the turn carries images the model must actually SEE. Providers
     * route these requests along a vision-capable model chain (registry
     * `modalities.input` contains "image") instead of whichever model is
     * pinned — a text model would silently ignore the images.
     */
    @Transient val requireVision: Boolean = false,
    @Transient
    val providerConfig: ProviderRequestConfig? = null
)

enum class ResponseFormat {
    JSON, TEXT
}

enum class RetryPolicy {
    DEFAULT,
    NONE
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

/**
 * One OpenAI-style function tool call the model emitted instead of (or along
 * with) prose. v1.0.6: the Zen free tier forces harness tools (`read`,
 * `shell`) into every request body, so reasoning models sometimes answer the
 * harness contract with actual tool calls. The provider surfaces them; the
 * agent loop executes the ones it can and re-asks for the rest.
 */
@Serializable
data class LLMToolCall(
    val name: String,
    val arguments: String,
    val id: String = ""
)

@Serializable
data class LLMResponse(
    val content: String,
    val tokensUsed: Int,
    val model: String,
    val provider: String,
    val latencyMs: Long,
    /** Non-empty when the model answered with function tool calls. */
    @Transient val toolCalls: List<LLMToolCall> = emptyList(),
    /**
     * v1.2.0: final OpenAI finish_reason of the stream ("stop", "length",
     * "tool_calls", ...). Null when the provider does not surface one.
     * `"length"` is the output-truncation signal the harness continuation
     * loop acts on — never treat it as a complete answer.
     */
    @Transient val finishReason: String? = null
)

/**
 * Builds the OpenAI wire shape for one user message body: the text part plus
 * every image the message carries (screenshot, uploads) as `image_url` parts.
 * Inline file text is appended to the text as structured blocks the model can
 * quote from — the OpenCode pattern for non-image attachments.
 */
private fun buildUserContent(msg: ChatMessage): Any {
    val images = msg.allImages()
    val attachments = parseMessageAttachments(msg.attachmentsJson)
    val inlineBlocks = attachments?.files?.mapNotNull { file ->
        val text = file.inlineText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        "[Attached file: ${file.name} (${file.mime})]\n${file.inlineText}"
    }.orEmpty()
    val notes = attachments?.notes.orEmpty()
    val textWithAttachments = buildString {
        append(msg.text)
        inlineBlocks.forEach { block ->
            append("\n\n")
            append(block)
        }
        notes.forEach { note ->
            append("\n\n[Attachment note: ")
            append(note)
            append("]")
        }
    }.ifBlank { msg.text }

    if (images.isEmpty()) return textWithAttachments
    val parts = mutableListOf<Map<String, Any>>(
        mapOf("type" to "text", "text" to textWithAttachments)
    )
    for (base64 in images) {
        parts.add(
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64")
            )
        )
    }
    return parts
}

fun List<ChatMessage>.toOpenAIMessages(systemPrompt: String): List<Map<String, Any>> {
    val messagesList = mutableListOf<Map<String, Any>>()
    messagesList.add(mapOf("role" to "system", "content" to systemPrompt))
    this.forEach { msg ->
        val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
        if (msg.sender == ChatMessage.Sender.USER && msg.allImages().isNotEmpty()) {
            messagesList.add(mapOf("role" to role, "content" to buildUserContent(msg)))
        } else {
            messagesList.add(mapOf("role" to role, "content" to msg.text))
        }
    }
    return messagesList
}
