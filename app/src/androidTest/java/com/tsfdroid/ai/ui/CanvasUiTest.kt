package com.tsfdroid.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tsfdroid.ai.core.canvas.CanvasArtifact
import com.tsfdroid.ai.core.canvas.CanvasArtifactBus
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.screens.CanvasScreen
import com.tsfdroid.ai.ui.theme.OpenDroidTheme
import com.tsfdroid.ai.ui.viewmodel.CanvasViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * God-Tier Phase 2, ticket 5.2 — the Canvas Workspace UI contract.
 *
 * The screen under test is the real production composable, driven by a real
 * [CanvasViewModel] over a real [CanvasArtifactBus]; the fake Plan artifact is
 * injected through the ViewModel's publish surface exactly like a wired
 * producer would. The chat pane slot receives a stand-in because the
 * production default pulls a Hilt-scoped ChatViewModel — the layout, panes,
 * tags, and artifact rendering under test are all production code.
 *
 * Layout adaptivity is exercised by constraining the composition's width:
 * BoxWithConstraints sees the real incoming constraints and must decide
 * split-pane (wide) vs single-pane-with-explicit-open (narrow) from them.
 */
@RunWith(AndroidJUnit4::class)
class CanvasUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fakePlan(): Plan = Plan(
        planId = "canvas-plan",
        goal = "Plan the day",
        estimatedDuration = "3 min",
        estimatedSteps = 3,
        steps = listOf(
            PlanStep("canvas-plan-step-1", 1, "Read the morning news", "OPEN_APP", status = StepStatus.COMPLETED),
            PlanStep("canvas-plan-step-2", 2, "Summarize the weather", "SEARCH_WEB", status = StepStatus.RUNNING),
            PlanStep("canvas-plan-step-3", 3, "Draft the briefing", "WRITE_FILE", status = StepStatus.PENDING)
        )
    )

    private fun launchCanvas(widthDp: Int, artifact: CanvasArtifact?) {
        val bus = CanvasArtifactBus()
        val viewModel = CanvasViewModel(bus)
        composeRule.setContent {
            OpenDroidTheme {
                // requiredWidth/Height OVERRIDE the incoming constraints (a
                // plain width() would be coerced down to the phone window's
                // ~411dp and silently test the compact layout instead).
                // BoxWithConstraints then genuinely measures the full
                // constrained width, exercising the real adaptive branch.
                Box(
                    Modifier
                        .requiredWidth(widthDp.dp)
                        .requiredHeight(800.dp)
                ) {
                    CanvasScreen(
                        viewModel = viewModel,
                        onBack = {},
                        chatPane = { paneModifier ->
                            Text(text = "chat pane content", modifier = paneModifier)
                        }
                    )
                }
            }
        }
        if (artifact != null) {
            composeRule.runOnIdle { viewModel.publish(artifact) }
        }
    }

    @Test
    fun wideLayout_showsBothPanes_andRendersPlanSteps() {
        launchCanvas(widthDp = 840, artifact = CanvasArtifact.PlanArtifact(fakePlan()))

        composeRule.onNodeWithTag("canvas_root").assertIsDisplayed()
        composeRule.onNodeWithTag("canvas_chat_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()

        // The artifact viewer renders every plan step's description.
        composeRule.onNodeWithText("Read the morning news").assertIsDisplayed()
        composeRule.onNodeWithText("Summarize the weather").assertIsDisplayed()
        composeRule.onNodeWithText("Draft the briefing").assertIsDisplayed()
        composeRule.onNodeWithText("Plan the day").assertIsDisplayed()
    }

    @Test
    fun wideLayout_artifactPaneShowsEmptyState_whenNothingPublished() {
        launchCanvas(widthDp = 840, artifact = null)

        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()
        composeRule.onNodeWithText("No artifact yet").assertIsDisplayed()
    }

    @Test
    fun narrowLayout_hidesArtifactPane_untilExplicitlyOpened() {
        launchCanvas(widthDp = 411, artifact = CanvasArtifact.PlanArtifact(fakePlan()))

        // Compact: chat pane is the surface; the artifact pane is NOT laid out.
        composeRule.onNodeWithTag("canvas_chat_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("canvas_artifact_pane").assertDoesNotExist()

        // The affordance to open it exists and opens the artifact pane.
        composeRule.onNodeWithTag("canvas_open_artifact").assertIsDisplayed()
        composeRule.onNodeWithTag("canvas_open_artifact").performClick()

        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()
        composeRule.onNodeWithText("Draft the briefing").assertIsDisplayed()
    }

    @Test
    fun narrowLayout_closingArtifactPane_returnsToChat() {
        launchCanvas(widthDp = 411, artifact = CanvasArtifact.PlanArtifact(fakePlan()))

        composeRule.onNodeWithTag("canvas_open_artifact").performClick()
        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()

        composeRule.onNodeWithTag("canvas_close_artifact").performClick()

        composeRule.onNodeWithTag("canvas_artifact_pane").assertDoesNotExist()
        composeRule.onNodeWithTag("canvas_chat_pane").assertIsDisplayed()
    }

    @Test
    fun narrowLayout_withoutArtifact_noOpenAffordance() {
        launchCanvas(widthDp = 411, artifact = null)

        composeRule.onNodeWithTag("canvas_chat_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("canvas_artifact_pane").assertDoesNotExist()
        composeRule.onNodeWithTag("canvas_open_artifact").assertDoesNotExist()
    }

    @Test
    fun textArtifact_rendersTitleAndBody() {
        launchCanvas(
            widthDp = 840,
            artifact = CanvasArtifact.TextArtifact(title = "Field notes", body = "The quick brown fox jumps over the lazy dog.")
        )

        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()
        composeRule.onNodeWithText("Field notes").assertIsDisplayed()
        composeRule.onNodeWithText("The quick brown fox jumps over the lazy dog.").assertIsDisplayed()
    }

    @Test
    fun fileArtifact_rendersFileNameAndContent() {
        launchCanvas(
            widthDp = 840,
            artifact = CanvasArtifact.FileArtifact(fileName = "briefing.md", content = "# Morning briefing", sizeBytes = 18)
        )

        composeRule.onNodeWithTag("canvas_artifact_pane").assertIsDisplayed()
        composeRule.onNodeWithText("briefing.md").assertIsDisplayed()
        composeRule.onNodeWithText("# Morning briefing").assertIsDisplayed()
    }

    @Test
    fun planSteps_areIndividuallyAddressable() {
        launchCanvas(widthDp = 840, artifact = CanvasArtifact.PlanArtifact(fakePlan()))

        composeRule.onAllNodesWithTag("canvas_plan_step").assertCountEquals(3)
    }
}
