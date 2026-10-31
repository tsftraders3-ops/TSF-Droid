# TSF Droid Feature Status

This file is the honest, evidence-backed inventory of what TSF Droid
`1.8.0-rc.1` actually is. Every claim below maps to code, tests, and CI
evidence. Anything not listed as **Verified Implemented** is either partial
or absent — no marketing language, no vaporware.

Evidence legend:
- **unit** = `app/src/test` suite (1,143 tests, green locally on the
  release branch; Remote CI re-runs it on every push)
- **hermetic E2E** = emulator + FakeLLMProvider, deterministic, required
  release gate (`.github/workflows/e2e-emulator.yml`, job `hermetic-e2e`)
- **live E2E** = emulator + keyless OpenCode Zen free tier, informational
  job — honest but flaky by provider design

Last updated: 2026-10-11, for the 1.8.0-rc.1 release candidate (God-Tier
Phase 2 architecture complete).

---

## Verified Implemented

### Safety (the consent boundary)
- Policy-critical actions (`SEND_SMS`, `CALL_PHONE`, `SEND_MONEY`, contacts
  writes, …) are refused on every non-interactive dispatch path — macro
  scheduling, MCP tool calls, and background plan execution — and require
  explicit interactive confirmation. (`AutoApprovalPolicy.POLICY_CRITICAL_ACTIONS`
  + `neverAutoApprove`; unit + hermetic E2E:
  `consentBoundary_backgroundMacroWithSendSmsIsRefused`.)
- The macro path cannot silently execute a critical action embedded mid-plan
  (`ActionSequenceExecutor` refusal, hermetic E2E).
- God-Tier Phase 2 additions extend the same boundary:
  - `SubAgentGraph` refuses tasks whose toolScope declares a policy-critical
    action BEFORE the specialist runs, and refuses EXECUTOR drafts that
    smuggle a policy-critical action name AFTER the run (unit:
    `SubAgentGraphTest`, scope + scrub-gate tests). The graph has no
    dispatch path at all.
  - `ProactiveSuggestionWorker` has no dispatch path (no ActionDispatcher
    anywhere in `core/proactive`) and discards any suggestion carrying a
    policy-critical action before persistence (unit:
    `ProactiveWorkerTest`).
  - `VisionDrivingLoop`'s consent gate FAILS CLOSED: a located target whose
    label is policy-critical (Delete / Pay / Send / …) halts the loop with
    `ConsentRequired` before the gesture, and so does an UNLABELED target —
    an uninspectable coordinate tap is exactly the class
    `AutoApprovalPolicy` holds policy-critical (unit:
    `VisionDrivingLoopTest`).

### Privacy
- Auto-reply revocation: a notification arriving from the user ("You: …")
  cancels the pending auto-reply; the global / per-app / per-contact three-layer
  switches are re-checked immediately before dispatch. (Unit:
  `AutoReplyEngine` revocation tests.)
- All user payloads in logs go through `CrashLogRedactor`; provider error
  bodies are redacted before logging or UI surfacing (`RedactedDetail`).
- God-Tier Phase 2: the vision driving loop's screenshots exist only as
  in-memory `Bitmap`s, recycled immediately after each vision call, before
  any gesture — zero disk I/O in the class (grep-verified; recycling
  asserted on every exit path including timeout, unit:
  `VisionDrivingLoopTest`).

### Vision computer use (single shot)
- Screenshot → structured target JSON (`target_found`, `x`, `y`,
  `confidence`) → confidence gate (> 0.85) → `CLICK_COORDINATES` →
  `dispatchGesture()`. Low confidence never dispatches a gesture (hermetic
  E2E: `visionTapGate_lowConfidenceNeverDispatches`; unit:
  `VisionEngine` grounding tests).

### Agent core
- Sub-agent delegation: `SubAgentRouter` with a per-specialist circuit
  breaker (timeout kills the specialist, CEO proceeds without the brief),
  token budgets (over-budget briefs discarded), and bounded prompts.
  (Hermetic E2E: 3 tests; unit: router suite.)
- Plan goal restoration: the user's own words are the plan goal verbatim —
  a model paraphrase can no longer weaken the artifact signal (the b3
  regression; unit: `PlanGoalRestorationTest`, 5 tests).
- Plan deferral, synthesis dump-guard, answer hygiene, honesty-claim audit,
  file-collision rename guard (unit suites replaying verbatim field
  evidence; live E2E: FieldFixes suite green since the fixes).

### God-Tier Phase 2 — Vector memory (5.1)
- Room v17 `memory_embeddings` storage + `EmbeddingProvider` seam
  (`LocalHashEmbeddingProvider`: FNV-1a hashed bag-of-words, L2-normalized,
  honest lexical similarity), cosine-ranked top-K recall with floor + TTL +
  dims/model guards, forget removes both rows. (Unit: 24 tests;
  migration 16→17 validated against the exported schema.)

### God-Tier Phase 2 — Canvas workspace (5.2)
- Adaptive split workspace: wide layouts (≥ 720dp) render chat + artifact
  panes side by side; compact layouts keep the artifact pane OUT of the
  tree until the user opens it (open/close affordances, durable state).
  Plans flow live onto the canvas via `CanvasPlanPublisher` →
  `CanvasArtifactBus`; the artifact viewer renders plans (step statuses),
  titled prose, and file previews.
- Real navigation: `CANVAS` route in the production NavHost, reachable from
  the dashboard's More sheet; compact mode has its own title + back bar.
  (Unit: `CanvasViewModelTest` 5, `CanvasLayoutRobolectricTest` 2 — the
  `requiredWidth`-overrides-window technique is proven locally, not just
  CI-hoped; instrumentation: `CanvasUiTest` 8 in the hermetic gate;
  hermetic journey stage 6 drives More → Canvas → back on a real emulator.)
- Honest gaps: `TextArtifact`/`FileArtifact` have no production producer
  yet (only plans flow onto the bus); the default chat pane is the real
  ChatScreen but there is no canvas-specific composer behavior.

### God-Tier Phase 2 — Proactive background intelligence (5.4)
- `ProactiveSuggestionWorker` (CoroutineWorker): local pattern analysis
  (recent chat counts, learned memory counts) → deterministic template
  suggestions → Room v18 `proactive_suggestions`. Ships OFF; the Settings
  toggle is the only scheduling surface; constraints
  `requiresBatteryNotLow` + `requiresDeviceIdle` are load-bearing and
  tested (WorkManagerTestInitHelper: with constraints unmet the worker
  stays ENQUEUED and persists nothing; enabled-and-met runs persist).
- Honest gaps: no reading surface exists yet — suggestions accumulate in
  Room with no screen or notification rendering them (the DAO is ready);
  the engine is deliberately template-based (no LLM in the background).

### God-Tier Phase 2 / EGM v2.0.0 arc — Continuous vision driving loop, WIRED (5.5 + Milestone 1)
- `DRIVE_SCREEN_GOAL` (ADVANCED, schema-validated): the bounded driving
  loop is now a plan action. Set-of-Mark fast path — when the accessibility
  tree yields interactive marks, every locate step is TEXT-ONLY (zero
  screenshot captures; the model picks a numbered mark, the mark's bounds
  ground the gesture); empty trees keep the unchanged in-memory bitmap
  path (immediate recycle, fail-closed unlabeled-target consent).
- Production goal verifier: `VisionGoalVerifier` — deterministic
  contains-check against the post-settle tree first, vision fallback ONLY
  on an empty tree, strict `{"goal_reached": bool}` contract,
  FLAG_SECURE-aware; unparsable/failed → false (never a guessed success).
  The action refuses to run without `expectedScreenText` (verify-or-fail)
  and pre-checks the baseline (expected text already visible → Success,
  0 steps).
- Consent ask-loop: mid-drive `ConsentRequired` halts surface as
  interactive `NeedsInput` questions (chips "Yes, tap it" / "No, stop
  driving") riding the existing answer-injection machinery. Approvals are
  one-shot, label-bound, goal-bound, 10-min TTL, gesture-budget-bound
  (≤ 5 taps per invocation); unlabeled targets are never pre-approvable
  (`UserActionRequired`); non-affirmative answers are explicit declines
  (conservative token logic — "not allow"/"never approve"/"sure" all
  decline). `DRIVE_SCREEN_GOAL` is in `POLICY_CRITICAL_ACTIONS` (plan gate
  + macro/MCP/sub-agent refusal; unit: consent boundary tests).
  (Unit: `VisionScreenMarkTest` 5, `VisionGoalVerifierTest` 9,
  `VisionDrivingLoopTest` 24 (12 engine + 12 SoM), `DriveScreenGoalActionTest`
  18; hermetic: `DriveScreenGoalHermeticTest` — honest halt with no
  accessibility service.)

### E2E infrastructure
- `FakeLLMProvider` + `HermeticTestProviderFactory` — deterministic,
  network-free agent-pipeline tests on a real emulator.
- The hermetic E2E gate is a **standalone required CI job**: app launch,
  onboarding journey (name entry with multi-attempt IME discipline), dashboard
  chrome, tab navigation, new-chat composer, Canvas navigation, accessibility
  targeting/scrape/readiness, keystore crypto — all without any provider.
- Live-provider E2E is a separate informational job with honest verdict
  classification (PASS / RATE_LIMITED / FAIL / FAIL_TIMEOUT) backed by
  measured evidence (provider pump latency distribution, starvation-failure
  signature; no fake greens, no guessed rate limits).
- 1,191 unit tests green on the feature branch (1,143 at v1.8.0-rc.1 + 48
  added by the EGM v2.0.0 arc Milestone 1: 12 SoM loop additions, 9 goal
  verifier, 5 SoM model, 18 drive action, 2 policy gate, 1 executor consent
  boundary, 1 GoalContract consent passthrough).

---

## Verified capabilities — engines proven, CEO wiring pending

These are REAL, fully tested subsystems with NO production caller yet. They
are not vaporware (the tests prove real behavior at the engine level), and
they are not "shipped features" (nothing in the app invokes them yet). The
follow-up is wiring them into `AgentLoop`:

- **Multi-agent DAG swarm (5.3)** — `SubAgentGraph`: topological
  validation, concurrent async execution (siblings start at the same
  instant — a sequential implementation would fail the test), dependency
  awaiting with parent-output context, per-task timeout kill with graceful
  degradation (dependents skipped, independent branches unaffected), and the
  two safety gates above. Unit: `SubAgentGraphTest` (12). What is missing:
  nothing in `AgentLoop` builds task graphs — the CEO still does sequential
  `researchSafely` → `draftSafely` delegation. The planner integration is
  the next arc.
- **Continuous vision driving loop (5.5)** — moved to "Verified
  Implemented" above: the EGM v2.0.0 Milestone 1 wired it end to end
  (`DRIVE_SCREEN_GOAL` + SoM grounding + the production goal verifier +
  the consent ask-loop). The one-shot `VisionEngine.tapLocatedTarget`
  remains engine-level (it never had a production caller — the earlier
  claim here that "AgentLoop uses it" was wrong; production taps were
  always tree-based `CLICK_*` actions).

## Not Implemented

- **Autonomous multi-agent swarm EXECUTION** — the engine exists (above);
  the orchestration does not. Sequential delegation remains the CEO's only
  multi-agent path today.
- **Proactive suggestion reading surface** — worker + storage are real;
  no UI or notification renders the stored suggestions yet.

---

## Known Limitations (honest)

- Live E2E depends on the OpenCode Zen free tier, which throttles under
  load (measured: single streaming turns stretching from ~3s median to
  60–90s; failures rotate between runs and pass on healthy days). This is
  why the live gate is informational and the deterministic hermetic gate
  is the release truth.
- The vision driving loop's 30s total budget accommodates roughly 2–5
  vision round-trips on a real provider — "exactly 5 steps" is reachable
  only with fast providers; the BOUNDS (never infinite, never uninspected)
  are the safety property.
- The swarm's output scrub gate matches policy-critical action NAMES in
  EXECUTOR drafts — a determined prompt injection could phrase an action
  differently. Defense in depth for this is the CEO-side AutoApprovalPolicy
  gate that the wiring must pass through; until wired, nothing executes
  swarm drafts at all.
- LocalHashEmbeddingProvider is lexical (bag-of-words FNV-1a), not a
  semantic model — honest lexical similarity only; swapping in a real
  embedding model is a provider-seam change away.
- Remote CI runners are pinned to `ubuntu-24.04`; the `ubuntu-latest`
  migration to Ubuntu 26 (2026-10-19) is deliberately excluded from this
  release's verification surface.
- Some pinned GitHub Actions (`actions/setup-java@v4`,
  `android-actions/setup-android@v3`) emit Node 20 deprecation warnings;
  they are cosmetic and will be upgraded after the RC.
