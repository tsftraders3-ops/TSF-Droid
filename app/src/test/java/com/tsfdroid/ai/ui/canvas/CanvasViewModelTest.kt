package com.tsfdroid.ai.ui.canvas

import com.tsfdroid.ai.core.canvas.CanvasArtifact
import com.tsfdroid.ai.core.canvas.CanvasArtifactBus
import com.tsfdroid.ai.core.canvas.CanvasPlanPublisher
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * God-Tier Phase 2, ticket 5.2 (Canvas Workspace) — the ViewModel contract.
 *
 * The Canvas receives artifacts through a single SharedFlow-based bus so that
 * every producer (PlanManager today, AgentLoop emissions tomorrow) funnels
 * through the same channel the ViewModel exposes. These tests pin that
 * contract BEFORE the UI exists: state starts empty, publishing flows to
 * subscribers, a late subscriber sees the last artifact (replay), and clear
 * returns the surface to empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelTest {

    /** viewModelScope uses Dispatchers.Main — a controllable Main is mandatory. */
    class MainDispatcherRule(
        @OptIn(ExperimentalCoroutinesApi::class)
        private val dispatcher: TestDispatcher = StandardTestDispatcher()
    ) : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun plan(planId: String = "plan-1", vararg stepDescriptions: String): Plan = Plan(
        planId = planId,
        goal = "Morning briefing",
        estimatedDuration = "2 min",
        estimatedSteps = stepDescriptions.size,
        steps = stepDescriptions.mapIndexed { index, description ->
            PlanStep(
                stepId = "$planId-step-${index + 1}",
                order = index + 1,
                description = description,
                action = "OPEN_APP",
                status = StepStatus.PENDING
            )
        }
    )

    @Test
    fun `artifact state starts null`() = runTest {
        val viewModel = CanvasViewModel(CanvasArtifactBus())

        assertNull(viewModel.artifact.value)
    }

    @Test
    fun `publish flows artifact to subscribers`() = runTest {
        val viewModel = CanvasViewModel(CanvasArtifactBus())
        backgroundScope.launch { viewModel.artifact.collect { } }

        val plan = plan("Check the news", "Summarize the weather")
        viewModel.publish(CanvasArtifact.PlanArtifact(plan))
        advanceUntilIdle()

        assertEquals(CanvasArtifact.PlanArtifact(plan), viewModel.artifact.value)
    }

    @Test
    fun `replay restores the last artifact for a late subscriber`() = runTest {
        val sharedBus = CanvasArtifactBus()
        val first = CanvasViewModel(sharedBus)
        val text = CanvasArtifact.TextArtifact(title = "Notes", body = "buy milk")
        first.publish(text)
        advanceUntilIdle()

        // A NEW subscriber (process restart, re-entering the Canvas) must see
        // the last artifact without anyone re-publishing it.
        val second = CanvasViewModel(sharedBus)
        backgroundScope.launch { second.artifact.collect { } }
        advanceUntilIdle()

        assertEquals(text, second.artifact.value)
    }

    @Test
    fun `clearArtifact returns the surface to empty`() = runTest {
        val viewModel = CanvasViewModel(CanvasArtifactBus())
        backgroundScope.launch { viewModel.artifact.collect { } }
        viewModel.publish(CanvasArtifact.FileArtifact("report.md", "# Report", 9))
        advanceUntilIdle()
        assertNotNull(viewModel.artifact.value)

        viewModel.clearArtifact()
        advanceUntilIdle()

        assertNull(viewModel.artifact.value)
    }

    @Test
    fun `plan publisher bridges PlanManager flow into the bus`() = runTest {
        val plans = MutableStateFlow<Plan?>(null)
        val bus = CanvasArtifactBus()
        val publisher = CanvasPlanPublisher(plans, bus)

        var observed: CanvasArtifact? = null
        val job = launch {
            bus.artifacts.collect { observed = it }
        }
        // The publisher's scope in tests: a FOREGROUND test dispatcher on the
        // same scheduler, so advanceUntilIdle deterministically runs its
        // collection loop (backgroundScope tasks are drained by different
        // machinery and are not advanced the same way). Production passes the
        // application scope; start(scope) is the seam.
        val publisherScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.Job()
        )
        try {
            // Not started: no plan flows even when the source emits.
            plans.value = plan("Nothing happens")
            advanceUntilIdle()
            assertNull(observed)

            // Started: the current plan and every later plan flows to the bus.
            publisher.start(publisherScope)
            advanceUntilIdle()
            assertTrue(observed is CanvasArtifact.PlanArtifact)

            plans.value = plan("plan-2", "Updated")
            advanceUntilIdle()
            val second = observed
            assertTrue(second is CanvasArtifact.PlanArtifact)
            assertEquals("plan-2", (second as CanvasArtifact.PlanArtifact).plan.planId)
        } finally {
            job.cancel()
            publisherScope.cancel()
        }
    }
}
