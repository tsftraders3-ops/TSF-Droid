package com.tsfdroid.ai.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tsfdroid.ai.accessibility.MarkKind
import com.tsfdroid.ai.accessibility.VisionScreenMark
import com.tsfdroid.ai.actions.base.Action
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.core.agent.DeviceStateProvider
import com.tsfdroid.ai.core.agent.VisionDrivingLoop
import com.tsfdroid.ai.core.agent.VisionEngine
import com.tsfdroid.ai.core.agent.VisionGoalVerifier
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Milestone 1 (EGM v2.0.0 arc) — the DRIVE_SCREEN_GOAL action (spec File 13,
 * Checkpoint 3): param contracts, baseline pre-check, consent ask-loop
 * (one-shot, label-bound, TTL, gesture-budget), honest result mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveScreenGoalActionTest {

    private lateinit var loop: VisionDrivingLoop
    private lateinit var goalVerifier: VisionGoalVerifier
    private lateinit var driveAction: AdvancedControlActions.DriveScreenGoalAction
    private lateinit var context: Context
    private val taps = java.util.concurrent.atomic.AtomicInteger(0)

    /** Scripted SoM locator: answers served in order, last one repeats. */
    private class ScriptedProvider(private val answers: List<String>) : LLMProvider {
        private var served = 0
        override val name: String = "ScriptedActionProvider"
        override val availableModels: List<String> = listOf("fake")
        override suspend fun complete(request: LLMRequest): LLMResponse {
            val content = answers[served.coerceAtMost(answers.size - 1)]
            served++
            return LLMResponse(content = content, tokensUsed = 10, model = "fake", provider = name, latencyMs = 1)
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("{}")
        override suspend fun isAvailable(): Boolean = true
    }

    private fun somMark(index: Int, label: String?) = VisionScreenMark(
        index = index,
        kind = MarkKind.BUTTON,
        label = label,
        left = 120, top = 450, right = 340, bottom = 520
    )

    private val node1 = """{"target_found": true, "node": 1, "confidence": 0.9}"""
    private val node2 = """{"target_found": true, "node": 2, "confidence": 0.9}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val factory = neverFactory()
        val visionEngine = VisionEngine(factory)
        loop = VisionDrivingLoop(visionEngine, factory)
        goalVerifier = VisionGoalVerifier(visionEngine, factory)
        val actions = AdvancedControlActions(
            visionDrivingLoop = loop,
            visionGoalVerifier = goalVerifier
        )
        driveAction = actions.getActions()
            .first { it.name == "DRIVE_SCREEN_GOAL" } as AdvancedControlActions.DriveScreenGoalAction

        // Hermetic loop seams: two benign marks by default, taps counted.
        loop.somMarks = { listOf(somMark(1, "Search"), somMark(2, "Wi-Fi")) }
        loop.screenDimensions = { 1080 to 2400 }
        loop.gestureDispatch = { _, _ -> taps.incrementAndGet(); true }
        loop.waitForUiSettle = { }
        loop.providerAccess = { ScriptedProvider(listOf(node1)) }

        // Verifier seams: no baseline text, never vision-verified (the
        // capture has no service under Robolectric → fail-closed false).
        goalVerifier.screenTextProvider = { null }
        goalVerifier.windowContextProvider = { "" }
    }

    private fun params(
        goal: String = "open the network settings",
        expected: String = "Network & internet",
        consent: String? = null
    ): Map<String, String> =
        buildMap {
            put("goal", goal)
            put("expectedScreenText", expected)
            consent?.let { put("consent", it) }
        }

    // ------------------------------------------------------------------
    // Param contracts + baseline pre-check
    // ------------------------------------------------------------------

    @Test
    fun `missingGoal failsWithParamError`() = runTest {
        val result = driveAction.execute(mapOf("expectedScreenText" to "X"), context)
        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("goal parameter is missing"))
        assertEquals(0, taps.get())
    }

    @Test
    fun `missingExpectedText failsWithParamError`() = runTest {
        val result = driveAction.execute(mapOf("goal" to "open settings"), context)
        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("expectedScreenText parameter is required"))
        assertEquals(0, taps.get())
    }

    @Test
    fun `expectedAlreadyOnScreen returnsSuccessZeroSteps`() = runTest {
        goalVerifier.screenTextProvider = { "Home\nNetwork & internet\nBattery" }

        val result = driveAction.execute(params(), context)

        assertTrue(result is ActionResult.Success)
        assertTrue(result.data!!.contains("already met"))
        assertTrue(result.data!!.contains("0 steps"))
        assertEquals("no gesture for an already-met goal", 0, taps.get())
    }

    @Test
    fun `goalReached returnsSuccessMessage`() = runTest {
        // Baseline (call 1) lacks the text; the post-settle tree (call 2+) has it.
        var calls = 0
        goalVerifier.screenTextProvider = {
            calls++
            if (calls == 1) "Home screen" else "Settings\nNetwork & internet"
        }

        val result = driveAction.execute(params(), context)

        assertTrue("expected Success but was $result", result is ActionResult.Success)
        assertTrue(result.data!!.contains("Goal reached after 1 step"))
        assertEquals(1, taps.get())
    }

    // ------------------------------------------------------------------
    // The consent ask-loop
    // ------------------------------------------------------------------

    @Test
    fun `consentRequiredLabeled returnsNeedsInputWithChipsAndConsentParam`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }

        val result = driveAction.execute(params(goal = "remove my account"), context)

        assertTrue("expected NeedsInput but was $result", result is ActionResult.NeedsInput)
        val needs = result as ActionResult.NeedsInput
        assertEquals("consent", needs.metadata["param"])
        assertEquals("vision_drive_consent", needs.metadata["type"])
        assertTrue(needs.options.contains("Yes, tap it"))
        assertTrue(needs.options.contains("No, stop driving"))
        assertTrue("the question names the label", needs.question.contains("Delete account"))
        assertEquals(0, taps.get())
    }

    @Test
    fun `consentApprovalReRun tapsApprovedLabelOnce`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }
        loop.providerAccess = { ScriptedProvider(listOf(node1)) }

        val run1 = driveAction.execute(params(goal = "remove my account"), context)
        assertTrue(run1 is ActionResult.NeedsInput)

        val run2 = driveAction.execute(params(goal = "remove my account", consent = "Yes, tap it"), context)

        // The gate authorized exactly one tap of the approved label; the
        // second critical tap in the same run re-halts.
        assertTrue("run 2 must re-halt after the one-shot tap, was $run2", run2 is ActionResult.NeedsInput)
        assertEquals("exactly one authorized tap", 1, taps.get())
    }

    @Test
    fun `consentApprovalReRun carriesGestureBudget`() = runTest {
        // Marks: node 1 benign, node 2 critical. Script: two benign taps,
        // then the critical halt (2 steps spent); the approval re-run then
        // taps the approved critical label once and two more benign steps —
        // the budget caps run 2 at exactly 3 gestures (5 total).
        loop.somMarks = { listOf(somMark(1, "Search"), somMark(2, "Delete account")) }
        // A STABLE provider instance — providerAccess() is invoked once per
        // locate step and the script must advance across calls.
        val scripted = ScriptedProvider(listOf(node1, node1, node2, node2, node1, node1))
        loop.providerAccess = { scripted }

        val run1 = driveAction.execute(params(goal = "remove my account"), context)
        assertTrue(run1 is ActionResult.NeedsInput)
        assertEquals(2, taps.get())

        val run2 = driveAction.execute(params(goal = "remove my account", consent = "Yes, tap it"), context)

        assertTrue("expected MaxStepsExceeded mapping (Failure) but was $run2", run2 is ActionResult.Failure)
        assertTrue(run2.error!!.contains("Drove 3 step(s)"))
        assertEquals("total gestures across the ask-loop never exceed 5", 5, taps.get())
    }

    @Test
    fun `consentDecline failsHonestlyWithoutReDrive`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }

        val run1 = driveAction.execute(params(goal = "remove my account"), context)
        assertTrue(run1 is ActionResult.NeedsInput)

        val run2 = driveAction.execute(params(goal = "remove my account", consent = "No, stop driving"), context)

        assertTrue(run2 is ActionResult.Failure)
        assertTrue(run2.error!!.contains("declined"))
        assertTrue(run2.error!!.contains("Delete account"))
        assertEquals("a decline never re-drives", 0, taps.get())

        // The pending record is cleared: a later affirmative answer has
        // nothing to honor and the drive re-asks.
        val run3 = driveAction.execute(params(goal = "remove my account", consent = "Yes, tap it"), context)
        assertTrue("cleared pending must not authorize", run3 is ActionResult.NeedsInput)
        assertEquals(0, taps.get())
    }

    @Test
    fun `notAllow isNotApproval declines`() = runTest {
        val result = driveAction.execute(params(consent = "not allow"), context)
        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("declined"))
    }

    @Test
    fun `neverApprove isNotApproval declines`() = runTest {
        val result = driveAction.execute(params(consent = "never approve this"), context)
        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("declined"))
    }

    @Test
    fun `freeTextNonAffirmative declines`() = runTest {
        // Conservative by design: "sure" is not an affirmative token.
        val result = driveAction.execute(params(consent = "sure"), context)
        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("declined"))
    }

    @Test
    fun `staleConsentDifferentGoal doesNotApprove`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }

        val run1 = driveAction.execute(params(goal = "goal A"), context)
        assertTrue(run1 is ActionResult.NeedsInput)

        val run2 = driveAction.execute(params(goal = "goal B", consent = "Yes, tap it"), context)

        assertTrue("a pending record for goal A must not authorize goal B", run2 is ActionResult.NeedsInput)
        assertEquals(0, taps.get())
    }

    @Test
    fun `staleConsentExpiredTtl doesNotApprove`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }

        val run1 = driveAction.execute(params(goal = "remove my account"), context)
        assertTrue(run1 is ActionResult.NeedsInput)

        // Fast-forward the fake clock past CONSENT_TTL_MS.
        val realNow = System.currentTimeMillis()
        driveAction.nowProvider = { realNow + AdvancedControlActions.DriveScreenGoalAction.CONSENT_TTL_MS + 1 }

        val run2 = driveAction.execute(params(goal = "remove my account", consent = "Yes, tap it"), context)

        assertTrue("an expired approval must not authorize", run2 is ActionResult.NeedsInput)
        assertEquals(0, taps.get())
    }

    // ------------------------------------------------------------------
    // Honest result mapping
    // ------------------------------------------------------------------

    @Test
    fun `unlabeledConsentHalt returnsUserActionRequired`() = runTest {
        loop.somMarks = { listOf(somMark(1, null)) }
        loop.providerAccess = { ScriptedProvider(listOf(node1)) }

        val result = driveAction.execute(params(goal = "continue the setup"), context)

        assertTrue("expected UserActionRequired but was $result", result is ActionResult.UserActionRequired)
        assertTrue((result as ActionResult.UserActionRequired).message.contains("stopped the screen drive"))
        assertEquals(0, taps.get())
    }

    @Test
    fun `haltedResult mapsToFailureWithReason`() = runTest {
        loop.somMarks = { emptyList() }
        loop.screenCapture = { null } // no accessibility service → no capture

        val result = driveAction.execute(params(), context)

        assertTrue(result is ActionResult.Failure)
        assertTrue(result.error!!.contains("Driving halted"))
        assertTrue(result.error!!.contains("refusing to drive blind"))
    }

    // ------------------------------------------------------------------
    // isApproval token logic (direct companion cases)
    // ------------------------------------------------------------------

    @Test
    fun `isApproval exactChip isApproval`() {
        assertTrue(AdvancedControlActions.DriveScreenGoalAction.isApproval("Yes, tap it"))
        assertTrue(AdvancedControlActions.DriveScreenGoalAction.isApproval("tap it"))
    }

    @Test
    fun `isApproval anchoredYes isApproval`() {
        assertTrue(AdvancedControlActions.DriveScreenGoalAction.isApproval("yes"))
        assertTrue(AdvancedControlActions.DriveScreenGoalAction.isApproval("approve the tap"))
        assertTrue(AdvancedControlActions.DriveScreenGoalAction.isApproval("allow it"))
    }

    @Test
    fun `isApproval negationTokens areNotApproval`() {
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval("not allow"))
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval("never approve this"))
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval("no, stop"))
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval("don't"))
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval("sure"))
        assertFalse(AdvancedControlActions.DriveScreenGoalAction.isApproval(""))
    }

    // ------------------------------------------------------------------
    // test factory (never resolved: seam-driven tests)
    // ------------------------------------------------------------------

    private fun neverFactory(): com.tsfdroid.ai.core.llm.LLMProviderFactory {
        fun <T : Any> unused(): javax.inject.Provider<T> =
            javax.inject.Provider { throw AssertionError("test provider must never be resolved") }
        fun <T : Any> unusedLazy(): dagger.Lazy<T> =
            dagger.Lazy { throw AssertionError("test lazy must never be resolved") }
        val unusedSettings = com.tsfdroid.ai.data.repository.SettingsRepository(
            dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = kotlinx.coroutines.flow.flowOf(
                    androidx.datastore.preferences.core.emptyPreferences()
                )
                override suspend fun updateData(
                    transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
                ) = throw UnsupportedOperationException()
            },
            providerCredentialStore = com.tsfdroid.ai.core.agent.UnusedCredentialStore,
            runStartupMigration = false
        )
        return com.tsfdroid.ai.core.llm.LLMProviderFactory(
            claudeProvider = unused(),
            openAIProvider = unused(),
            geminiProvider = unused(),
            mistralProvider = unused(),
            groqProvider = unused(),
            ollamaProvider = unused(),
            openRouterProvider = unused(),
            togetherAIProvider = unused(),
            cohereProvider = unused(),
            deepSeekProvider = unused(),
            openCodeZenProvider = unused(),
            copilotProvider = unused(),
            customOpenAIProvider = unused(),
            gemmaProvider = unused(),
            liteRTLMProvider = unused(),
            hybridOnDeviceProvider = unused(),
            settingsRepository = unusedSettings,
            onDeviceLatencyTracker = com.tsfdroid.ai.core.llm.OnDeviceLatencyTracker(unusedSettings),
            actionDispatcher = unusedLazy(),
            intentClassifier = unusedLazy(),
            deviceStateProvider = DeviceStateProvider(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            )
        )
    }
}
