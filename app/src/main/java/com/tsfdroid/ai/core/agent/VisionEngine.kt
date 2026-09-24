package com.tsfdroid.ai.core.agent

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
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
 * Vision engine that captures screenshots and analyzes them using a vision-capable LLM.
 * Uses the existing AccessibilityService's takeScreenshotAndEncode() for capture,
 * with a fallback to getScreenText() for text-only analysis.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {
    companion object {
        private const val TAG = "VisionEngine"

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
