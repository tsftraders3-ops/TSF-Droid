# Milestone 1 — Wire VisionDrivingLoop + Set-of-Mark (SoM) Accessibility Grounding

*   **Author:** Principal Android Systems Architect (Elephant)
*   **Status:** In Review (EGM Phase A = this design; Phase B = TDD implementation after this spec reaches zero unresolved Blockers/Majors)
*   **Date:** 2026-10-31 (rev 2 — Goldfish remediation applied: 3 Blockers, 11 Majors resolved; see Resolve Log at end)
*   **Base:** `feature/egm-v2.0.0-arc` @ `cfa3cf1` (v1.8.0-rc.1)

--------------------------------------------------------------------------------

## Overview

Turn the verified-but-unwired `VisionDrivingLoop` engine (CHANGELOG item 5.5,
v1.8.0-rc.1 — see Additional Resources) into a production capability with
three additions: (1) Set-of-Mark grounding that lets the locator pick
numbered accessibility elements instead of guessing pixel coordinates,
(2) a real goal verifier replacing the always-`false` default, and (3) a
`DRIVE_SCREEN_GOAL` action that exposes the loop to plans and surfaces
mid-drive consent halts as interactive user questions through the existing
`NeedsInput` machinery.

## The Problem

Production screen interaction today is one-shot and tree-based:
`CLICK_TEXT` / `CLICK_ID` / `TYPE_TEXT` / `SCROLL` steps execute through
`GenericAppAutomator`, each as a separate plan step with no adaptation loop
and no goal verification. Neither vision tap path is wired: `VisionDrivingLoop`
has no production caller, and the one-shot `VisionEngine.tapLocatedTarget`
also has no production caller (repo-wide grep: only its definition, its unit
test, and a hermetic instrumentation test reference it). The loop's
`goalVerifier` seam defaults to `{ false }`, so even if called, every drive
would end `MaxStepsExceeded`. And every bitmap locate costs a screenshot +
vision round-trip — on the default keyless provider configuration
(`OpenCodeZenProvider`, no API key), measured streaming turns stretch from
~3 s median to 60–90 s under load (FEATURE_STATUS.md "Known Limitations"),
which busts the loop's unchanged 30 s total budget on the bitmap path. The
accessibility tree, meanwhile, already describes every interactive element
with bounds and labels — the locator never sees it.

## Goals

*   Goal 1: A drive step locates its next tap from the accessibility tree
    (numbered marks) with zero screenshot capture when marks exist.
*   Goal 2: `drive()` can return `GoalReached` in production via a
    deterministic `expectedScreenText` check against the post-settle tree,
    with a vision fallback only when the tree is empty.
*   Goal 3: `DRIVE_SCREEN_GOAL` is a registered, schema-validated action;
    mid-drive consent halts become interactive questions whose answers are
    bound to the exact approved label, single-use, and gesture-budget-aware.
*   Goal 4: All 1,143 existing unit tests stay green except one mechanical
    constructor-arg update (see Target File 17); the 12 existing
    `VisionDrivingLoopTest` tests pass unmodified.

## Non-Goals

*   Changing `AgentLoop`'s planner prompt or plan-evaluation logic beyond
    what the new schema entry makes visible to the planner.
*   Wiring the loop into `HarnessLoop` (the direct tool-call execution path
    that bypasses plan approval) — AgentLoop plan path only for this
    milestone.
*   Any Room schema change, new permission, or new third-party dependency.
*   Replacing or reconfiguring any LLM provider.

## Open Questions

(none — all decisions resolved in this document)

## Critical User Journeys (CUJs)

### CUJ 1: Drive a screen to a verifiable goal

*   **Given**: The user has the Accessibility Service enabled and an approved
    plan step `DRIVE_SCREEN_GOAL` with `goal="Wi-Fi details screen is open"`
    and `expectedScreenText="Network & internet"`. The starting screen does
    not show that text.
*   **When**: The step executes.
*   **Then**: Each step reads interactive marks from the accessibility tree,
    the model selects a numbered mark (no screenshot taken), the gesture
    dispatches at the mark's center through the settle barrier, the UI
    settles, and the verifier's contains-check confirms the text — the
    action returns `Success` with the driven step count. (If the expected
    text is already present on the starting screen, the action returns
    `Success` with zero steps: "goal already met".)

### CUJ 2: Mid-drive consent halt becomes an interactive question

*   **Given**: A drive step locates the mark `[2] Button "Delete account"`.
    ("Delete" matches the pre-existing critical-label vocabulary matcher
    inside `VisionDrivingLoop` step 3 — `POLICY_CRITICAL_UI_PATTERNS`
    (Delete / Pay / Send / …), unchanged by this design; this label-level
    matcher is distinct from the action-level `POLICY_CRITICAL_ACTIONS`
    set described below.)
*   **When**: The in-loop consent gate matches the critical label and no
    approval is active.
*   **Then**: The action returns `ActionResult.NeedsInput`; `AgentLoop`
    renders the verbatim question with tappable chips ("Yes, tap it" /
    "No, stop driving") and parks. A "Yes, tap it" answer is injected as
    `params["consent"]`; the action re-executes with a remaining-gesture
    budget; the consent gate authorizes exactly ONE tap of the previously
    halted label; a second critical tap — same label or not — halts and
    asks again (bounded by the existing 5-per-action / 8-per-plan prompt
    budgets). A non-affirmative answer ("No…", "not allow", "never approve
    this", free text) is an explicit decline: honest `Failure`, no re-drive.

### CUJ 3: Custom-rendered app falls back to the bitmap path

*   **Given**: The foreground app is Flutter-rendered; the accessibility
    tree yields zero interactive marks.
*   **When**: `DRIVE_SCREEN_GOAL` executes.
*   **Then**: The loop uses the unchanged bitmap path (in-memory capture,
    locate with the fractional `x`/`y` + `label` contract, immediate
    recycle) including the fail-closed unlabeled-target consent halt.
    On the keyless tier this path is expected to hit the 30 s timeout
    honestly (see Risks) — the timeout is the safety net, not a defect.

## Technical Plan (Architecture)

```text
plan step DRIVE_SCREEN_GOAL (AgentLoop, interactive, plan-approval gated)
        │
        ▼
DriveScreenGoalAction.execute(goal, expectedScreenText, [consent])
        │  0. baseline pre-check: expected text already on screen → Success(0 steps)
        │  1. approval state: goal-bound, one-tap, gesture-budget-bound (see below)
        ▼
VisionDrivingLoop.drive(goal, verifier, consentGate, remainingSteps)
        │
        ├─ somMarks() ── OpenDroidAccessibilityService.getInteractiveMarks()
        │                   └─ nodeTraversal.interactiveMarks(root, 25)   [root recycled]
        │                        → List<VisionScreenMark> (index, kind, label, px bounds)
        │
        ├─ marks non-empty ──► locateFromMarks(marks, goal)      [text-only LLM call]
        │      model answers {"node": N} → mark center fractions + mark label
        │      x/y answers are REFUSED in marks mode (node-or-not-found only)
        │
        ├─ marks empty ──────► screenCapture() → visionLocate() → recycle   [unchanged]
        │
        ├─ CONSENT GATE: critical label → consentGate(label)? : ConsentRequired halt
        │                unlabeled target → always ConsentRequired (fail closed)
        ├─ CONFIDENCE GATE (≥ 0.85) → gestureDispatch → GenericAppAutomator.clickCoordinates
        │                                              (awaitUiIdle settle barrier, reused)
        └─ settle → VisionGoalVerifier.isReached(goal, expected, baselineAbsent)
              ├─ tree non-empty → tree.contains(expected, ignoreCase)   [deterministic]
              └─ tree empty      → ONE vision judgment (VisionEngine.captureScreen(),
                                  FLAG_SECURE-aware; strict {"goal_reached": bool}, else false)

VisionDriveResult mapping (DriveScreenGoalAction):
  GoalReached        → ActionResult.Success
  ConsentRequired    → NeedsInput("param"→"consent")  [labeled]  → re-execute w/ approval
                     → UserActionRequired               [unlabeled] (no re-ask loop)
  MaxSteps/LowConf/Halted → ActionResult.Failure (reason)
```

**Consent binding (exact rules).** The action holds `pendingConsentGoal`
and `pendingConsentLabel` plus `gesturesSpent` (all instance state; the
single `ActionDispatcher` registry builds the action once at construction,
so the instance lives for the app lifetime). An approval is honored only
when ALL of: `consent` param non-empty and `isApproval` (token logic
below); `pendingConsentGoal == goal`; the halt was labeled; and the
pending record is fresh (recorded within `CONSENT_TTL_MS = 10 min`).
A honored approval builds a one-shot gate — the first critical label equal
to the approved label passes once, then the gate closes for the rest of
the run. Any consumed, declined, stale, or mismatched consent clears the
pending record. `gesturesSpent` accumulates `result.steps.size` from every
`VisionDriveResult` and caps the next `drive()` at
`maxSteps = (MAX_STEPS - gesturesSpent).coerceAtLeast(1)` — total gestures
across the entire ask-loop of one invocation never exceed 5.

**isApproval (token logic, conservative).** Tokenize on non-letters; if
any token is a negation (`not`, `no`, `never`, `dont`, `don't`, `stop`,
`cancel`, `refuse`, `n`) → false. Otherwise the FIRST token must be an
affirmative (`yes`, `approve`, `allow`, `continue`, `ok`) or the full
answer `tap it` → true. Everything else (including "sure", "maybe") is a
decline — conservative by design: the chip is the intended interface.

**Safety chain (defense in depth).** `DRIVE_SCREEN_GOAL` is added to
`AutoApprovalPolicy.POLICY_CRITICAL_ACTIONS` (the set of action names that
never auto-execute). Consequences, all pre-existing mechanisms keyed on
that set: the plan-level confirmation gate (AUTO/YOLO — the auto-approval
modes — refuse plans containing it; the plan falls back to PlanProposed,
the state where the user approves the plan interactively);
`ActionSequenceExecutor` (background multi-action sequences, "macros")
refuses it; `McpServer`, `SubAgentGraph`, and `ProactiveSuggestion` refuse
it. Inside the run: the loop's label-level consent gate, the 0.85
confidence floor, MAX_STEPS=5 / 30 s budgets, and the one-shot label-bound
approval above.

## Alternatives Considered

### Alternative A: Teach the existing one-shot `tapLocatedTarget` a marks mode instead of wiring the loop

*   **Pros**: Smaller diff; one code path.
*   **Cons**: Keeps the no-goal-verifier status quo (each tap is a separate
    plan step; the plan-evaluation LLM judges completion from prose); does
    not deliver the bounded multi-step driving the milestone demands;
    `VisionDrivingLoop` stays dead code.
*   **Reason for Rejection**: The milestone's explicit purpose is to wire
    the loop; the engine and its 12 tests already exist and are paid for.

### Alternative B: Always send BOTH the screenshot and the mark list to the locator

*   **Pros**: Maximum locate accuracy on vision-capable providers.
*   **Cons**: Keeps the slow vision round-trip (the keyless OpenCode Zen
    tier serves text and vision on the same throttled queue; some free
    models are text-only and cannot answer an image prompt at all, which
    is why CUJ 3's bitmap fallback is best-effort, not primary).
*   **Reason for Rejection**: Defeats Goal 1 (zero-capture fast path). The
    bitmap path remains available automatically whenever marks are empty.

### Alternative C: Approve consent via a persistent grant set ("always allow label X")

*   **Pros**: Fewer prompts on repetitive flows.
*   **Cons**: A stale grant silently approves a future critical tap — exactly
    the silent-side-effect class the consent boundary exists to prevent;
    `AutoApprovalPolicy` deliberately offers no always-allow for critical
    actions (the Safety-Absolute invariant: new features may never bypass
    the `AutoApprovalPolicy` consent boundary).
*   **Reason for Rejection**: Violates safety. The chosen design is
    single-use, goal-bound, TTL-bounded, and gesture-budget-bound; a
    different critical label re-asks.

### Alternative D: Implement consent re-ask with a new AgentLoop-specific branch for DRIVE_SCREEN_GOAL

*   **Pros**: Tailor-made control.
*   **Cons**: Duplicates `resolveNeedsInput` — chat rendering, option chips,
    the stop-command lexicon, the 5-per-action / 8-per-plan prompt budgets,
    and the pre-armed answer routing (all subtle, field-hardened
    behaviors); every future consent action would grow its own copy.
*   **Reason for Rejection**: Reuse violation. `NeedsInput` +
    `metadata["param"]` injection already provides the machinery; the one
    gap (question copy — see Target File 8) is a two-line passthrough.

### Alternative E: The planner composes existing tree-based actions (CLICK_TEXT / TYPE_TEXT / SCROLL chains) instead of a new action

*   **Pros**: Zero new engine surface; each tap node-grounded via
    `findAndClick`; retries already exist per step.
*   **Cons**: The planner cannot pre-know dynamic screen labels (search
    results, in-app browsers, arbitrary apps), so plans break at the first
    unexpected label; each step is a plan step (no in-flight adaptation);
    there is no goal-verification loop, so "done" is judged from prose;
    this milestone's mandate (wiring the verified driving engine) would be
    dropped.
*   **Reason for Rejection**: It is the right tool for static, known-label
    flows — which the planner already has. `DRIVE_SCREEN_GOAL` targets the
    complementary case: dynamic/unknown-structure screens where label
    pre-planning fails.

## Risks & Limitations

*   **Locale gap (accepted)**: the loop's critical-label vocabulary is
  English-only substring matching (`POLICY_CRITICAL_UI_PATTERNS`). A
  non-English critical label ("Konto löschen", "删除") does not halt.
  Mitigations: unlabeled targets still fail closed; the action is
  plan-approval gated; approval is one-shot and label-bound. Documented as
  a known limitation; a localized vocabulary is a follow-up.
*   **25-mark truncation (accepted)**: `interactiveMarks` caps the numbered
  list at 25 in depth-first order; on dense screens the goal element may
  be absent. In marks mode the model must then answer `target_found=false`
  (x/y is refused), the loop halts `LowConfidence` honestly. Scrolling
  SoM is a future improvement.
*   **30 s budget vs free-tier latency (accepted)**: the bitmap path
  (CUJ 3) is expected to hit the timeout on the keyless tier — that is the
  documented safety net. The marks path spends one text-only call per
  step; if the provider throttles, the run still ends in bounded
  `Halted`, never an infinite drive.
*   **Approval freshness residual risk (accepted)**: the approval is
  label-bound, not bounds-bound; the screen may change while the ask is
  parked. Mitigations: the settle barrier at dispatch, one-shot
  consumption, goal binding, the 10-minute TTL, and the 5-gesture total
  cap. A bounds-revalidating gate is a follow-up if field evidence
  demands it.
*   **Re-driven non-critical taps (accepted, bounded)**: after an approval
  re-execution, the drive may re-tap a non-critical element it already
  tapped (e.g., a toggle). Mitigated by the shared gesture budget (total
  ≤ 5 per invocation) and the honest per-run reporting.
*   **Bitmap-path baseline blindness (accepted)**: on an empty-tree screen
    (FLAG_SECURE / custom-rendered) the baseline pre-check sees a null
    baseline, so "expected text already visible in pixels on the starting
    screen" is guarded only by the one-shot vision judgment. The marks-path
    baseline (the common case) is fully deterministic.

## Non-Functional Requirements

*   **Security & Privacy**: The SoM path performs no screenshot capture and
    no disk I/O (strict privacy improvement). The bitmap path's in-memory +
    immediate-recycle contract is untouched (no new `java.io` / `MediaStore`
    usage). Goal/expected text reaches only the LLM provider through the
    existing request seam — never Logcat (`ActionDispatcher.redactedParamsForLog`
    already redacts values).
*   **Performance**: Marks path = one text-only LLM call per step plus, per
    step, the deterministic verifier (no LLM when `expectedScreenText` is
    matched against a non-empty tree). `interactiveMarks` collects at most
    25 marks in one depth-first walk (node visits are bounded by the walk
    aborting once 25 marks are collected or the tree is exhausted).
*   **Rollout & Rollback**: One new schema entry, one new policy-set entry,
    one new action class, defaulted parameters, plus one mechanical
    constructor-arg update in an existing test. Revert = revert the
    milestone commits; no data migrations.

--------------------------------------------------------------------------------

### Additional Resources

*   `FEATURE_STATUS.md` (v1.8.0-rc.1) — "engines proven, CEO wiring pending"
    (CEO = the `AgentLoop` orchestrator, in-repo jargon)
*   `CHANGELOG.md` v1.8.0-rc.1 — item 5.5 engine bounds (5 steps / 30 s / 0.85)
*   `VisionDrivingLoopTest.kt`, `VisionTargetTapTest.kt` — engine behavior
*   EGM artifact set for this milestone:
    `docs/designs/egm-artifacts/milestone_1_brief.md`

### Future Improvements

*   **The idea**: mark-list scrolling (SoM over `SCROLL_FORWARD` when no
    mark advances the goal) — deferred; the loop halts honestly instead.
*   **The idea**: bounds-revalidating consent gate — deferred pending field
    evidence (see Risks).

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Implementation proceeds in three compile-green checkpoints, then Phase B:

*   **Checkpoint 1**: Files 1–3 + Test 9 (marks model, traversal, service
    accessor) — build green, new tests red→green.
*   **Checkpoint 2**: Files 4–5 + Tests 10–11 (verifier, loop SoM path) —
    build green.
*   **Checkpoint 3**: Files 6–8, 17 + Tests 12–14 (action, schema, policy,
    GoalContract, test constructor fix) — build green, full suite green.
*   **Phase B**: Files 15–16 (hermetic test, FEATURE_STATUS) after the
    Gradle gate.

### Target Files to Create/Modify

#### 1. `app/src/main/java/com/tsfdroid/ai/accessibility/VisionScreenMark.kt` (Create)

*   **Type / Scope**: Pure Kotlin data model + renderer (no Android imports).
*   **Proposed Changes**:
    ```kotlin
    package com.tsfdroid.ai.accessibility

    enum class MarkKind { BUTTON, TEXT_FIELD, CHECKBOX, SWITCH, SPINNER, SCROLLABLE, OTHER }

    data class VisionScreenMark(
        val index: Int,       // 1-based, assigned in walk order
        val kind: MarkKind,
        val label: String?,   // text ?: contentDescription, trimmed; null when both blank
        val left: Int, val top: Int, val right: Int, val bottom: Int  // getBoundsInScreen px
    ) {
        fun centerXFrac(screenWidth: Int): Double = ((left + right) / 2.0) / screenWidth
        fun centerYFrac(screenHeight: Int): Double = ((top + bottom) / 2.0) / screenHeight
    }

    object SetOfMarkRenderer {
        /** One line per mark: `[1] Button "Search" (120,450..340,520)`;
         *  null label renders as `(unlabeled)`. Empty list → "".
         *  NOTE: this `(unlabeled)` placeholder is a DIFFERENT string from
         *  VisionDrivingLoop's "(unlabeled target)" consent sentinel — do not unify them. */
        fun render(marks: List<VisionScreenMark>): String
    }
    ```

#### 2. `app/src/main/java/com/tsfdroid/ai/accessibility/AccessibilityNodeTraversal.kt` (Modify)

*   **Type / Scope**: New pure traversal method (existing methods untouched).
*   **Proposed Changes** — add:
    ```kotlin
    fun interactiveMarks(root: AccessibilityNodeInfo?, maxMarks: Int = 25): List<VisionScreenMark>
    ```
    Depth-first walk; a node becomes a mark when
    `node.isClickable || node.isEditable || node.isScrollable` AND its
    `getBoundsInScreen` rect has `width() > 0 && height() > 0`; the walk
    aborts once `maxMarks` marks are collected. Kind resolution table,
    evaluated in order: `isEditable → TEXT_FIELD`; `isCheckable → CHECKBOX`;
    `isScrollable → SCROLLABLE`; `className` ends `Spinner → SPINNER`;
    `className` ends `Switch → SWITCH`; `className` ends `Button → BUTTON`;
    else `OTHER`. Label = `node.text ?: node.contentDescription`, trimmed,
    null when blank. Intermediate child nodes recycled as the existing
    walkers do; the caller recycles the root.

#### 3. `app/src/main/java/com/tsfdroid/ai/accessibility/OpenDroidAccessibilityService.kt` (Modify)

*   **Type / Scope**: One new public accessor reusing the existing Hilt
    field `nodeTraversal` (the same instance `getScreenText()` uses).
*   **Proposed Changes** — add:
    ```kotlin
    fun getInteractiveMarks(maxMarks: Int = 25): List<VisionScreenMark> {
        val root = rootInActiveWindow ?: return emptyList()
        try {
            return nodeTraversal.interactiveMarks(root, maxMarks)
        } finally {
            root.recycle()
        }
    }
    ```

#### 4. `app/src/main/java/com/tsfdroid/ai/core/agent/VisionGoalVerifier.kt` (Create)

*   **Type / Scope**: New @Singleton verifier with test seams
    (`providerAccess` seam per the VisionEngine pattern; screen text
    delegated to `VisionEngine.getScreenText()` — no duplicated plumbing).
*   **Proposed Changes**:
    ```kotlin
    @Singleton
    class VisionGoalVerifier @Inject constructor(
        private val visionEngine: VisionEngine,
        private val llmProviderFactory: LLMProviderFactory
    ) {
        internal var providerAccess: suspend () -> LLMProvider = { llmProviderFactory.getActiveProvider() }
        internal var screenTextProvider: () -> String? = { visionEngine.getScreenText() }
        /** Window context line (package/activity) appended to the vision prompt when available. */
        internal var windowContextProvider: () -> String = {
            OpenDroidAccessibilityService.getInstance()?.rootInActiveWindow?.packageName?.toString() ?: ""
        }

        /** Deterministic-first, vision-fallback, never throws, never guesses true. */
        suspend fun isReached(goal: String, expectedScreenText: String, expectedAbsentFromBaseline: Boolean): Boolean
        // Decision tree (exact):
        //   tree = screenTextProvider()            // visionEngine.getScreenText(): null when blank
        //   tree != null → tree.contains(expectedScreenText.trim(), ignoreCase = true)
        //                  && expectedAbsentFromBaseline
        //   tree == null → visionCheck(goal, expectedScreenText)   // ONLY when the tree is empty
        //
        // visionCheck: capture = visionEngine.captureScreen()
        //   (FLAG_SECURE-aware: black frame → base64 null) → null → false
        //   base64 != null → providerAccess().complete(LLMRequest(
        //     systemPrompt = GOAL_VERIFIER_SYSTEM_PROMPT (+ window context line when non-empty),
        //     messages = [ChatMessage(text = goal + expected text question, imageBase64 = capture.base64)],
        //     temperature = 0.0f, maxTokens = 50, responseFormat = JSON))
        //   Parse: brace-substring JSON, "goal_reached" must be Boolean true → true; else false.
        //   All exceptions → false.
        // GOAL_VERIFIER_SYSTEM_PROMPT: "You verify whether a UI goal has been achieved on this
        //   screen. Answer ONLY a JSON object: {\"goal_reached\": <true|false>}."
    ```

#### 5. `app/src/main/java/com/tsfdroid/ai/core/agent/VisionDrivingLoop.kt` (Modify)

*   **Type / Scope**: SoM fast path + consent-gate + verifier/maxSteps
    parameters + settle-barrier dispatch.
*   **Proposed Changes**:
    *   New parse output (internal data class):
        ```kotlin
        internal data class VisionLocateAnswer(
            val found: Boolean, val x: Double?, val y: Double?,
            val node: Int?, val confidence: Float, val label: String?
        )
        internal fun parseLocateAnswer(raw: String): VisionLocateAnswer
        // Same tolerant brace-substring JSON extraction as parseLocateResponse,
        // additionally reading optional integer "node". Unparsable → all-empty answer.
        ```
    *   `parseLocateResponse(raw, dims)` reimplemented as a thin wrapper over
        `parseLocateAnswer` + existing `normalize` — identical behavior for
        the 12 existing tests.
    *   New seam:
        ```kotlin
        internal var somMarks: suspend () -> List<VisionScreenMark> = {
            OpenDroidAccessibilityService.getInstance()?.getInteractiveMarks() ?: emptyList()
        }
        ```
    *   **`gestureDispatch` default seam changes** from the direct service
        call to the settle-barrier path (one line, matching every other
        production tap path):
        ```kotlin
        internal var gestureDispatch: suspend (Float, Float) -> Boolean = { x, y ->
            GenericAppAutomator.clickCoordinates(x, y)
        }
        ```
    *   `drive` signature (backward-compatible defaults; the `goalVerifier`
        internal seam remains for the 12 existing tests — new code passes
        parameters):
        ```kotlin
        suspend fun drive(
            goal: String,
            verifier: suspend () -> Boolean = this.goalVerifier,
            consentGate: (suspend (String) -> Boolean)? = null,
            maxSteps: Int = MAX_STEPS
        ): VisionDriveResult
        ```
        The loop bound becomes `steps.size < maxSteps` (callers cannot
        raise it above `MAX_STEPS` — clamp `maxSteps.coerceAtMost(MAX_STEPS)`).
        Step 7 calls `verifier()`. The consent branch (step 3) becomes:
        a critical or unlabeled target passes ONLY when
        `target.label != null && consentGate != null && consentGate(target.label!!)`;
        otherwise the existing `ConsentRequired` payload (unchanged fields,
        including the `"(unlabeled target)"` sentinel for null labels).
        Steps 1–2 become: `val marks = somMarks()`; if non-empty →
        `locateFromMarks(marks, goal)` (no `screenCapture()` call at all);
        if empty → existing capture/locate/recycle path with the existing
        `Halted("screen capture unavailable — refusing to drive blind")`.
    *   New internal locator:
        ```kotlin
        private suspend fun locateFromMarks(marks: List<VisionScreenMark>, goal: String): VisionDriveTarget
        ```
        Text-only `providerAccess().complete(LLMRequest(...))` — system
        prompt = `SOM_LOCATOR_SYSTEM_PROMPT` + `SetOfMarkRenderer.render(marks)`
        + screen-size line; user message = goal; `temperature = 0.1f`,
        `maxTokens = 200`, `responseFormat = JSON`, no `imageBase64`.
        Answer resolution (fail-closed in every undefined case):
        *   `node` present and in `1..marks.size` and `dims != null` →
            `VisionDriveTarget(found = true, x = mark.centerXFrac(w),
            y = mark.centerYFrac(h), confidence = answer.confidence.coerceIn(0f,1f),
            label = mark.label)`
        *   `node` present but out of range, or `dims == null`, or `x`/`y`
            answered instead of `node` (x/y is REFUSED in marks mode — the
            model never saw the screen, so a coordinate it reports is
            ungrounded) → not-found target
        *   Provider exception → not-found target (existing convention).
    *   `SOM_LOCATOR_SYSTEM_PROMPT` (new constant): numbered-mark contract —
        answer ONLY `{"target_found": <bool>, "node": <mark number>,
        "confidence": <0..1>}`; `node` must be a number from the list;
        if no listed element advances the goal, answer `target_found` false
        with `node` 0 — NEVER invent coordinates.

#### 6. `app/src/main/java/com/tsfdroid/ai/actions/AdvancedControlActions.kt` (Modify)

*   **Type / Scope**: Constructor injection + one new action class.
*   **Proposed Changes**:
    *   Constructor:
        ```kotlin
        class AdvancedControlActions @Inject constructor(
            private val visionDrivingLoop: VisionDrivingLoop,
            private val visionGoalVerifier: VisionGoalVerifier
        )
        ```
        (Hilt-safe: both are `@Singleton`/`@Inject`-constructible; the only
        production injection site is `ActionDispatcher`, which builds its
        registry once at construction.)
    *   `getActions()` gains `DriveScreenGoalAction(visionDrivingLoop, visionGoalVerifier)`.
    *   New private class:
        ```kotlin
        private class DriveScreenGoalAction(
            private val loop: VisionDrivingLoop,
            private val goalVerifier: VisionGoalVerifier
        ) : Action {
            override val name: String = "DRIVE_SCREEN_GOAL"
            private var pendingConsentGoal: String? = null
            private var pendingConsentLabel: String? = null
            private var pendingConsentAt: Long = 0L
            private var gesturesSpent: Int = 0
            companion object {
                internal const val CONSENT_TTL_MS = 10 * 60_000L
                internal const val UNLABELED_SENTINEL = "(unlabeled target)" // == VisionDrivingLoop's payload
                private val NEGATIONS = setOf("not", "no", "never", "dont", "don't", "stop", "cancel", "refuse", "n")
                private val AFFIRMATIVES = setOf("yes", "approve", "allow", "continue", "ok")
            }
        }
        ```
        Behavior (exact order):
        1. `goal = params["goal"]?.trim()?.takeIf { it.isNotEmpty() }` else
           `ActionResult(false, null, "goal parameter is missing")`.
        2. `expected = params["expectedScreenText"]?.trim()?.takeIf { it.isNotEmpty() }`
           ?: return `ActionResult(false, null, "expectedScreenText parameter is required — the drive refuses to claim success it cannot verify")`.
        3. **Baseline pre-check**: `baseline = goalVerifier.currentScreenText()`
           (a new non-suspending read-through of `screenTextProvider`); if
           `baseline.contains(expected, ignoreCase = true)` → return
           `ActionResult(true, "Goal already met on the current screen — 0 steps driven: $goal", null)`.
           (`expectedAbsentFromBaseline` passed to the verifier later is
           always `true` by construction — the action never starts a drive
           whose expected text is already visible.)
        4. Consent resolution: `consentAnswer = params["consent"]?.trim()`.
           *   consentAnswer null → no approval (first run); proceed to 5.
           *   `isApproval(consentAnswer)` false → **explicit decline**:
               clear pending state, return
               `ActionResult(false, null, "You declined the tap on '${pendingConsentLabel ?: "the critical control"}' — drive stopped.")`
               (no re-drive).
           *   approval but `pendingConsentGoal != goal` or
               `now - pendingConsentAt > CONSENT_TTL_MS` or
               `pendingConsentLabel == null` → stale/mismatched: clear
               pending, proceed with no approval (the drive re-evaluates
               and re-asks if it still halts).
           *   else → honored: read label, clear pending, build one-shot
               gate below.
        5. `result = loop.drive(
                goal = goal,
                verifier = { goalVerifier.isReached(goal, expected, expectedAbsentFromBaseline = true) },
                consentGate = honoredLabel?.let { lbl -> one-shot { candidate -> candidate == lbl } },
                maxSteps = (VisionDrivingLoop.MAX_STEPS - gesturesSpent).coerceIn(1, VisionDrivingLoop.MAX_STEPS)
            )`
            — after every result: `gesturesSpent += result.steps.size`.
            `gesturesSpent` resets to 0 on every execute() call that arrives
            WITHOUT a `consent` param (a fresh invocation — new plan step,
            plan re-run, or first run); only the approval re-execution within
            one ask-loop inherits the depleted budget.
            The one-shot gate consumes ONLY on a label EQUAL to the approved
            label — wrong-label calls leave the gate armed.
        6. Mapping:
            *   `GoalReached` → `ActionResult(true, "Goal reached after ${result.steps.size} step(s): $goal", null)`
            *   `ConsentRequired` with a real label (`result.targetLabel != UNLABELED_SENTINEL`) →
                record `pendingConsentGoal = goal; pendingConsentLabel = result.targetLabel; pendingConsentAt = now`
                and return
                `ActionResult.NeedsInput(question = "Screen driving needs your approval before it taps '${result.targetLabel}'. ${result.reason} Continue?", options = listOf("Yes, tap it", "No, stop driving"), metadata = mapOf("param" to "consent", "type" to "vision_drive_consent"))`
            *   `ConsentRequired` for the sentinel →
                `ActionResult.UserActionRequired("I stopped the screen drive: ${result.reason}")`
                (no re-ask loop — an uninspectable tap can never be
                pre-approved)
            *   `MaxStepsExceeded` → `ActionResult(false, null, "Drove ${result.steps.size} step(s) toward '$goal' but could not verify the goal — stopping instead of guessing.")`
            *   `LowConfidence` → `ActionResult(false, null, "Stopped at step ${result.atStep}: the next tap target was unclear (confidence ${result.confidence}, floor ${VisionDrivingLoop.CONFIDENCE_FLOOR}) — refusing to guess.")`
            *   `Halted` → `ActionResult(false, null, "Driving halted: ${result.reason}")`
    *   `isApproval` (companion, testable):
        ```kotlin
        internal fun isApproval(answer: String): Boolean {
            val tokens = answer.trim().lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            if (tokens.any { it in NEGATIONS }) return false
            return tokens.first() in AFFIRMATIVES || answer.trim().lowercase() == "tap it"
        }
        ```

#### 7. `app/src/main/java/com/tsfdroid/ai/core/agent/ActionSchema.kt` (Modify)

*   **Type / Scope**: One new `ActionDefinition` in the ADVANCED block,
    placed after `CLICK_COORDINATES`.
*   **Proposed Changes**:
    ```kotlin
    ActionDefinition(
        name = "DRIVE_SCREEN_GOAL",
        description = """Drives the screen step by step toward a goal: reads
            the interactive elements (Set-of-Mark) or takes a screenshot, taps
            the element that best advances the goal, waits for the screen to
            settle, and re-checks until the goal text is verified. One run:
            max 5 taps, 30 seconds total; consent re-asks are bounded by the
            plan's prompt budget and the same 5-tap total. Policy-critical:
            requires plan approval and asks again before any
            destructive-looking control.""",
        params = listOf(
            ParamDefinition("goal", ParamType.STRING, true,
                "What the driving should achieve on screen, e.g. 'open the Wi-Fi settings screen'"),
            ParamDefinition("expectedScreenText", ParamType.STRING, true,
                "Text that will be visible when the goal is reached — used to verify success (must NOT be visible on the starting screen)")
        ),
        examples = listOf("drive the screen until the Wi-Fi settings are open"),
        category = ActionCategory.ADVANCED,
        isSimple = false
    )
    ```
    Risk: left `UNSPECIFIED` → `ActionRiskPolicy.forDefinition` maps ADVANCED
    → `ADVANCED_CONTROL` (same class as CLICK_COORDINATES).
    `neverAutoApprove = false` (the POLICY_CRITICAL set entry, not this
    flag, carries the confirmation gate).

#### 8. `app/src/main/java/com/tsfdroid/ai/core/agent/GoalContract.kt` (Modify)

*   **Type / Scope**: One passthrough branch in `humanizeParamPrompt`
    (line ~288). Without it, every generic NeedsInput question is rewritten
    to "What should I use for 'consent'?" — destroying the consent question
    copy (the label being approved appears at char ~48, invisible in the
    30-char chip header; only TTS would speak it).
*   **Proposed Changes**: in the `when (key)` block add:
    ```kotlin
    "consent" -> return originalQuestion   // consent questions are already human-written
    ```
    (The function's tail appends option lines for other keys; for consent
    the verbatim question already includes everything.)

#### 9. `app/src/main/java/com/tsfdroid/ai/core/agent/AutoApprovalPolicy.kt` (Modify)

*   **Type / Scope**: One set entry.
*   **Proposed Changes**: add `"DRIVE_SCREEN_GOAL"` to
    `POLICY_CRITICAL_ACTIONS` inside the existing CLICK_COORDINATES comment
    block (a bounded sequence of coordinate taps — same uninspectable-tap
    rationale).

#### 10. `app/src/test/java/com/tsfdroid/ai/accessibility/VisionScreenMarkTest.kt` (Create — TDD RED, Checkpoint 1)

*   **Type / Scope**: Pure JVM unit tests.
*   **Proposed Changes** (exact cases):
    *   `render_emptyList_isEmpty`
    *   `render_formatsIndexKindLabelBounds` — mark(1, BUTTON, "Search", 120,450,340,520) renders `[1] Button "Search" (120,450..340,520)`
    *   `render_nullLabel_usesUnlabeledPlaceholder` — `(unlabeled)` inside the quotes
    *   `centerFractions_areBoundsMidpointOverScreen` — bounds 120..340 on width 1080 → `230.0/1080`; 450..520 on height 2400 → `485.0/2400`

#### 11. `app/src/test/java/com/tsfdroid/ai/core/agent/VisionGoalVerifierTest.kt` (Create — TDD RED, Checkpoint 2)

*   **Type / Scope**: Unit tests, fake `providerAccess` seam (scripted
    provider per the `VisionTargetTapTest` fake pattern).
*   **Proposed Changes** (exact cases):
    *   `expectedText_foundInTree_isReachedTrue` — deterministic; provider seam never invoked (call counter)
    *   `expectedText_missingFromTree_isReachedFalse` (deterministic)
    *   `expectedText_caseInsensitiveMatch`
    *   `expectedText_baselineAbsentFlagFalse_isReachedFalse` — the `expectedAbsentFromBaseline=false` guard
    *   `blankTree_fallsBackToVisionCheck` — screenTextProvider → null; provider answers `{"goal_reached": true}` with an image message → true; provider answers `{"goal_reached": false}` → false
    *   `visionCheck_unparsableAnswer_isFalse` — provider answers prose without JSON
    *   `visionCheck_providerThrows_isFalse`
    *   `visionCheck_blackFrameCapture_isFalse` — `visionEngine.captureScreen()` faked to the FLAG_SECURE result (base64 null) → false, provider never called

#### 12. `app/src/test/java/com/tsfdroid/ai/core/agent/VisionDrivingLoopTest.kt` (Modify — TDD RED, Checkpoint 2; existing 12 tests untouched)

*   **Type / Scope**: Additive tests using the established fake-seam style.
*   **Proposed Changes** (exact new cases):
    *   `som_marksPresent_skipsScreenCapture` — somMarks returns 2 marks; screenCapture seam increments a counter; fake provider answers `{"target_found":true,"node":2,"confidence":0.9}`; assert capture count == 0 and gesture dispatched at mark 2's center fractions, with the mark's own label
    *   `som_nodeOutOfRange_isNotFound` — `node: 7` with 2 marks → LowConfidence, zero gestures
    *   `som_xyAnswerInMarksMode_isRefused` — provider answers `{"target_found":true,"x":0.3,"y":0.4,"confidence":0.9}` with marks present → not-found → LowConfidence, zero gestures
    *   `som_nodeAnswerWithoutDims_isNotFound` — screenDimensions → null → not-found
    *   `som_criticalLabelWithoutConsent_haltsConsentRequired` — mark labeled "Delete account" → ConsentRequired, zero gestures
    *   `som_criticalLabelWithConsentGate_dispatchesOnce` — one-shot gate: a wrong-label critical call does NOT consume the gate (stays armed); the first call with a label EQUAL to "Delete account" consumes it; a second equal-label critical step in the same run halts
    *   `som_criticalLabelConsentGateWrongLabel_stillHalts` — gate approves only "Pay" but target is "Delete account" → ConsentRequired
    *   `som_unlabeledMark_alwaysConsentRequired_evenWithGate` — mark.label = null → ConsentRequired regardless of consentGate
    *   `som_emptyMarks_fallsBackToBitmapCapture` — somMarks → empty; screenCapture returns fake bitmap; provider answers x/y → gesture dispatched, capture count == 1
    *   `drive_verifierParam_goalReachedAfterFirstStep` — drive(goal, verifier = { true }) with one located step → GoalReached with 1 step
    *   `drive_maxStepsParam_boundsTheRun` — maxSteps = 2 with a never-satisfied goal → MaxStepsExceeded with exactly 2 steps
    *   `drive_maxStepsParam_clampedToEngineMax` — maxSteps = 99 behaves as MAX_STEPS

#### 13. `app/src/test/java/com/tsfdroid/ai/actions/DriveScreenGoalActionTest.kt` (Create — TDD RED, Checkpoint 3)

*   **Type / Scope**: Unit tests constructing
    `AdvancedControlActions(VisionDrivingLoop(VisionEngine(fakeFactory), fakeFactory), VisionGoalVerifier(VisionEngine(fakeFactory), fakeFactory))`
    (same fake-`LLMProviderFactory` construction pattern as
    `VisionDrivingLoopTest` / `VisionTargetTapTest`) and driving
    `getActions().first { it.name == "DRIVE_SCREEN_GOAL" }`.
*   **Proposed Changes** (exact cases):
    *   `missingGoal_failsWithParamError`
    *   `missingExpectedText_failsWithParamError`
    *   `expectedAlreadyOnScreen_returnsSuccessZeroSteps` — baseline seam returns text containing expected
    *   `goalReached_returnsSuccessMessage`
    *   `consentRequiredLabeled_returnsNeedsInputWithChipsAndConsentParam` — metadata["param"] == "consent", options contain "Yes, tap it"
    *   `consentApprovalReRun_tapsApprovedLabelOnce` — run 1 NeedsInput; run 2 with `consent = "Yes, tap it"` → gate honored; a third critical same-label tap in the run halts again
    *   `consentApprovalReRun_carriesGestureBudget` — run 1 spends 2 steps then halts; run 2's drive is invoked with maxSteps = 3 (verified via a maxSteps-observing fake loop seam or step count)
    *   `consentDecline_failsHonestlyWithoutReDrive` — `consent = "No, stop driving"` → Failure mentioning decline, loop not invoked, pending cleared
    *   `notAllow_isNotApproval_declines` — `consent = "not allow"` → Failure
    *   `neverApprove_isNotApproval_declines` — `consent = "never approve this"` → Failure
    *   `freeTextNonAffirmative_declines` — `consent = "sure"` → Failure (conservative)
    *   `staleConsentDifferentGoal_doesNotApprove` — pending goal "A", execute with goal "B" + approval → ConsentRequired re-ask (NeedsInput), no gate
    *   `staleConsentExpiredTtl_doesNotApprove` — pendingConsentAt older than TTL (fake clock seam) → NeedsInput again
    *   `unlabeledConsentHalt_returnsUserActionRequired` — loop returns ConsentRequired with "(unlabeled target)" → UserActionRequired (not NeedsInput)
    *   `haltedResult_mapsToFailureWithReason`
    *   `isApproval_exactChip_isApproval` / `isApproval_anchoredYes_isApproval` / `isApproval_negationTokens_areNotApproval` — direct companion-function cases

#### 14. `app/src/test/java/com/tsfdroid/ai/core/agent/GoalContractTest.kt` (Modify — TDD RED, Checkpoint 3)

*   **Type / Scope**: Additive case for the consent passthrough.
*   **Proposed Changes**: `humanizeParamPrompt_consentPassesQuestionThrough`
    — `humanizeParamPrompt("DRIVE_SCREEN_GOAL", "consent", "Screen driving needs your approval before it taps 'Delete account'. Continue?")`
    returns the question verbatim (contains "Delete account").

#### 15. `app/src/test/java/com/tsfdroid/ai/core/agent/AutoApprovalPolicyTest.kt` + `app/src/test/java/com/tsfdroid/ai/core/agent/ActionSequenceExecutorTest.kt` (Modify — TDD RED, Checkpoint 3)

*   **Proposed Changes** (exact cases):
    *   `driveScreenGoal_isPolicyCriticalAction`
    *   `planWithDriveScreenGoal_neverAutoApproves` — AUTO and YOLO with a granted set containing it → false
    *   `consentBoundary_backgroundMacroWithDriveScreenGoalIsRefused` (in the executor test) — a sequence step `DRIVE_SCREEN_GOAL` is refused with the existing policy-critical message and nothing executes

#### 16. `app/src/androidTest/java/com/tsfdroid/ai/e2e/DriveScreenGoalHermeticTest.kt` (Create — Phase B, hermetic gate)

*   **Type / Scope**: Instrumentation test for the hermetic E2E job.
*   **Proposed Changes** (exact case):
    *   `drivesNowhereWithoutAccessibility_honestHalt` — with no
        accessibility service bound (hermetic emulator default), the action
        returns a Failure whose message contains "halt" (case-insensitive)
        and the app does not crash.

#### 17. `app/src/test/java/com/tsfdroid/ai/core/storage/StorageWorkspaceProviderTest.kt` (Modify — Checkpoint 3, mechanical)

*   **Type / Scope**: The ONLY existing test that constructs
    `AdvancedControlActions()` directly (line ~133). The new constructor
    parameters break its compilation; this file updates the construction.
*   **Proposed Changes**: replace `AdvancedControlActions()` with
    `AdvancedControlActions(VisionDrivingLoop(VisionEngine(neverFactory), neverFactory), VisionGoalVerifier(VisionEngine(neverFactory), neverFactory))`
    where `neverFactory` is a tiny local fake `LLMProviderFactory` whose
    `getActiveProvider()` throws (this test only exercises file actions;
    the drive path is never resolved). No assertions change.

#### 18. `FEATURE_STATUS.md` (Modify — Phase B, after gates pass)

*   Move "Continuous vision driving loop (5.5)" from "engines proven, CEO
    wiring pending" to "Verified Implemented" with the new evidence lines
    (unit suites + hermetic case), note the SoM fast path and the consent
    question flow, AND correct the stale claim on line ~154
    (`tapLocatedTarget` has no production caller — it never did; this spec
    fixed the same inherited error in its own Problem statement).

## Verification Plan

*   **Compilation/Build**: `./gradlew assembleDebug` after each checkpoint —
    zero warnings in modified files.
*   **Unit Tests**: `./gradlew testDebugUnitTest` — all 1,143 existing
    (with the one mechanical File-17 update) + ~45 new tests pass,
    0 failures, 0 errors.
*   **Static Analysis & Linting**: `./gradlew lintDebug` at the final
    acceptance gate of this v2.0.0 arc (Milestone 8), not per-milestone.
*   **E2E/Integration**: hermetic E2E job (CI) incl. the new
    `DriveScreenGoalHermeticTest`.
*   **Drift**: `/egm-drift-report` (the EGM drift-detection skill, executed
    per its SKILL.md) comparing this spec against the modified files →
    "No drift detected" appended to `docs/designs/egm-artifacts/DRIFT_REPORT.md`.

--------------------------------------------------------------------------------

## Resolve Log (Goldfish round 1 → rev 2)

*   **Blocker (compile break)**: `StorageWorkspaceProviderTest.kt:133`
    constructs `AdvancedControlActions()` → added Target File 17.
*   **Blocker (consent lexicon)**: substring `isApproval` counted "not
    allow"/"never approve" as consent → token-based conservative logic
    (negation tokens reject; first-token affirmative required) + decline
    branch + 5 test cases.
*   **Blocker (question copy)**: `humanizeParamPrompt` would render "What
    should I use for 'consent'?" → Target File 8 passthrough + GoalContract
    test.
*   **Major (ungrounded x/y in marks mode)** → x/y REFUSED in marks mode
    (node-or-not-found); prompt and tests updated.
*   **Major (verifier YAGNI + capture reinvention)** → `expectedScreenText`
    now REQUIRED; the no-expected LLM-judgment branch deleted; vision
    fallback (empty tree only) reuses `VisionEngine.captureScreen()`
    (FLAG_SECURE-aware); `providerAccess` seam added for tests.
*   **Major (missing Alternative E)** → added.
*   **Major (settle barrier)** → `gestureDispatch` default now routes
    through `GenericAppAutomator.clickCoordinates` (`awaitUiIdle`).
*   **Major (one approval → 5 taps)** → one-shot gate (single critical tap
    per approval).
*   **Major (stale approval)** → goal binding + 10-min TTL + gesture-budget
    carry (`gesturesSpent`, `maxSteps` param, total ≤ 5 per invocation).
*   **Major (verifier false positive)** → baseline pre-check: expected
    text already visible → immediate Success(0 steps), else drive with
    `expectedAbsentFromBaseline = true`.
*   **Major (false premise)** → Problem rewritten: neither vision tap path
    is wired; production taps are tree-based CLICK_* actions.
*   **Major (locale gap, truncation, budget honesty, no Risks section)** →
    Risks & Limitations section added with accepted-risk rationale.
*   **Major (decline loop)** → explicit decline branch (no re-drive).
*   **Minor/Nit batch**: `nodeTraversal` field name + root recycle; node
    valid + dims null → not-found; `MarkKind.LINK` removed; NFR wording
    (25 marks, not nodes; verifier-perf qualification); EGM phase/CEO/
    HarnessLoop/AUTO-YOLO glosses; free-tier latency attribution; heading
    levels; "honestly" repetition trimmed; `drive_consentGateParam_overridesNoConsent`
    contradictory case removed (replaced by explicit param-driven cases);
    phasing checkpoints defined.

*Drafted with EGM*
