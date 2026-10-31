# Milestone 1 Concept Brief — Wire VisionDrivingLoop + Set-of-Mark (SoM) Grounding

*   **Author:** Principal Android Systems Architect (Elephant)
*   **Status:** Proposed (EGM Phase A, design-only mode)
*   **Date:** 2026-10-31
*   **Artifact set:** `milestone_1_brief.md` (this file) → `docs/designs/milestone_1_vision-driving-wiring.md`

## Problem

`VisionDrivingLoop` (ticket 5.5, v1.8.0-rc.1) is a verified engine with no
production caller: `AgentLoop` still executes the one-shot
`VisionEngine.tapLocatedTarget`, the loop's `goalVerifier` defaults to
`{ false }` (fail-closed by design), and every locate costs a full
screenshot + vision round-trip — slow and brittle on free-tier providers.

Three concrete gaps:

1. **No grounding fast path.** Interactive on-screen elements are already
   described by the accessibility tree (`AccessibilityNodeInfo` bounds, labels,
   clickability), yet the locator only ever sees a base64 bitmap. A numbered
   Set-of-Mark list would let even a text-only free model pick
   `[3] Button "Search"` deterministically — no pixels, no guessing.
2. **No real goal verifier.** The loop can never claim `GoalReached` in
   production; every drive ends in `MaxStepsExceeded` — honest but useless.
3. **No action surface.** Nothing in `ActionSchema` / `AdvancedControlActions`
   exposes the loop, so no plan can use it, and there is no path that turns
   `VisionDriveResult.ConsentRequired` into an interactive user confirmation.

## Proposed Direction (agreed scope)

- **SoM hybrid grounding**: when the accessibility tree yields interactive
  marks, the locator prompt carries the numbered mark list (text-only round
  trip; no screenshot); the model answers either a `node` id (resolved to the
  mark's deterministic center) or fractional `x`/`y` + `label` for
  custom-rendered views. Empty mark list → existing bitmap path, unchanged.
- **Production goal verifier**: `VisionGoalVerifier` checks the post-settle
  accessibility text tree against `expectedScreenText` (deterministic
  contains-check); falls back to a bounded single LLM judgment (screen text,
  or screenshot only when the tree is empty). Unparsable/failed judgments are
  `false` — never a guessed success.
- **Action + consent wiring**: `DRIVE_SCREEN_GOAL` action (params `goal`,
  optional `expectedScreenText`) classified `POLICY_CRITICAL` in
  `AutoApprovalPolicy` (defense in depth with the loop's in-flight consent
  gate); `ConsentRequired` maps to `ActionResult.NeedsInput`, which rides the
  existing `AgentLoop.resolveNeedsInput` → `askUserQuestion`-surface →
  answer-injected re-execution loop, with the approval bound to the exact
  halted label and single-use.

## Non-Goals

- No `AgentLoop` planner-prompt changes beyond the new action being visible
  in the schema (planner picks it like any ADVANCED action).
- No Room schema changes, no new permissions, no new dependencies.
- No vision-model swap; the free-tier provider seam is untouched.

## Constraints (from invariants)

- Consent boundary: `DRIVE_SCREEN_GOAL` enters `POLICY_CRITICAL_ACTIONS`;
  macros / MCP / sub-agents / background paths refuse it automatically
  because they already gate on that set.
- Privacy: the SoM path captures NO screenshot at all (strict improvement);
  the bitmap path keeps in-memory + immediate recycle.
- Existing 12 `VisionDrivingLoopTest` tests stay green (drive() signature
  change is additive with defaults).
