package com.tsfdroid.ai.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tsfdroid.ai.actions.AdvancedControlActions
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.agent.VisionDrivingLoop
import com.tsfdroid.ai.core.agent.VisionEngine
import com.tsfdroid.ai.core.agent.VisionGoalVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone 1 (EGM v2.0.0 arc) — DRIVE_SCREEN_GOAL hermetic gate (spec
 * File 16).
 *
 * On the hermetic emulator NO accessibility service is bound (the service is
 * enabled by the user in system settings; a fresh emulator never has it).
 * The honest behavior for the driving action in that state: no marks, no
 * capture, no crash — a Failure whose message says the drive halted.
 * The safety property under test is FAIL-CLOSED, not "somehow succeeds".
 */
@RunWith(AndroidJUnit4::class)
class DriveScreenGoalHermeticTest {

    @Test
    fun drivesNowhereWithoutAccessibility_honestHalt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // No test fakes: the REAL production wiring, real seams, no service.
        val factory = HermeticTestProviderFactory.create(context)
        val visionEngine = VisionEngine(factory)
        val actions = AdvancedControlActions(
            visionDrivingLoop = VisionDrivingLoop(visionEngine, factory),
            visionGoalVerifier = VisionGoalVerifier(visionEngine, factory)
        )
        val drive = actions.getActions().first { it.name == "DRIVE_SCREEN_GOAL" }

        val result = drive.execute(
            mapOf(
                "goal" to "open the Wi-Fi settings screen",
                "expectedScreenText" to "Network & internet"
            ),
            context
        )

        // Fail-closed: no accessibility service → no marks (empty tree) → no
        // capture → Halted("screen capture unavailable — refusing to drive
        // blind") mapped to an honest Failure naming the halt. Never a
        // crash, never a guessed success, never a gesture.
        assertTrue("expected a Failure but was $result", result is ActionResult.Failure)
        assertTrue(
            "the failure must say the drive halted, was: ${result.error}",
            result.error!!.contains("halt", ignoreCase = true)
        )
    }
}
