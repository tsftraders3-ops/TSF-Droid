package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Milestone 1 (EGM v2.0.0 arc) — the production goal verifier of the vision
 * driving loop, replacing the engine's `{ false }` default. The loop NEVER
 * claims [VisionDriveResult.GoalReached] without this verifier saying so.
 *
 * Decision tree (deterministic first, vision only as the empty-tree
 * fallback, never a guessed success):
 *  1. DETERMINISTIC — the post-settle accessibility text tree contains the
 *     expected text (case-insensitive) AND the text was absent from the
 *     baseline (the action guarantees this by construction; the flag is the
 *     second lock). No LLM.
 *  2. VISION FALLBACK — ONLY when the tree is empty (custom-rendered /
 *     FLAG_SECURE screens): one in-memory screenshot judgment with a strict
 *     `{"goal_reached": <bool>}` contract. Unparsable, failed, or absent
 *     capture → false.
 *
 * Everything unparsable or thrown → false. Cancellation propagates (loop
 * teardown is not a verification verdict).
 *
 * PRIVACY: the fallback frame travels as base64 through the existing
 * request seam and is never written to disk — this class performs zero disk
 * I/O and no logging of screen content.
 */
@Singleton
class VisionGoalVerifier @Inject constructor(
    private val visionEngine: VisionEngine,
    private val llmProviderFactory: LLMProviderFactory
) {
    // Test seams (the established VisionEngine pattern): defaults reproduce
    // the production paths exactly; hermetic tests substitute fakes.
    internal var providerAccess: suspend () -> LLMProvider =
        { llmProviderFactory.getActiveProvider() }

    /** Accessibility text tree of the current screen; null when blank/unavailable. */
    internal var screenTextProvider: () -> String? = { visionEngine.getScreenText() }

    /** Window context line (package/activity) appended to the vision prompt when available. */
    internal var windowContextProvider: () -> String = {
        val service = OpenDroidAccessibilityService.getInstance()
        val root = service?.rootInActiveWindow
        try {
            root?.packageName?.toString() ?: ""
        } finally {
            root?.recycle()
        }
    }

    /**
     * Screen-capture seam for the vision fallback (the VisionEngine seam
     * pattern): the default is the real FLAG_SECURE-aware
     * [VisionEngine.captureScreen]; hermetic tests substitute fixed captures.
     */
    internal var screenCapture: suspend () -> ScreenCaptureResult =
        { visionEngine.captureScreen() }

    /**
     * Non-suspending read-through of the screen text for the action's
     * baseline pre-check (expected text already visible → no drive at all).
     */
    fun currentScreenText(): String? = screenTextProvider()

    /**
     * Deterministic-first, vision-fallback, never throws, never guesses true.
     */
    suspend fun isReached(
        goal: String,
        expectedScreenText: String,
        expectedAbsentFromBaseline: Boolean
    ): Boolean {
        return try {
            val tree = screenTextProvider()
            if (tree != null) {
                tree.contains(expectedScreenText.trim(), ignoreCase = true) && expectedAbsentFromBaseline
            } else {
                visionCheck(goal, expectedScreenText)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The empty-tree fallback: ONE in-memory screenshot judgment.
     * FLAG_SECURE-aware — [VisionEngine.captureScreen] yields a null base64
     * for black frames, and a null capture is a false, never a guess.
     */
    private suspend fun visionCheck(goal: String, expectedScreenText: String): Boolean {
        val capture = screenCapture()
        val base64 = capture.base64 ?: return false
        val windowContext = windowContextProvider()
        return try {
            val response = providerAccess().complete(
                LLMRequest(
                    systemPrompt = GOAL_VERIFIER_SYSTEM_PROMPT +
                        (windowContext.takeIf { it.isNotEmpty() }
                            ?.let { "\nCurrent app: $it" } ?: ""),
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = "Goal: $goal\nExpected on screen when reached: \"$expectedScreenText\"\n" +
                                "Has the goal been achieved on this screen?",
                            sender = ChatMessage.Sender.USER,
                            imageBase64 = base64
                        )
                    ),
                    temperature = 0.0f,
                    maxTokens = 50,
                    responseFormat = ResponseFormat.JSON,
                    requireVision = true
                )
            )
            parseGoalReached(response.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Tolerant brace-substring extraction; only a strict Boolean true counts. */
    internal fun parseGoalReached(raw: String): Boolean {
        if (raw.isBlank()) return false
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        return try {
            val obj = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            obj["goal_reached"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        internal const val GOAL_VERIFIER_SYSTEM_PROMPT =
            "You verify whether a UI goal has been achieved on this screen. " +
                "Answer ONLY a JSON object: {\"goal_reached\": <true|false>}."
    }
}
