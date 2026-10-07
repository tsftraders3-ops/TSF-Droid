package com.tsfdroid.ai.core.agent

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the agent cannot perceive the screen. Drives the Phase 2.5 blind-spot
 * handling: abort instead of guessing.
 */
enum class BlindSpot {
    /** MediaProjection returned an effectively all-black frame — FLAG_SECURE. */
    SECURE_FLAG_BLACK_FRAME,

    /** Both the screenshot and the accessibility node tree came back empty. */
    UNREADABLE_SCREEN
}

/** Screenshot plus an optional blind-spot classification of why it is null. */
data class ScreenCaptureResult(val base64: String?, val blindSpot: BlindSpot?)

/**
 * M-02 (audit fc9ea97): a vision-grounded tap target. x and y are FRACTIONS of
 * the screen dimensions (0.0..1.0, origin top-left) — the model sees an image,
 * not pixels. VisionEngine maps them onto absolute screen pixels at dispatch
 * time via the current display dimensions.
 */
data class VisionTarget(
    val targetFound: Boolean,
    val x: Double?,
    val y: Double?,
    val confidence: Float,
    val note: String? = null
)

/** Outcome of a vision-grounded tap attempt. */
data class VisionTapOutcome(
    val tapped: Boolean,
    val reason: String? = null,
    val target: VisionTarget? = null
)

/**
 * Vision engine that captures screenshots and analyzes them using a vision-capable LLM.
 * Uses the existing AccessibilityService's takeScreenshotAndEncode() for capture,
 * with a fallback to getScreenText() for text-only analysis.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {
    // Test seams (M-02 remediation): the defaults reproduce the production
    // paths exactly. Tests substitute hermetic implementations (fake JSON
    // provider, fixed capture, fixed screen dimensions, redirected service
    // lookup). Never set by production code.
    internal var providerAccess: suspend () -> LLMProvider =
        { llmProviderFactory.getActiveProvider() }
    internal var screenCapturer: suspend () -> String? = { captureScreenBase64() }
    internal var screenDimensions: () -> Pair<Int, Int>? = {
        val service = OpenDroidAccessibilityService.getInstance()
        service?.resources?.displayMetrics?.let { it.widthPixels to it.heightPixels }
    }
    internal var tapExecutor: suspend (Float, Float) -> Boolean = { x, y ->
        OpenDroidAccessibilityService.getInstance()?.clickCoordinates(x, y) ?: false
    }

    companion object {
        private const val TAG = "VisionEngine"

        /** M-02: taps below this confidence floor are never dispatched. */
        const val TAP_CONFIDENCE_FLOOR = 0.85f

        /**
         * Parses the vision model's locator answer. The prompt demands a single
         * JSON object; this parser tolerates markdown fences and surrounding
         * prose. Anything unparsable resolves to a not-found target — never a
         * guessed coordinate.
         */
        fun parseTargetResponse(raw: String): VisionTarget {
            if (raw.isBlank()) {
                return VisionTarget(false, null, null, 0f, "empty locator response")
            }
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) {
                return VisionTarget(false, null, null, 0f, "no JSON object in locator response")
            }
            return try {
                val contract = locatorJson.decodeFromString<TargetContract>(
                    raw.substring(start, end + 1)
                )
                VisionTarget(
                    targetFound = contract.targetFound,
                    x = contract.x,
                    y = contract.y,
                    confidence = contract.confidence.toFloat()
                )
            } catch (e: Exception) {
                VisionTarget(false, null, null, 0f, "unparsable locator response")
            }
        }

        /** A target may only be tapped when found, confident, and in range. */
        fun isTapEligible(target: VisionTarget): Boolean =
            target.targetFound &&
                target.confidence > TAP_CONFIDENCE_FLOOR &&
                target.x != null && target.y != null &&
                target.x in 0.0..1.0 &&
                target.y in 0.0..1.0

        /** The locator wire contract the vision model must answer with. */
        @Serializable
        private data class TargetContract(
            @SerialName("target_found") val targetFound: Boolean = false,
            val x: Double? = null,
            val y: Double? = null,
            val confidence: Double = 0.0
        )

        private val locatorJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }


        /** Fraction of sampled pixels that must be near-black to call a frame protected. */
        private const val BLACK_FRAME_RATIO = 0.98

        /** Max per-channel intensity for a sampled pixel to count as black. */
        private const val BLACK_CHANNEL_MAX = 10

        private const val SAMPLE_GRID = 16

        /**
         * Resilient blind-spot check (Phase 2.5): apps holding FLAG_SECURE
         * (banking, DRM, private tabs) render as an all-black MediaProjection
         * frame, and Flutter/Unity custom-rendered apps can expose an empty
         * accessibility tree. A black frame decoded as a valid bitmap is the
         * FLAG_SECURE signature — detect it by sampling a sparse grid so the
         * agent reports reality instead of feeding a black rectangle to a
         * vision model and acting on hallucinated coordinates.
         */
        fun isEffectivelyBlackFrame(base64: String): Boolean {
            return runCatching {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runCatching false
                var sampled = 0
                var black = 0
                val stepX = (bitmap.width / SAMPLE_GRID).coerceAtLeast(1)
                val stepY = (bitmap.height / SAMPLE_GRID).coerceAtLeast(1)
                var y = 0
                while (y < bitmap.height) {
                    var x = 0
                    while (x < bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        sampled++
                        if (r <= BLACK_CHANNEL_MAX && g <= BLACK_CHANNEL_MAX && b <= BLACK_CHANNEL_MAX) black++
                        x += stepX
                    }
                    y += stepY
                }
                bitmap.recycle()
                sampled > 0 && black.toDouble() / sampled >= BLACK_FRAME_RATIO
            }.getOrDefault(false)
        }
    }

    /**
     * Capture with blind-spot classification. base64 is null whenever the
     * capture failed OR the frame is FLAG_SECURE-protected (reporting a black
     * rectangle to the vision model would only produce confident nonsense).
     */
    suspend fun captureScreen(): ScreenCaptureResult {
        val base64 = captureScreenBase64()
        if (base64 != null) {
            if (isEffectivelyBlackFrame(base64)) {
                Log.w(TAG, "Screenshot is an all-black frame — FLAG_SECURE content")
                return ScreenCaptureResult(null, BlindSpot.SECURE_FLAG_BLACK_FRAME)
            }
            return ScreenCaptureResult(base64, null)
        }
        return ScreenCaptureResult(null, null)
    }

    /**
     * Capture the current screen as a base64-encoded JPEG string.
     * Uses the AccessibilityService's existing screenshot capability.
     */
    suspend fun captureScreenBase64(): String? {
        val service = OpenDroidAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "Accessibility service instance is null")
            return null
        }

        return try {
            service.takeScreenshotAndEncode()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}")
            null
        }
    }

    /**
     * Extract visible text from the screen via accessibility nodes.
     * This works even when screenshot capture fails.
     */
    fun getScreenText(): String? {
        val service = OpenDroidAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "Accessibility service not available for text extraction")
            return null
        }
        return try {
            val text = service.getScreenText()
            if (text.isNotBlank()) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Screen text extraction failed: ${e.message}")
            null
        }
    }

    /**
     * M-02 (audit fc9ea97): vision-guided computer use. Locates the described
     * target on the current screen via the structured locator contract, gates
     * on the confidence floor, maps the fractional coordinates onto the real
     * display, and dispatches the tap through the same gesture path the
     * CLICK_COORDINATES action uses. Anything short of a high-confidence,
     * in-range target refuses to tap — the agent reports, it never guesses.
     */
    suspend fun tapLocatedTarget(targetDescription: String): VisionTapOutcome {
        val base64 = screenCapturer()
            ?: return VisionTapOutcome(
                tapped = false,
                reason = "The screen could not be captured, so I will not tap blind.",
                target = null
            )
        if (isEffectivelyBlackFrame(base64)) {
            return VisionTapOutcome(
                tapped = false,
                reason = "The screen is protected against capture (FLAG_SECURE), so I will not tap blind.",
                target = null
            )
        }

        val dims = screenDimensions()
        val response = providerAccess().complete(
            LLMRequest(
                systemPrompt = LOCATOR_SYSTEM_PROMPT +
                    (dims?.let { " The screen is ${it.first}x${it.second} pixels." } ?: ""),
                messages = listOf(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = "Locate the target and answer with the JSON object only. Target: $targetDescription",
                        sender = ChatMessage.Sender.USER,
                        imageBase64 = base64
                    )
                ),
                temperature = 0.1f,
                maxTokens = 200,
                responseFormat = ResponseFormat.JSON,
                requireVision = true
            )
        )

        val parsed = parseTargetResponse(response.content)
        val target = normalizePixelCoordinates(parsed, dims)

        if (!isTapEligible(target)) {
            return VisionTapOutcome(
                tapped = false,
                reason = if (!parsed.targetFound) {
                    "I could not find \"$targetDescription\" on this screen, so I did not tap."
                } else if (parsed.confidence <= TAP_CONFIDENCE_FLOOR) {
                    "The located target's confidence (${parsed.confidence}) is below the " +
                        "${TAP_CONFIDENCE_FLOOR} tap floor, so I did not tap."
                } else {
                    "The located coordinates are out of range, so I did not tap."
                },
                target = target
            )
        }

        val (width, height) = dims
            ?: return VisionTapOutcome(
                tapped = false,
                reason = "Screen dimensions are unavailable, so fractional coordinates cannot be mapped to pixels.",
                target = target
            )

        val pixelX = (target.x!! * width).toFloat()
        val pixelY = (target.y!! * height).toFloat()
        val dispatched = tapExecutor(pixelX, pixelY)
        return if (dispatched) {
            VisionTapOutcome(tapped = true, target = target)
        } else {
            VisionTapOutcome(
                tapped = false,
                reason = "The gesture dispatcher refused the tap.",
                target = target
            )
        }
    }

    /**
     * Some models answer in absolute pixels despite the fractions contract.
     * Values above 1.0 cannot be fractions, so — when the screen dimensions
     * are known — they are mapped back to fractions. A value of exactly 1.0
     * stays a fraction (the far edge).
     */
    private fun normalizePixelCoordinates(target: VisionTarget, dims: Pair<Int, Int>?): VisionTarget {
        if (dims == null) return target
        val x = target.x
        val y = target.y
        val nx = if (x != null && x > 1.0) x / dims.first else x
        val ny = if (y != null && y > 1.0) y / dims.second else y
        if (nx == x && ny == y) return target
        return target.copy(x = nx?.coerceIn(0.0, 1.0), y = ny?.coerceIn(0.0, 1.0))
    }

    private val LOCATOR_SYSTEM_PROMPT = """You are a vision-grounding agent for Android screenshots.
Locate the described target on the screenshot and answer with ONLY a JSON object — no prose, no markdown fences:
{"target_found": <true|false>, "x": <number>, "y": <number>, "confidence": <number>}
x is the target's horizontal center as a FRACTION of the screen width (0.0 = left edge, 1.0 = right edge).
y is the vertical center as a FRACTION of the screen height (0.0 = top edge, 1.0 = bottom edge).
confidence is 0.0 to 1.0 — how certain you are that the target is exactly at that position.
If the target is not visible, ambiguous, off-screen, or you are unsure, set target_found to false and x/y to 0."""

    /**
     * Capture the screen and analyze it with a vision-capable LLM.
     * Falls back to text-only analysis if screenshot capture fails.
     */
    suspend fun analyzeCurrentScreen(
        userQuestion: String = "What do you see on this screen?"
    ): String {
        // Image-based analysis first, with FLAG_SECURE black-frame detection.
        val capture = captureScreen()

        if (capture.base64 != null) {
            return analyzeWithImage(capture.base64, userQuestion)
        }

        // Fallback: text-based analysis using accessibility tree. A black frame
        // with a readable tree is still analyzable — FLAG_SECURE only blocks pixels.
        val screenText = getScreenText()
        if (screenText != null) {
            return analyzeWithText(screenText, userQuestion)
        }

        return when (capture.blindSpot) {
            BlindSpot.SECURE_FLAG_BLACK_FRAME ->
                "That screen is protected against capture (FLAG_SECURE) and its accessibility tree is empty, so I can neither see nor read it — and I will not blind-click into it. I can still open an app, a link, or a share sheet via Android intents if you tell me the target."
            else ->
                "I could not capture or read this screen. It may use a custom rendering engine like Flutter or Unity that exposes no accessibility nodes. I will not guess at coordinates — I can open an app, a link, or a share sheet via Android intents instead. The Accessibility Service may also need re-enabling in Settings > Accessibility > OpenDroid."
        }
    }

    private suspend fun analyzeWithImage(
        base64Image: String,
        userQuestion: String
    ): String {
        val visionPrompt = """
            Analyze this Android screenshot.
            User question: $userQuestion
            
            Describe:
            1. What app is open
            2. What content is visible
            3. Answer the user's specific question
            4. Any important information on screen
            
            Be concise and helpful.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val imageMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = visionPrompt,
                sender = ChatMessage.Sender.USER,
                imageBase64 = base64Image
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a vision AI that analyzes Android screenshots. Be concise and accurate.",
                    messages = listOf(imageMessage),
                    temperature = 0.3f,
                    maxTokens = 500,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis failed: ${e.message}")
            "I captured the screenshot but couldn't analyze it: ${e.message}"
        }
    }

    private suspend fun analyzeWithText(
        screenText: String,
        userQuestion: String
    ): String {
        val textPrompt = """
            I extracted the following text from the user's Android screen:
            
            ---
            $screenText
            ---
            
            User question: $userQuestion
            
            Based on the visible text, describe what's on screen and answer the user's question.
            Be concise and helpful.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = textPrompt,
                sender = ChatMessage.Sender.USER
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an AI that analyzes Android screen content from extracted text. Be concise and accurate.",
                    messages = listOf(message),
                    temperature = 0.3f,
                    maxTokens = 500,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Text analysis failed: ${e.message}")
            "I read the screen text but couldn't analyze it: ${e.message}"
        }
    }

    /**
     * Reads the current screen, extracts structured information (meeting details, key points, notes),
     * and returns a structured summary suitable for storage in notes or memory.
     */
    suspend fun extractAndStructureScreenInfo(
        topic: String = "important information"
    ): String {
        val capture = captureScreen()
        if (capture.base64 != null) {
            return extractWithImage(capture.base64, topic)
        }

        val screenText = getScreenText()
        if (screenText != null) {
            return extractWithText(screenText, topic)
        }

        return when (capture.blindSpot) {
            BlindSpot.SECURE_FLAG_BLACK_FRAME ->
                "This screen's content is protected against capture (FLAG_SECURE), so there is nothing to extract. Please copy the details somewhere readable, or tell me the target and I will open it via an intent."
            else ->
                "Could not capture or read the screen. Please ensure the Accessibility Service is enabled in Settings > Accessibility > OpenDroid."
        }
    }

    private suspend fun extractWithImage(
        base64Image: String,
        topic: String
    ): String {
        val extractionPrompt = """
            Analyze this Android screenshot and extract the requested information.
            Target topic: $topic
            
            Instructions:
            1. Thoroughly inspect all visible messages, notes, dates, times, locations, and details.
            2. Extract and structure all relevant information relating to "$topic".
            3. If this contains a meeting, event, or schedule, extract and format cleanly:
               • Meeting / Subject: <Name or purpose>
               • Date: <Date/Day>
               • Time: <Time>
               • Location: <Platform/Address/Link/Room>
               • Participants: <People mentioned>
               • Notes / Action Items: <Key details>
            4. If this is a general note, message, or article, format into concise bullet points.
            5. Keep formatting clean, markdown-friendly, and easy to read.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val imageMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = extractionPrompt,
                sender = ChatMessage.Sender.USER,
                imageBase64 = base64Image
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an expert information extraction and summarization AI for Android. Extract clean, structured notes from screenshots.",
                    messages = listOf(imageMessage),
                    temperature = 0.2f,
                    maxTokens = 600,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Image extraction failed: ${e.message}")
            val screenText = getScreenText()
            if (screenText != null) {
                extractWithText(screenText, topic)
            } else {
                "I captured the screenshot but couldn't extract the details: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    private suspend fun extractWithText(
        screenText: String,
        topic: String
    ): String {
        val extractionPrompt = """
            The following text was extracted from the user's Android screen:
            
            ---
            $screenText
            ---
            
            Target topic: $topic
            
            Instructions:
            1. Thoroughly read the screen text above.
            2. Extract and structure all relevant information relating to "$topic".
            3. If this contains a meeting, event, or schedule, format cleanly:
               • Meeting / Subject: <Name or purpose>
               • Date: <Date/Day>
               • Time: <Time>
               • Location: <Platform/Address/Link/Room>
               • Participants: <People mentioned>
               • Notes / Action Items: <Key details>
            4. If this is a general note, message, or info, format into concise bullet points.
            5. Keep formatting clean, markdown-friendly, and easy to read.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = extractionPrompt,
                sender = ChatMessage.Sender.USER
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an expert information extraction and summarization AI for Android. Extract clean, structured notes from screen text.",
                    messages = listOf(message),
                    temperature = 0.2f,
                    maxTokens = 600,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Text extraction failed: ${e.message}")
            "I read the screen text but couldn't extract the details: ${e.localizedMessage ?: e.message}"
        }
    }
}
