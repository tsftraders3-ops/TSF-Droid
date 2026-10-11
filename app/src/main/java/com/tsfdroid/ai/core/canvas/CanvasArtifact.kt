package com.tsfdroid.ai.core.canvas

import com.tsfdroid.ai.data.models.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * God-Tier Phase 2, ticket 5.2 — the unit of content the Canvas workspace
 * renders in its artifact pane.
 *
 * The Canvas is producer-agnostic: anything that can be reduced to one of
 * these shapes can be shown next to the chat. The MVP ships the three types
 * the artifact viewer knows how to render:
 *  - [PlanArtifact] — the agent's live plan (steps, statuses, goal);
 *  - [TextArtifact] — titled prose (drafts, briefings, answers);
 *  - [FileArtifact] — an exported file's name plus a content preview.
 */
sealed interface CanvasArtifact {

    /** A live plan. Step-status changes republish the whole plan (immutable data class). */
    data class PlanArtifact(val plan: Plan) : CanvasArtifact

    /** Titled prose. */
    data class TextArtifact(val title: String, val body: String) : CanvasArtifact

    /** A file-shaped artifact: name, preview content, and its size in bytes. */
    data class FileArtifact(
        val fileName: String,
        val content: String,
        val sizeBytes: Long
    ) : CanvasArtifact
}

/**
 * The single shared-flow channel every canvas producer funnels through.
 *
 * One bus, N producers: [CanvasPlanPublisher] bridges PlanManager's plan
 * emissions today, and any future producer (AgentLoop artifact emissions,
 * file exports) publishes through the same [publish] surface — the Canvas
 * ViewModel is the only consumer the UI ever sees.
 *
 * The bus is a [StateFlow] — the [SharedFlow] specialization that always
 * holds the latest value — because a canvas surface wants "current
 * artifact" semantics, not an event log:
 *  - a late subscriber (navigating to the Canvas, process restore)
 *    immediately receives the current artifact instead of waiting for the
 *    next agent action;
 *  - rapid republishes (plan step-status churn) conflate to the newest
 *    artifact, which is correct for a live-content pane;
 *  - [clear] publishes `null`, so "close artifact" is itself durable —
 *    a subscriber that arrives after the clear sees an empty canvas, not a
 *    resurrected artifact.
 */
@Singleton
class CanvasArtifactBus @Inject constructor() {

    private val _artifacts = MutableStateFlow<CanvasArtifact?>(null)

    /** The current artifact stream (null = empty canvas). See class docs for the semantics. */
    val artifacts: StateFlow<CanvasArtifact?> = _artifacts.asStateFlow()

    /** Publishes the newest artifact to every current and future subscriber. */
    fun publish(artifact: CanvasArtifact) {
        _artifacts.value = artifact
    }

    /** Empties the canvas surface durably: current and future subscribers see null. */
    fun clear() {
        _artifacts.value = null
    }
}
