package com.tsfdroid.ai.core.agent

import android.accessibilityservice.GestureDescription
import androidx.test.core.app.ApplicationProvider
import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-02 remediation (audit fc9ea97): vision-guided computer use must actually
 * reach the gesture dispatcher. A mocked vision model returns a structured
 * JSON target (fractions of the screen); the engine maps the fractions onto
 * the real screen dimensions and dispatches the tap through the same
 * clickCoordinates() path the CLICK_COORDINATES action uses — and the
 * dispatched GestureDescription must contain exactly those pixel
 * coordinates.
 *
 * The seams overridden here (providerAccess / screenCapturer /
 * screenDimensions / tapExecutor) all default to the real production paths;
 * only the service lookup is redirected to a Robolectric-constructed
 * service because the singleton is only set by the real accessibility
 * binding, which no JVM test can provide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionTargetTapTest {

    private lateinit var accessibilityService: OpenDroidAccessibilityService
    private lateinit var visionEngine: VisionEngine
    private var providerJson: String? = null
    private var llmCalls = 0

    private val screenW = 1080
    private val screenH = 1920

    @Before
    fun setUp() {
        accessibilityService = OpenDroidAccessibilityService()
        // The shadow refuses gestures until explicitly enabled; the real
        // service dispatches whenever the system accepts the gesture.
        shadowOf(accessibilityService).setCanDispatchGestures(true)
        providerJson = null
        llmCalls = 0
        visionEngine = VisionEngine(neverFactory())        // Production defaults are overridden ONLY where the real path requires a
        // bound accessibility service or a live LLM — neither exists in JVM tests.
        visionEngine.providerAccess = {
            llmCalls++
            FakeVisionProvider(providerJson)
        }
        // A real WHITE image: an undecodable or black fake would trip the
        // FLAG_SECURE black-frame guard (under Robolectric's native graphics
        // garbage bytes can decode to an all-black bitmap) and the engine would
        // rightly refuse to tap. A genuine non-black capture exercises the
        // vision path itself.
        visionEngine.screenCapturer = {
            val bitmap = android.graphics.Bitmap.createBitmap(
                64, 64, android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val bytes = java.io.ByteArrayOutputStream().also {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        }
        visionEngine.screenDimensions = { screenW to screenH }
        visionEngine.tapExecutor = { x, y ->
            // The REAL gesture path: same method CLICK_COORDINATES dispatches
            // through (GenericAppAutomator.clickCoordinates -> service.clickCoordinates).
            accessibilityService.clickCoordinates(x, y)
        }
    }

    /** The providerAccess seam intercepts every LLM access, so the factory's
     * own providers are never resolved — SAM lambdas throw if that guarantee
     * is ever broken. */
    private fun neverFactory(): LLMProviderFactory {
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
        return LLMProviderFactory(
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

    @Test
    fun `high-confidence vision target taps the exact mapped screen coordinates`() = runBlocking {
        // Fractions of a 1080x1920 screen: (0.5, 0.75) -> (540, 1440).
        providerJson = """{"target_found": true, "x": 0.5, "y": 0.75, "confidence": 0.93}"""

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertTrue("tap must be dispatched", tapped != null && tapped.tapped)
        assertEquals("LLM consulted exactly once", 1, llmCalls)

        val gesture: GestureDescription = shadowOf(accessibilityService)
            .gesturesDispatched.single().description()
        val points = shadowOf(gesture.getStroke(0).path).points
        assertEquals("one moveTo stroke point", 1, points.size)
        assertEquals(540f, points[0].x, 0.01f)
        assertEquals(1440f, points[0].y, 0.01f)
    }

    @Test
    fun `low-confidence vision target never dispatches a gesture`() = runBlocking {
        providerJson = """{"target_found": true, "x": 0.5, "y": 0.75, "confidence": 0.7}"""

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertTrue("low confidence must be refused", tapped != null && !tapped.tapped)
        assertEquals(0, shadowOf(accessibilityService).gesturesDispatched.size)
    }

    @Test
    fun `target not found never dispatches a gesture`() = runBlocking {
        providerJson = """{"target_found": false, "x": 0, "y": 0, "confidence": 0.2}"""

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertTrue("missing target must be refused", tapped != null && !tapped.tapped)
        assertEquals(0, shadowOf(accessibilityService).gesturesDispatched.size)
    }

    @Test
    fun `unparsable vision response never dispatches a gesture`() = runBlocking {
        providerJson = "I looked at the screen and the button is at the bottom right."

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertTrue("prose must never become a tap", tapped != null && !tapped.tapped)
        assertEquals(0, shadowOf(accessibilityService).gesturesDispatched.size)
    }

    @Test
    fun `unreadable screen never consults the vision model`() = runBlocking {
        visionEngine.screenCapturer = { null }

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertNotNull(tapped)
        assertFalse(tapped.tapped)
        assertEquals("no LLM call on unreadable screens", 0, llmCalls)
        assertEquals(0, shadowOf(accessibilityService).gesturesDispatched.size)
    }

    @Test
    fun `absolute pixel coordinates from the model are mapped back to fractions`() = runBlocking {
        // Some models return pixels despite the prompt; a 1080x1920 screen with
        // (540,960) is the same target as (0.5, 0.5).
        providerJson = """{"target_found": true, "x": 540, "y": 960, "confidence": 0.9}"""

        val tapped = visionEngine.tapLocatedTarget("the Send button")

        assertTrue(tapped.tapped)
        val gesture: GestureDescription = shadowOf(accessibilityService)
            .gesturesDispatched.single().description()
        val points = shadowOf(gesture.getStroke(0).path).points
        assertEquals(540f, points[0].x, 0.01f)
        assertEquals(960f, points[0].y, 0.01f)
    }
}

/** Hermetic vision provider: returns whatever JSON the test pinned. */
class FakeVisionProvider(private val json: String?) : LLMProvider {
    override val name: String = "FakeVisionProvider"
    override val availableModels: List<String> = listOf("fake-vision")

    override suspend fun complete(request: LLMRequest): LLMResponse = LLMResponse(
        content = json ?: """{"target_found": false, "x": 0, "y": 0, "confidence": 0.0}""",
        tokensUsed = 20,
        model = "fake-vision",
        provider = "FakeVisionProvider",
        latencyMs = 3
    )

    override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("{}")

    override suspend fun isAvailable(): Boolean = true
}

/** Minimal unused credential store for factory construction. */
object UnusedCredentialStore : com.tsfdroid.ai.core.security.ProviderCredentialStore {
    override val recoveryState =
        kotlinx.coroutines.flow.MutableStateFlow(com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState.Ready)
            as kotlinx.coroutines.flow.StateFlow<com.tsfdroid.ai.core.security.ProviderCredentialRecoveryState>

    override fun read(credential: com.tsfdroid.ai.core.security.ProviderCredentialId) =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
    override fun readProviderApiKeys() =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
    override fun write(credential: com.tsfdroid.ai.core.security.ProviderCredentialId, value: String) =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
    override fun remove(credential: com.tsfdroid.ai.core.security.ProviderCredentialId) =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
    override fun migrateLegacyCredentials() =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
    override fun resetForReentry() =
        com.tsfdroid.ai.core.security.CredentialStoreResult.StorageUnavailable
}
