package com.tsfdroid.ai.core.llm.providers

import com.tsfdroid.ai.core.llm.LLMRequest
import org.json.JSONObject

/**
 * v1.3.0 (Phase 14 WAVE C): the shared wire translation for the
 * reasoning-effort selector.
 *
 * Since v1.2.0 the chat UI has offered per-model effort levels and carried
 * the selection on [LLMRequest.reasoningEffort] — but only
 * [OpenCodeZenProvider] ever put it on the wire. Every other OpenAI-compatible
 * provider silently ignored the user's selection, so outside Zen the selector
 * lied. This object is the single gate all of those providers now share.
 *
 * Real effort control, never cosmetic: the field is only sent when (a) the
 * user picked a level AND (b) the model's models.dev registry spec
 * ([ZenModelSpec], served by [ModelsDevRegistry]) actually lists that level —
 * the same rule the Zen provider has enforced since v1.2.0 (see its
 * `runStreamAttempt` reasoning-effort block). A null spec (unknown model),
 * a blank selection, or a level the model does not expose omits the field
 * instead of firing it at an endpoint that would reject or ignore it.
 */
object ReasoningEffort {

    /** The OpenAI chat-completions field name for effort selection. */
    const val WIRE_FIELD = "reasoning_effort"

    /**
     * v1.3.0: applies the user's reasoning-effort selection to an OpenAI-style
     * chat-completions request body. Real effort control, never cosmetic:
     * the field is only sent when (a) the user picked a level AND (b) the
     * model's registry spec actually lists that level — same rule the Zen
     * provider uses. [body] is mutated in place (callers build a
     * JSONObject/Map before this).
     */
    fun applyToBody(body: JSONObject, request: LLMRequest, modelSpec: ZenModelSpec?) {
        val effort = wireEffort(request, modelSpec) ?: return
        body.put(WIRE_FIELD, effort)
    }

    /**
     * v1.3.0: Map-based twin of [applyToBody] for the providers that build
     * their chat-completions body as a `MutableMap<String, Any>` before Gson
     * serialization (the OpenAI-compatible provider template). Same gate,
     * same field, mutated in place.
     */
    fun applyToBody(body: MutableMap<String, Any>, request: LLMRequest, modelSpec: ZenModelSpec?) {
        val effort = wireEffort(request, modelSpec) ?: return
        body[WIRE_FIELD] = effort
    }

    /**
     * v1.3.0: one-call wiring for an OpenAI-compatible provider that has no
     * registry lookup of its own — resolves the model's spec from the shared
     * models.dev registry (the exact guarded lookup the Zen provider uses:
     * `runCatching { registry.specs()[modelId] }`, any failure degrades to
     * "unknown model" and the field is simply not sent) and applies it to
     * [body].
     *
     * The registry is consulted ONLY when the request actually carries a
     * selection, so a no-effort request keeps its exact pre-v1.3.0 wire shape
     * and makes zero additional network calls. Once a selection exists the
     * registry's own single-flight cache (24h success TTL / 30min failure
     * cooldown) bounds the cost to one fetch per process.
     */
    suspend fun applyToBody(
        body: MutableMap<String, Any>,
        request: LLMRequest,
        modelId: String,
        registry: ModelsDevRegistry
    ) {
        if (request.reasoningEffort.isNullOrBlank()) return
        // v1.3.0: modelInfo() — the ALL-provider map. specs() only carries the
        // Zen "opencode" section, so every other provider's models always
        // resolved to null here and the field was never sent (the selector
        // stayed a Zen-only lie — the exact defect the critic proved).
        val modelSpec = runCatching { registry.modelInfo()[modelId] }.getOrNull()
        applyToBody(body, request, modelSpec)
    }

    /**
     * The single gate, mirroring OpenCodeZenProvider's live-verified rule:
     * a non-blank selection that case-insensitively matches one of the
     * spec's [ZenModelSpec.reasoningLevels] is sent lowercased (the wire
     * contract's casing); everything else stays absent.
     */
    private fun wireEffort(request: LLMRequest, modelSpec: ZenModelSpec?): String? {
        if (modelSpec == null) return null
        val requested = request.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (modelSpec.reasoningLevels.any { it.equals(requested, ignoreCase = true) }) {
            requested.lowercase()
        } else {
            null
        }
    }
}
