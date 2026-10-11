package com.tsfdroid.ai.core.canvas

import com.tsfdroid.ai.core.agent.PlanManager
import com.tsfdroid.ai.data.models.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * God-Tier Phase 2, ticket 5.2 — bridges the agent's plan emissions onto the
 * canvas bus.
 *
 * The Canvas never talks to [PlanManager] directly: this publisher is the
 * ONLY plan wiring, so the ViewModel stays a plain consumer of the bus and
 * stays trivially constructible for UI tests. Started once from the
 * application scope ([com.tsfdroid.ai.OpenDroidApp]); it is idempotent —
 * calling [start] twice just adds a second collector, which the bus's
 * conflating StateFlow tolerates — but the app starts it exactly once.
 *
 * Every plan mutation (step RUNNING -> COMPLETED, param edits, merges)
 * flows through `PlanManager.currentPlan` and is republished as a new
 * immutable [CanvasArtifact.PlanArtifact], which is what keeps the artifact
 * pane LIVE rather than a snapshot.
 */
@Singleton
class CanvasPlanPublisher @Inject constructor(
    private val plans: StateFlow<Plan?>,
    private val bus: CanvasArtifactBus
) {
    /**
     * Starts collecting plan emissions into the bus on the given scope.
     * The current plan (if any) is published immediately, so opening the
     * Canvas mid-task shows the running plan instead of an empty pane.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            plans.collect { plan ->
                    // A plan going away (clearPlan) does NOT clear the canvas:
                    // the last artifact stays visible for reading. Only an
                    // explicit user "close" clears the bus.
                    if (plan != null) {
                        bus.publish(CanvasArtifact.PlanArtifact(plan))
                    }
                }
        }
    }
}
