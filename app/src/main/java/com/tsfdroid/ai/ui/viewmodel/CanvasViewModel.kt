package com.tsfdroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tsfdroid.ai.core.canvas.CanvasArtifact
import com.tsfdroid.ai.core.canvas.CanvasArtifactBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * God-Tier Phase 2, ticket 5.2 — the Canvas workspace's view state.
 *
 * The ViewModel is deliberately THIN: it exposes the shared artifact flow as
 * UI state and forwards publish/clear intents onto the bus. All real wiring
 * (plan emissions -> bus) lives in
 * [com.tsfdroid.ai.core.canvas.CanvasPlanPublisher], so this class has a
 * single, dependency-light constructor — the UI tests drive the REAL
 * ViewModel + REAL bus through [publish], exactly like a wired producer
 * would.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val bus: CanvasArtifactBus
) : ViewModel() {

    /** The artifact currently shown in the canvas pane (null = empty state). */
    val artifact: StateFlow<CanvasArtifact?> = bus.artifacts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** Publishes an artifact to the canvas surface. The producer-facing seam. */
    fun publish(artifact: CanvasArtifact) {
        bus.publish(artifact)
    }

    /** Closes the current artifact; the pane returns to its empty state. */
    fun clearArtifact() {
        bus.clear()
    }
}
