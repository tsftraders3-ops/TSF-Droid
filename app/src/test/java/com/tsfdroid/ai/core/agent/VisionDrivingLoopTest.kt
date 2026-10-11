package com.tsfdroid.ai.core.agent

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
        label: String? = null
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
