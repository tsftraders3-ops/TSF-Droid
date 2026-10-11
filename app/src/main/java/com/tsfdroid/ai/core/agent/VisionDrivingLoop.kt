package com.tsfdroid.ai.core.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.ResponseFormat
import com.tsfdroid.ai.data.models.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * God-Tier Phase 2, ticket 5.5 — one located waypoint of the driving loop.
 *
 * Like [VisionTarget], x/y are FRACTIONS of the screen (0.0..1.0, top-left
 * origin); [label] is the visible text the vision model reports for the
 * element it located — the consent gate reads it before any gesture.
 */
data class VisionDriveTarget(
    val found: Boolean,
    val x: Double?,
    val y: Double?,
    val confidence: Float,
    val label: String? = null
)

/** One executed driving step (a gesture was dispatched). */
data class VisionDriveStep(
    val stepIndex: Int,
    val x: Double?,
    val y: Double?,
    val confidence: Float,
    val label: String?
)

/**
 * The outcome of a driving loop run. Every halt is a VALUE — the loop never
 * throws to its caller.
 */
sealed class VisionDriveResult {
    /** The goal verifier confirmed completion after the last gesture. */
    data class GoalReached(val steps: List<VisionDriveStep>) : VisionDriveResult()

    /** The budget of MAX_STEPS gestures was spent without completion. */
    data class MaxStepsExceeded(val steps: List<VisionDriveStep>) : VisionDriveResult()

    /** A step's located confidence fell below the floor — the loop refuses to act on it. */
    data class LowConfidence(val steps: List<VisionDriveStep>, val confidence: Float, val atStep: Int) : VisionDriveResult()

    data class ConsentRequired(
        val steps: List<VisionDriveStep>,
        val targetLabel: String,
        val atStep: Int,
        val reason: String
    ) : VisionDriveResult()

    /** The loop could not continue (capture failed / dispatch refused / total timeout). */
    data class Halted(val steps: List<VisionDriveStep>, val reason: String) : VisionDriveResult()
}

/**
 * God-Tier Phase 2, ticket 5.5 — the Continuous Vision Driving Loop.
 *
 * A VERIFIED CAPABILITY with no production caller yet: AgentLoop still uses
 * the one-shot [VisionEngine.tapLocatedTarget]. The driving engine, its
 * bounds, and its consent gate are done and proven by tests; wiring the
 * loop into the agent's vision action path is the follow-up.
 *
 * Upgrades the one-shot [VisionEngine.tapLocatedTarget] into a BOUNDED,
 * multi-step autonomous driving loop:
 *
 *      capture -> vision locate -> CONSENT GATE -> confidence gate ->
 *      dispatch gesture -> wait for UI settle -> verify goal -> repeat
 *
 * Bounds (all load-bearing, all tested):
 *  - [MAX_STEPS] gestures, hard-capped — a never-satisfiable goal terminates
 *    in MaxStepsExceeded, never an infinite tap loop;
 *  - [TOTAL_TIMEOUT_MS] wraps the WHOLE run — a wedged screen cannot hold
 *    the loop hostage;
 *  - [CONFIDENCE_FLOOR] halts the loop the moment the locator's confidence
 *    drops below 0.85 — no blind retries on a degrading view;
 *  - the CONSENT GATE runs before the confidence gate: a located target
 *    whose label matches a policy-critical UI pattern (Delete / Pay / Send /
 *    ...) halts the loop with [VisionDriveResult.ConsentRequired] — the
 *    same consent boundary [AutoApprovalPolicy] enforces on planned
 *    actions, extended to "what is under the finger".
 *
 * PRIVACY: each frame lives as an in-memory [Bitmap] only, and is recycled
 * IMMEDIATELY after the vision call — before the gesture, before the next
 * step. The loop performs no disk writes at all (grep-verifiable: no
 * java.io, no MediaStore, no file API in this class).
 */
@Singleton
class VisionDrivingLoop @Inject constructor(
    private val visionEngine: VisionEngine,
    private val llmProviderFactory: LLMProviderFactory
) {
    // Test seams (the established VisionEngine pattern): defaults reproduce
    // the production paths exactly; hermetic tests substitute fakes.
    internal var providerAccess: suspend () -> com.tsfdroid.ai.core.llm.LLMProvider =
        { llmProviderFactory.getActiveProvider() }
    internal var screenCapture: suspend () -> Bitmap? = { captureScreenBitmap() }
    internal var visionLocate: suspend (Bitmap, String) -> VisionDriveTarget =
        { bitmap, goal -> locateOnFrame(bitmap, goal) }
    internal var gestureDispatch: suspend (Float, Float) -> Boolean = { x, y ->
        OpenDroidAccessibilityService.getInstance()?.clickCoordinates(x, y) ?: false
    }
    internal var screenDimensions: () -> Pair<Int, Int>? = {
        val service = OpenDroidAccessibilityService.getInstance()
        service?.resources?.displayMetrics?.let { it.widthPixels to it.heightPixels }
    }
    internal var waitForUiSettle: suspend () -> Unit = { delay(UI_SETTLE_MS) }

    /**
     * Conservative goal verifier. Default: NO signal — the loop NEVER
     * claims the goal is reached without a real verifier (failing closed on
     * success claims is as important as failing closed on taps). Production
     * wiring must inject a real verifier; the tests inject theirs.
     */
    internal var goalVerifier: suspend () -> Boolean = { false }

    /**
     * Runs the loop for [goal]. Returns a [VisionDriveResult] — never throws
     * for driving-level failures.
     */
    suspend fun drive(goal: String): VisionDriveResult {
        val steps = mutableListOf<VisionDriveStep>()
        val outcome: VisionDriveResult? = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            while (steps.size < MAX_STEPS) {
                val stepIndex = steps.size + 1

                // 1. Capture — in-memory only.
                val bitmap = screenCapture()
                    ?: return@withTimeoutOrNull VisionDriveResult.Halted(
                        steps.toList(),
                        "screen capture unavailable — refusing to drive blind"
                    )

                // 2. Vision locate. The frame is recycled IMMEDIATELY after
                //    analysis — before any gesture, before the next capture.
                val target: VisionDriveTarget
                try {
                    target = visionLocate(bitmap, goal)
                } finally {
                    bitmap.recycle()
                }

                // 3. CONSENT GATE (before confidence): a policy-critical
                //    label under the finger means the user must confirm — and
                //    so does an UNLABELED target: a coordinate tap that
                //    cannot be inspected before it fires is exactly the
                //    class AutoApprovalPolicy holds policy-critical
                //    (CLICK_COORDINATES). The gate FAILS CLOSED — a missing
                //    label is a missing safety verdict, never a free tap.
                if (target.found && (target.label == null || isPolicyCriticalTarget(target.label))) {
                    val label = target.label
                    return@withTimeoutOrNull VisionDriveResult.ConsentRequired(
                        steps = steps.toList(),
                        targetLabel = label ?: "(unlabeled target)",
                        atStep = stepIndex,
                        reason = if (label == null) {
                            "the next gesture targets an element the vision model could not label — " +
                                "an uninspectable coordinate tap requires explicit user consent"
                        } else {
                            "the next gesture targets '$label' — a policy-critical control " +
                                "that requires explicit user consent"
                        }
                    )
                }

                // 4. Confidence gate: below the floor, or nothing located at
                //    all — the loop halts instead of guessing.
                if (!target.found || target.confidence < CONFIDENCE_FLOOR) {
                    return@withTimeoutOrNull VisionDriveResult.LowConfidence(
                        steps = steps.toList(),
                        confidence = target.confidence,
                        atStep = stepIndex
                    )
                }

                // 5. Dispatch the gesture (fractions -> pixels).
                val dims = screenDimensions()
                if (dims == null || target.x == null || target.y == null) {
                    return@withTimeoutOrNull VisionDriveResult.Halted(
                        steps.toList(),
                        "screen dimensions unavailable — fractional coordinates cannot be mapped"
                    )
                }
                val dispatched = gestureDispatch(
                    (target.x * dims.first).toFloat(),
                    (target.y * dims.second).toFloat()
                )
                if (!dispatched) {
                    return@withTimeoutOrNull VisionDriveResult.Halted(
                        steps.toList(),
                        "the gesture dispatcher refused the tap"
                    )
                }
                steps += VisionDriveStep(
                    stepIndex = stepIndex,
                    x = target.x,
                    y = target.y,
                    confidence = target.confidence,
                    label = target.label
                )

                // 6. Wait (bounded) for the UI to settle before re-sensing.
                waitForUiSettle()

                // 7. Goal check: stop the moment the goal is verifiably met.
                if (goalVerifier()) {
                    return@withTimeoutOrNull VisionDriveResult.GoalReached(steps.toList())
                }
            }
            VisionDriveResult.MaxStepsExceeded(steps.toList())
        }
        return outcome
            ?: VisionDriveResult.Halted(steps.toList(), "driving timeout: the goal was not reached within ${TOTAL_TIMEOUT_MS}ms")
    }

    /**
     * Production locate: asks the vision model for the next element that
     * advances [goal], on the provided (already captured) frame. Never
     * guesses: an unparsable answer is a not-found target.
     */
    private suspend fun locateOnFrame(bitmap: Bitmap, goal: String): VisionDriveTarget {
        val dims = screenDimensions()
        val base64 = bitmapToBase64Png(bitmap)
        return try {
            val response = providerAccess().complete(
                LLMRequest(
                    systemPrompt = DRIVING_LOCATOR_SYSTEM_PROMPT +
                        (dims?.let { " The screen is ${it.first}x${it.second} pixels." } ?: ""),
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = "Locate the element that best advances this goal and answer with the JSON object only. Goal: $goal",
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
            parseLocateResponse(response.content, dims)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VisionDriveTarget(found = false, x = null, y = null, confidence = 0f, label = null)
        }
    }

    /**
     * Tolerant parser for the locator answer: markdown fences and prose are
     * tolerated, anything unparsable is a NOT-FOUND target — the loop never
     * acts on a guessed coordinate.
     */
    internal fun parseLocateResponse(raw: String, dims: Pair<Int, Int>?): VisionDriveTarget {
        if (raw.isBlank()) {
            return VisionDriveTarget(false, null, null, 0f, null)
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return VisionDriveTarget(false, null, null, 0f, null)
        }
        return try {
            val obj = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val found = obj["target_found"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val x = obj["x"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val y = obj["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val confidence = obj["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            val label = obj["label"]?.jsonPrimitive?.content
            val normalized = normalize(x, y, dims)
            VisionDriveTarget(
                found = found,
                x = normalized?.first,
                y = normalized?.second,
                confidence = confidence.coerceIn(0f, 1f),
                label = label
            )
        } catch (e: Exception) {
            VisionDriveTarget(false, null, null, 0f, null)
        }
    }

    /** Models sometimes answer in pixels; values > 1.0 are mapped back to fractions. */
    private fun normalize(x: Double?, y: Double?, dims: Pair<Int, Int>?): Pair<Double?, Double?> {
        if (dims == null) return x to y
        val nx = x?.let { if (it > 1.0) it / dims.first else it }?.coerceIn(0.0, 1.0)
        val ny = y?.let { if (it > 1.0) it / dims.second else it }?.coerceIn(0.0, 1.0)
        return nx to ny
    }

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun captureScreenBitmap(): Bitmap? {
        val base64 = visionEngine.captureScreenBase64() ?: return null
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private val DRIVING_LOCATOR_SYSTEM_PROMPT = """You are the vision driver of an autonomous Android agent.
Locate the screen element that best advances the user's goal and answer with ONLY a JSON object — no prose, no markdown fences:
{"target_found": <true|false>, "x": <number>, "y": <number>, "confidence": <number>, "label": "<the visible text of the element>"}
x is the element's horizontal center as a FRACTION of the screen width (0.0 = left edge, 1.0 = right edge).
y is the vertical center as a FRACTION of the screen height (0.0 = top edge, 1.0 = bottom edge).
confidence is 0.0 to 1.0 — how certain you are that the element is exactly at that position.
label is the element's own visible text, exactly as shown (used for the safety consent gate).
If no element advances the goal, or it is ambiguous or off-screen, set target_found to false and x/y to 0."""

    /**
     * The policy-critical UI vocabulary: labels that mean irreversible
     * consequences sit under the finger. Matched case-insensitively as
     * substrings. Deliberately does NOT include generic words like
     * "confirm"/"ok" — the gate must be precise enough to trust.
     */
    companion object {
        const val MAX_STEPS = 5
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val CONFIDENCE_FLOOR = 0.85f

        /** Bounded UI settle wait per step (no arbitrary long sleeps). */
        internal val UI_SETTLE_MS = 750L

        val POLICY_CRITICAL_UI_PATTERNS = listOf(
            // destruction
            "delete", "erase", "wipe", "uninstall", "remove",
            // money
            "pay", "payment", "purchase", "checkout", "buy", "transfer", "withdraw",
            // communication that leaves the device
            "send"
        )

        fun isPolicyCriticalTarget(label: String?): Boolean =
            label != null && POLICY_CRITICAL_UI_PATTERNS.any { label.contains(it, ignoreCase = true) }
    }
}
