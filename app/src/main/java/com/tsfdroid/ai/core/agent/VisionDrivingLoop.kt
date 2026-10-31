package com.tsfdroid.ai.core.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.tsfdroid.ai.accessibility.GenericAppAutomator
import com.tsfdroid.ai.accessibility.OpenDroidAccessibilityService
import com.tsfdroid.ai.accessibility.SetOfMarkRenderer
import com.tsfdroid.ai.accessibility.VisionScreenMark
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

/**
 * Milestone 1 (EGM v2.0.0 arc) — raw locator answer before grounding.
 * [node] is the numbered Set-of-Mark the model picked (1-based), null when
 * the model answered coordinates or nothing.
 */
internal data class VisionLocateAnswer(
    val found: Boolean,
    val x: Double?,
    val y: Double?,
    val node: Int?,
    val confidence: Float,
    val label: String?
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
        GenericAppAutomator.clickCoordinates(x, y)
    }
    internal var screenDimensions: () -> Pair<Int, Int>? = {
        val service = OpenDroidAccessibilityService.getInstance()
        service?.resources?.displayMetrics?.let { it.widthPixels to it.heightPixels }
    }
    internal var waitForUiSettle: suspend () -> Unit = { delay(UI_SETTLE_MS) }

    /**
     * Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark fast path: the numbered
     * interactive-element list read from the accessibility tree. Non-empty
     * marks make every locate step TEXT-ONLY (zero screenshot captures);
     * empty marks fall back to the unchanged bitmap path.
     */
    internal var somMarks: suspend () -> List<VisionScreenMark> = {
        OpenDroidAccessibilityService.getInstance()?.getInteractiveMarks() ?: emptyList()
    }

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
     *
     * Milestone 1 (EGM v2.0.0 arc): [verifier] defaults to the internal
     * [goalVerifier] seam (backward-compatible with the 12 existing tests);
     * [consentGate] is the one-shot approval hook the DRIVE_SCREEN_GOAL
     * action injects — a critical/unlabeled target passes ONLY when the
     * gate says so (unlabeled targets NEVER pass: an uninspectable tap
     * cannot be pre-approved); [maxSteps] bounds THIS run and is clamped to
     * [MAX_STEPS] — callers may lower the budget, never raise it.
     */
    suspend fun drive(
        goal: String,
        verifier: suspend () -> Boolean = this.goalVerifier,
        consentGate: (suspend (String) -> Boolean)? = null,
        maxSteps: Int = MAX_STEPS
    ): VisionDriveResult {
        val boundedMaxSteps = maxSteps.coerceAtMost(MAX_STEPS)
        val steps = mutableListOf<VisionDriveStep>()
        val outcome: VisionDriveResult? = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            while (steps.size < boundedMaxSteps) {
                val stepIndex = steps.size + 1

                // 1. LOCATE. Set-of-Mark fast path first: interactive marks
                //    from the accessibility tree make the step TEXT-ONLY —
                //    zero screenshot captures. Empty marks (custom-rendered
                //    or FLAG_SECURE screens) keep the unchanged bitmap path:
                //    in-memory capture, vision locate, immediate recycle.
                val marks = somMarks()
                val target: VisionDriveTarget = if (marks.isNotEmpty()) {
                    locateFromMarks(marks, goal)
                } else {
                    val bitmap = screenCapture()
                        ?: return@withTimeoutOrNull VisionDriveResult.Halted(
                            steps.toList(),
                            "screen capture unavailable — refusing to drive blind"
                        )
                    try {
                        visionLocate(bitmap, goal)
                    } finally {
                        bitmap.recycle()
                    }
                }

                // 2. CONSENT GATE (before confidence): a policy-critical
                //    label under the finger means the user must confirm — and
                //    so does an UNLABELED target: a coordinate tap that
                //    cannot be inspected before it fires is exactly the
                //    class AutoApprovalPolicy holds policy-critical
                //    (CLICK_COORDINATES). The gate FAILS CLOSED — a missing
                //    label is a missing safety verdict, never a free tap.
                //    An injected consentGate authorizes exactly the label it
                //    was armed for (the action builds it one-shot); an
                //    unlabeled target NEVER passes any gate.
                if (target.found && (target.label == null || isPolicyCriticalTarget(target.label))) {
                    val label = target.label
                    val authorized = label != null &&
                        consentGate != null &&
                        consentGate(label)
                    if (!authorized) {
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
                }

                // 3. Confidence gate: below the floor, or nothing located at
                //    all — the loop halts instead of guessing.
                if (!target.found || target.confidence < CONFIDENCE_FLOOR) {
                    return@withTimeoutOrNull VisionDriveResult.LowConfidence(
                        steps = steps.toList(),
                        confidence = target.confidence,
                        atStep = stepIndex
                    )
                }

                // 4. Dispatch the gesture (fractions -> pixels).
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

                // 5. Wait (bounded) for the UI to settle before re-sensing.
                waitForUiSettle()

                // 6. Goal check: stop the moment the goal is verifiably met.
                if (verifier()) {
                    return@withTimeoutOrNull VisionDriveResult.GoalReached(steps.toList())
                }
            }
            VisionDriveResult.MaxStepsExceeded(steps.toList())
        }
        return outcome
            ?: VisionDriveResult.Halted(steps.toList(), "driving timeout: the goal was not reached within ${TOTAL_TIMEOUT_MS}ms")
    }

    /**
     * Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark locate: ONE text-only LLM
     * call. The model sees the numbered mark list (no pixels) and answers a
     * node number; the chosen mark's bounds ground the coordinates
     * deterministically. Fail-closed in every undefined case:
     *  - the model answered x/y instead of a node → REFUSED (it never saw
     *    the screen, a coordinate it reports is ungrounded);
     *  - node out of `1..marks.size` or screen dimensions unavailable →
     *    not-found;
     *  - provider exception or unparsable answer → not-found.
     */
    private suspend fun locateFromMarks(marks: List<VisionScreenMark>, goal: String): VisionDriveTarget {
        val dims = screenDimensions()
        return try {
            val response = providerAccess().complete(
                LLMRequest(
                    systemPrompt = SOM_LOCATOR_SYSTEM_PROMPT +
                        SetOfMarkRenderer.render(marks).prependIndent("\n") +
                        (dims?.let { "\nThe screen is ${it.first}x${it.second} pixels." } ?: ""),
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = "Locate the element that best advances this goal and answer with the JSON object only. Goal: $goal",
                            sender = ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.1f,
                    maxTokens = 200,
                    responseFormat = ResponseFormat.JSON,
                    requireVision = false
                )
            )
            val answer = parseLocateAnswer(response.content)
            val node = answer.node
            if (!answer.found || node == null || node !in 1..marks.size || dims == null) {
                return VisionDriveTarget(found = false, x = null, y = null, confidence = 0f, label = null)
            }
            val mark = marks[node - 1]
            VisionDriveTarget(
                found = true,
                x = mark.centerXFrac(dims.first),
                y = mark.centerYFrac(dims.second),
                confidence = answer.confidence.coerceIn(0f, 1f),
                label = mark.label
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VisionDriveTarget(found = false, x = null, y = null, confidence = 0f, label = null)
        }
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
     * Tolerant parser for the raw locator answer: markdown fences and prose
     * are tolerated, anything unparsable is an all-empty answer. Reads the
     * optional integer "node" (Set-of-Mark pick) alongside the bitmap-path
     * fields; [parseLocateResponse] grounds the bitmap-path fields on top of
     * it.
     */
    internal fun parseLocateAnswer(raw: String): VisionLocateAnswer {
        if (raw.isBlank()) {
            return VisionLocateAnswer(false, null, null, null, 0f, null)
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return VisionLocateAnswer(false, null, null, null, 0f, null)
        }
        return try {
            val obj = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val found = obj["target_found"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val x = obj["x"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val y = obj["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val node = obj["node"]?.jsonPrimitive?.content?.toIntOrNull()
            val confidence = obj["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            val label = obj["label"]?.jsonPrimitive?.content
            VisionLocateAnswer(
                found = found,
                x = x,
                y = y,
                node = node,
                confidence = confidence.coerceIn(0f, 1f),
                label = label
            )
        } catch (e: Exception) {
            VisionLocateAnswer(false, null, null, null, 0f, null)
        }
    }

    /**
     * Bitmap-path grounding of [parseLocateAnswer]: identical behavior to
     * the pre-SoM parser (the 12 existing tests pin it), reimplemented as a
     * thin wrapper over the shared tolerant extractor.
     */
    internal fun parseLocateResponse(raw: String, dims: Pair<Int, Int>?): VisionDriveTarget {
        val answer = parseLocateAnswer(raw)
        val normalized = normalize(answer.x, answer.y, dims)
        return VisionDriveTarget(
            found = answer.found,
            x = normalized?.first,
            y = normalized?.second,
            confidence = answer.confidence,
            label = answer.label
        )
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
     * Milestone 1 (EGM v2.0.0 arc) — the Set-of-Mark locator contract. The
     * model NEVER sees pixels on this path; a coordinate answer is therefore
     * ungrounded by construction and refused downstream. node 0 means "no
     * listed element advances the goal".
     */
    internal val SOM_LOCATOR_SYSTEM_PROMPT = """You are the screen driver of an autonomous Android agent.
The screen's interactive elements are listed below as numbered marks — one per line:
[index] Kind "label" (left,top..right,bottom)
Pick the ONE mark that best advances the user's goal and answer with ONLY a JSON object — no prose, no markdown fences:
{"target_found": <true|false>, "node": <mark number>, "confidence": <0.0-1.0>}
node must be a mark number from the list — NEVER invent a number, NEVER answer coordinates (you cannot see the screen).
If no listed element advances the goal, or it is ambiguous, answer target_found false with node 0."""

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
