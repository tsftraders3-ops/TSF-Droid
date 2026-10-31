package com.tsfdroid.ai.core.agent

import android.graphics.Bitmap
import com.tsfdroid.ai.accessibility.MarkKind
import com.tsfdroid.ai.accessibility.VisionScreenMark
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * God-Tier Phase 2, ticket 5.5 — the Continuous Vision Driving Loop.
 *
 * The Bars (from the ticket, proven hermetically under virtual time):
 *  1. TERMINATION — the loop executes exactly MAX_STEPS (5) driven steps
 *     when the goal is never reached, then reports MaxStepsExceeded — never
 *     a 6th capture, never a 6th gesture.
 *  2. CONFIDENCE HALT — the loop halts immediately (no gesture dispatched)
 *     the moment a step's located confidence drops below 0.85.
 *  3. CONSENT REQUIRED — a target labeled "Delete Account" (or any
 *     policy-critical UI pattern: Delete / Pay / Send / ...) halts the loop
 *     BEFORE the gesture with a ConsentRequired result.
 *  4. PRIVACY — every captured screenshot Bitmap is recycled by the time
 *     the loop returns; frames live only in memory (no disk writes exist in
 *     the loop's code at all).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionDrivingLoopTest {

    private lateinit var loop: VisionDrivingLoop

    /** Bitmaps handed out by the fake capture seam — checked for recycling. */
    private val issuedBitmaps = mutableListOf<Bitmap>()
    private val captureCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val tapCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Per-step script for the fake vision seam: what step N's locate returns. */
    internal var locateScript: MutableList<(step: Int) -> VisionDriveTarget> = mutableListOf()
    internal var settleDelayMs: Long = 0
    internal var goalVerifier: () -> Boolean = { false }

    @Before
    fun setUp() {
        loop = VisionDrivingLoop(
            visionEngine = VisionEngine(neverFactory()),
            llmProviderFactory = neverFactory()
        )
        loop.screenCapture = {
            captureCount.incrementAndGet()
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { issuedBitmaps.add(it) }
        }
        loop.visionLocate = { _, _ ->
            val step = tapCount.get() + 1 // the step about to execute
            val index = (step - 1).coerceAtMost(locateScript.size - 1).coerceAtLeast(0)
            locateScript[index].invoke(step)
        }
        loop.gestureDispatch = { _, _ ->
            tapCount.incrementAndGet()
            true
        }
        loop.waitForUiSettle = { delay(settleDelayMs) }
        loop.goalVerifier = { goalVerifier() }
        loop.screenDimensions = { 1080 to 2400 }
    }

    private fun target(
        found: Boolean = true,
        x: Double = 0.5,
        y: Double = 0.5,
        confidence: Float = 0.95f,
        label: String? = "Continue" // benign default; null is the fail-closed case
    ) = VisionDriveTarget(found = found, x = x, y = y, confidence = confidence, label = label)

    // ------------------------------------------------------------------
    // BAR 1: bounded termination at exactly MAX_STEPS
    // ------------------------------------------------------------------

    @Test
    fun `loop terminates at exactly maxSteps when the goal is never met`() = runTest {
        locateScript = MutableList(20) { { target() } } // always confident, never goal-met

        val result = loop.drive("Open the settings screen")

        assertTrue("expected MaxStepsExceeded but was $result", result is VisionDriveResult.MaxStepsExceeded)
        assertEquals("exactly MAX_STEPS gestures", VisionDrivingLoop.MAX_STEPS, tapCount.get())
        assertEquals("exactly MAX_STEPS captures — no sixth frame", VisionDrivingLoop.MAX_STEPS, captureCount.get())
        assertEquals(VisionDrivingLoop.MAX_STEPS, (result as VisionDriveResult.MaxStepsExceeded).steps.size)
    }

    @Test
    fun `loop stops as soon as the goal verifier passes`() = runTest {
        locateScript = MutableList(20) { { target() } }
        var goalReached = false
        goalVerifier = {
            goalReached = tapCount.get() >= 3
            goalReached
        }

        val result = loop.drive("Open the settings screen")

        assertTrue("expected GoalReached but was $result", result is VisionDriveResult.GoalReached)
        assertEquals("the loop stops at the verified step", 3, tapCount.get())
    }

    // ------------------------------------------------------------------
    // BAR 2: low-confidence halt — below 0.85, immediately, no gesture
    // ------------------------------------------------------------------

    @Test
    fun `loop halts immediately when confidence drops below the floor`() = runTest {
        locateScript = mutableListOf(
            { target(confidence = 0.95f) },   // step 1: healthy tap
            { target(confidence = 0.60f) }    // step 2: confidence collapsed
        )

        val result = loop.drive("Open the settings screen")

        assertTrue("expected LowConfidence but was $result", result is VisionDriveResult.LowConfidence)
        val low = result as VisionDriveResult.LowConfidence
        assertEquals("halted at step 2", 2, low.atStep)
        assertEquals(0.60f, low.confidence, 0.0001f)
        assertEquals("only the healthy step dispatched a gesture", 1, tapCount.get())
        assertEquals("one tapped step recorded", 1, low.steps.size)
    }

    @Test
    fun `a not-found target halts the loop without a blind gesture`() = runTest {
        locateScript = mutableListOf({ target(found = false, confidence = 0.1f) })

        val result = loop.drive("Open the settings screen")

        assertTrue("expected LowConfidence but was $result", result is VisionDriveResult.LowConfidence)
        assertEquals("never taps blind", 0, tapCount.get())
    }

    // ------------------------------------------------------------------
    // BAR 3: policy-critical target => ConsentRequired BEFORE the gesture
    // ------------------------------------------------------------------

    @Test
    fun `a target labeled Delete Account returns ConsentRequired without tapping`() = runTest {
        locateScript = mutableListOf({ target(confidence = 0.92f, label = "Delete Account") })

        val result = loop.drive("Remove my account")

        assertTrue("expected ConsentRequired but was $result", result is VisionDriveResult.ConsentRequired)
        val consent = result as VisionDriveResult.ConsentRequired
        assertEquals("Delete Account", consent.targetLabel)
        assertEquals("the halt precedes the gesture", 0, tapCount.get())
        assertTrue(
            "the reason names the consent boundary",
            consent.reason.contains("consent", ignoreCase = true) ||
                consent.reason.contains("critical", ignoreCase = true)
        )
    }

    @Test
    fun `money and send targets are also consent-gated`() = runTest {
        for (criticalLabel in listOf("Pay now", "Send money", "Confirm purchase", "Erase all data")) {
            tapCount.set(0)
            locateScript = mutableListOf({ target(confidence = 0.97f, label = criticalLabel) })

            val result = loop.drive("Proceed with the transaction")

            assertTrue(
                "$criticalLabel must halt with ConsentRequired but was $result",
                result is VisionDriveResult.ConsentRequired
            )
            assertEquals("no gesture for $criticalLabel", 0, tapCount.get())
        }
    }

    @Test
    fun `an unlabeled target fails closed — consent required, no tap`() = runTest {
        // The vision model returned found=true, confidence 0.95, but NO label:
        // the consent gate cannot verify what is under the finger, so the
        // loop must halt for consent instead of tapping blind (the same
        // reason CLICK_COORDINATES is policy-critical in AutoApprovalPolicy).
        locateScript = mutableListOf({ target(confidence = 0.95f, label = null) })

        val result = loop.drive("Continue with the setup")

        assertTrue("expected ConsentRequired but was $result", result is VisionDriveResult.ConsentRequired)
        val consent = result as VisionDriveResult.ConsentRequired
        assertTrue(consent.targetLabel.contains("unlabeled"))
        assertEquals("the halt precedes the gesture", 0, tapCount.get())
    }

    @Test
    fun `benign labels drive on normally`() = runTest {
        locateScript = MutableList(20) { { target(confidence = 0.95f, label = "Settings") } }
        goalVerifier = { tapCount.get() >= 2 }

        val result = loop.drive("Open the settings screen")

        assertTrue("benign label must not halt the loop, was $result", result is VisionDriveResult.GoalReached)
    }

    // ------------------------------------------------------------------
    // BAR 4: privacy — every frame recycled, in-memory only
    // ------------------------------------------------------------------

    @Test
    fun `every captured bitmap is recycled when the loop returns`() = runTest {
        locateScript = MutableList(20) { { target(confidence = 0.95f) } }

        val result = loop.drive("Open the settings screen")

        assertTrue(result is VisionDriveResult.MaxStepsExceeded)
        assertEquals(VisionDrivingLoop.MAX_STEPS, issuedBitmaps.size)
        assertTrue(
            "all frames must be recycled by loop return",
            issuedBitmaps.all { it.isRecycled }
        )
    }

    @Test
    fun `a halted loop still recycles every frame it captured`() = runTest {
        locateScript = mutableListOf({ target(confidence = 0.5f) })

        val result = loop.drive("Open the settings screen")

        assertTrue(result is VisionDriveResult.LowConfidence)
        assertEquals(1, issuedBitmaps.size)
        assertTrue(issuedBitmaps.first().isRecycled)
    }

    // ------------------------------------------------------------------
    // Total timeout bound
    // ------------------------------------------------------------------

    @Test
    fun `the loop dies at its 30s total timeout even with slow screens`() = runTest {
        settleDelayMs = 11_000 // 2 full steps = 22s; the third settle crosses 30s
        locateScript = MutableList(20) { { target(confidence = 0.95f) } }

        val result = loop.drive("Open the settings screen")

        assertTrue("expected Halted(timeout) but was $result", result is VisionDriveResult.Halted)
        val halted = result as VisionDriveResult.Halted
        assertTrue(
            "halt reason must name the timeout: ${halted.reason}",
            halted.reason.contains("timeout", ignoreCase = true)
        )
        assertTrue("bounded progress before the kill", halted.steps.size in 2..3)
        assertTrue(
            "even a timeout-killed loop recycles its frames",
            issuedBitmaps.all { it.isRecycled }
        )
    }

    // ------------------------------------------------------------------
    // Patterns list sanity (the safety vocabulary itself)
    // ------------------------------------------------------------------

    @Test
    fun `the policy-critical pattern vocabulary covers the ticket's examples`() {
        for (label in listOf("Delete Account", "Pay", "Send", "delete", "PAY NOW")) {
            assertTrue(
                "'$label' must be classified policy-critical",
                VisionDrivingLoop.isPolicyCriticalTarget(label)
            )
        }
        assertFalse("'Settings' is not critical", VisionDrivingLoop.isPolicyCriticalTarget("Settings"))
        assertFalse("null label is not critical", VisionDrivingLoop.isPolicyCriticalTarget(null))
    }

    // ------------------------------------------------------------------
    // Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark fast path (spec File 12)
    // ------------------------------------------------------------------

    private fun somMark(
        index: Int,
        label: String?,
        left: Int = 120,
        top: Int = 450,
        right: Int = 340,
        bottom: Int = 520
    ) = VisionScreenMark(
        index = index,
        kind = MarkKind.BUTTON,
        label = label,
        left = left,
        top = top,
        right = right,
        bottom = bottom
    )

    /** Two benign marks; mark 2's center on a 1080x2400 screen is (550, 875). */
    private fun twoMarks() = listOf(
        somMark(1, "Search"),
        somMark(2, "Wi-Fi", left = 400, top = 800, right = 700, bottom = 950)
    )

    /** Hermetic SoM locator: scripted answers, last one repeats when exhausted. */
    private class SomProvider(private val json: String, private val thenJson: String? = null) : LLMProvider {
        private var served = 0
        override val name: String = "SomProvider"
        override val availableModels: List<String> = listOf("fake-som")
        override suspend fun complete(request: LLMRequest): LLMResponse {
            val content = if (served == 0) json else (thenJson ?: json)
            served++
            return LLMResponse(content = content, tokensUsed = 10, model = "fake-som", provider = name, latencyMs = 1)
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("{}")
        override suspend fun isAvailable(): Boolean = true
    }

    private fun recordingTaps(): MutableList<Pair<Float, Float>> {
        val taps = mutableListOf<Pair<Float, Float>>()
        loop.gestureDispatch = { x, y ->
            taps += x to y
            tapCount.incrementAndGet()
            true
        }
        return taps
    }

    @Test
    fun `som marksPresent skipsScreenCapture`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 2, "confidence": 0.9}""")
        }
        val taps = recordingTaps()

        val result = loop.drive("open the Wi-Fi settings", verifier = { true })

        assertTrue("expected GoalReached but was $result", result is VisionDriveResult.GoalReached)
        assertEquals("zero screenshot captures on the marks path", 0, captureCount.get())
        assertEquals(1, taps.size)
        assertEquals("gesture dispatched at mark 2's center", 550f, taps[0].first, 0.01f)
        assertEquals(875f, taps[0].second, 0.01f)
        assertEquals(
            "the step carries the mark's own label",
            "Wi-Fi",
            (result as VisionDriveResult.GoalReached).steps[0].label
        )
    }

    @Test
    fun `som nodeOutOfRange isNotFound`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 7, "confidence": 0.9}""")
        }

        val result = loop.drive("open the Wi-Fi settings")

        assertTrue("expected LowConfidence but was $result", result is VisionDriveResult.LowConfidence)
        assertEquals("an invented node number never dispatches", 0, tapCount.get())
    }

    @Test
    fun `som xyAnswerInMarksMode isRefused`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "x": 0.3, "y": 0.4, "confidence": 0.9}""")
        }

        val result = loop.drive("open the Wi-Fi settings")

        assertTrue("x/y is refused in marks mode — the model never saw the screen", result is VisionDriveResult.LowConfidence)
        assertEquals(0, tapCount.get())
    }

    @Test
    fun `som nodeAnswerWithoutDims isNotFound`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.screenDimensions = { null }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 2, "confidence": 0.9}""")
        }

        val result = loop.drive("open the Wi-Fi settings")

        assertTrue(result is VisionDriveResult.LowConfidence)
        assertEquals(0, tapCount.get())
    }

    @Test
    fun `som criticalLabelWithoutConsent haltsConsentRequired`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 1, "confidence": 0.9}""")
        }

        val result = loop.drive("remove my account")

        assertTrue("expected ConsentRequired but was $result", result is VisionDriveResult.ConsentRequired)
        assertEquals("Delete account", (result as VisionDriveResult.ConsentRequired).targetLabel)
        assertEquals("the halt precedes the gesture", 0, tapCount.get())
    }

    @Test
    fun `som criticalLabelWithConsentGate dispatchesOnce`() = runTest {
        loop.somMarks = {
            listOf(
                somMark(1, "Delete account"),
                somMark(2, "Delete account", left = 400, top = 800, right = 700, bottom = 950)
            )
        }
        val scripted = SomProvider(
            """{"target_found": true, "node": 1, "confidence": 0.9}""",
            thenJson = """{"target_found": true, "node": 2, "confidence": 0.9}"""
        )
        loop.providerAccess = { scripted }
        recordingTaps()
        var armed = true
        val oneShotGate: suspend (String) -> Boolean = { label ->
            if (label == "Delete account" && armed) {
                armed = false
                true
            } else {
                false
            }
        }

        val result = loop.drive("remove my account", consentGate = oneShotGate)

        assertTrue(
            "a second critical tap in the same run must re-halt, was $result",
            result is VisionDriveResult.ConsentRequired
        )
        assertEquals("exactly one authorized tap", 1, tapCount.get())
        assertEquals(1, (result as VisionDriveResult.ConsentRequired).steps.size)
    }

    @Test
    fun `som criticalLabelConsentGateWrongLabel stillHalts`() = runTest {
        loop.somMarks = { listOf(somMark(1, "Delete account")) }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 1, "confidence": 0.9}""")
        }
        val wrongLabelGate: suspend (String) -> Boolean = { it == "Pay" }

        val result = loop.drive("remove my account", consentGate = wrongLabelGate)

        assertTrue(result is VisionDriveResult.ConsentRequired)
        assertEquals("a gate armed for another label never authorizes", 0, tapCount.get())
    }

    @Test
    fun `som unlabeledMark alwaysConsentRequired evenWithGate`() = runTest {
        loop.somMarks = { listOf(somMark(1, null)) }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 1, "confidence": 0.95}""")
        }
        val approveAnything: suspend (String) -> Boolean = { true }

        val result = loop.drive("continue the setup", consentGate = approveAnything)

        assertTrue("an unlabeled target cannot be pre-approved", result is VisionDriveResult.ConsentRequired)
        assertTrue((result as VisionDriveResult.ConsentRequired).targetLabel.contains("unlabeled"))
        assertEquals(0, tapCount.get())
    }

    @Test
    fun `som emptyMarks fallsBackToBitmapCapture`() = runTest {
        loop.somMarks = { emptyList() }
        locateScript = MutableList(1) { { target(x = 0.3, y = 0.4) } }
        val taps = recordingTaps()

        val result = loop.drive("open the settings screen", verifier = { true })

        assertTrue("expected GoalReached but was $result", result is VisionDriveResult.GoalReached)
        assertEquals("empty marks keep the bitmap path", 1, captureCount.get())
        assertEquals(1, taps.size)
        assertEquals(0.3f * 1080, taps[0].first, 0.01f)
        assertEquals(0.4f * 2400, taps[0].second, 0.01f)
    }

    @Test
    fun `drive verifierParam goalReachedAfterFirstStep`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 2, "confidence": 0.9}""")
        }
        recordingTaps()

        val result = loop.drive("open the Wi-Fi settings", verifier = { true })

        assertTrue(result is VisionDriveResult.GoalReached)
        assertEquals("the verifier param stops the run at the first verified step", 1, (result as VisionDriveResult.GoalReached).steps.size)
        assertEquals(1, tapCount.get())
    }

    @Test
    fun `drive maxStepsParam boundsTheRun`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 1, "confidence": 0.9}""")
        }
        recordingTaps()

        val result = loop.drive("never satisfied goal", verifier = { false }, maxSteps = 2)

        assertTrue("expected MaxStepsExceeded but was $result", result is VisionDriveResult.MaxStepsExceeded)
        assertEquals("exactly 2 steps", 2, (result as VisionDriveResult.MaxStepsExceeded).steps.size)
        assertEquals(2, tapCount.get())
    }

    @Test
    fun `drive maxStepsParam clampedToEngineMax`() = runTest {
        loop.somMarks = { twoMarks() }
        loop.providerAccess = {
            SomProvider("""{"target_found": true, "node": 1, "confidence": 0.9}""")
        }
        recordingTaps()

        val result = loop.drive("never satisfied goal", verifier = { false }, maxSteps = 99)

        assertTrue(result is VisionDriveResult.MaxStepsExceeded)
        assertEquals(
            "maxSteps above the engine max behaves as MAX_STEPS",
            VisionDrivingLoop.MAX_STEPS,
            (result as VisionDriveResult.MaxStepsExceeded).steps.size
        )
        assertEquals(VisionDrivingLoop.MAX_STEPS, tapCount.get())
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
            providerCredentialStore = UnusedCredentialStore,
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
