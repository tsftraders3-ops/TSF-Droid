package com.tsfdroid.ai.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tsfdroid.ai.core.canvas.CanvasArtifact
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.ui.screens.CanvasLayout
import com.tsfdroid.ai.ui.theme.OpenDroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * God-Tier Phase 2, ticket 5.2 — LOCAL proof of the adaptive layout under a
 * SMALL window (Robolectric's default is ~320x470dp, well below the 720dp
 * breakpoint).
 *
 * This is the BLOCKER remediation test: the instrumentation CanvasUiTest
 * sizes its composition with requiredWidth (which OVERRIDES incoming
 * constraints). If that technique were wrong — if a plain preferred width
 * let the window coerce the box down — these tests would flip to the
 * compact layout and the wide-layout assertions here would fail. Running
 * under Robolectric means the proof is local and deterministic, not
 * CI-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanvasLayoutRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun smallPlan() = Plan(
        planId = "p", goal = "g", estimatedDuration = "1 min", estimatedSteps = 0, steps = emptyList()
    )

    private fun layout(widthDp: Int, artifact: CanvasArtifact?) {
        composeRule.setContent {
            OpenDroidTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .requiredWidth(widthDp.dp)
                            .requiredHeight(800.dp)
                    ) {
                        CanvasLayout(
                            artifact = artifact,
                            onBack = {},
                            onClearArtifact = {},
                            chatPane = { paneModifier ->
                                Text(text = "chat pane content", modifier = paneModifier)
                            }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `requiredWidth beats the small window — wide layout renders both panes`() {
        layout(widthDp = 840, artifact = null)

        composeRule.onNodeWithTag("canvas_root").assertExists()
        composeRule.onNodeWithTag("canvas_chat_pane").assertExists()
        // On the 840dp-wide composition the artifact pane is ALWAYS laid out —
        // if the Robolectric window (~320dp) had coerced the box, this tag
        // would be absent and the compact branch would render instead.
        composeRule.onNodeWithTag("canvas_artifact_pane").assertExists()
    }

    @Test
    fun `compact width hides the artifact pane until opened`() {
        layout(widthDp = 411, artifact = CanvasArtifact.PlanArtifact(smallPlan()))

        composeRule.onNodeWithTag("canvas_chat_pane").assertExists()
        composeRule.onNodeWithTag("canvas_artifact_pane").assertDoesNotExist()
    }
}
