# TSF Droid Feature Status

This file is the honest, evidence-backed inventory of what TSF Droid
`1.7.0-rc.1` actually is. Every claim below maps to code, tests, and CI
evidence. Anything not listed as **Verified Implemented** is either partial
or absent — no marketing language, no vaporware.

Evidence legend:
- **unit** = `app/src/test` suite (1,074 tests, green in Remote CI on the
  release commit)
- **hermetic E2E** = emulator + FakeLLMProvider, deterministic, required
  release gate (`.github/workflows/e2e-emulator.yml`, job `hermetic-e2e`)
- **live E2E** = emulator + keyless OpenCode Zen free tier, informational
  job — honest but flaky by provider design

Last updated: 2026-10-11, for the 1.7.0-rc.1 release candidate.

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

### Privacy
- Auto-reply revocation: a notification arriving from the user ("You: …")
  cancels the pending auto-reply; the global / per-app / per-contact three-layer
  switches are re-checked immediately before dispatch. (Unit:
  `AutoReplyEngine` revocation tests.)
- All user payloads in logs go through `CrashLogRedactor`; provider error
  bodies are redacted before logging or UI surfacing (`RedactedDetail`).

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

### E2E infrastructure
- `FakeLLMProvider` + `HermeticTestProviderFactory` — deterministic,
  network-free agent-pipeline tests on a real emulator.
- The hermetic E2E gate is a **standalone required CI job**: app launch,
  onboarding journey (name entry with multi-attempt IME discipline), dashboard
  chrome, tab navigation, new-chat composer, accessibility
  targeting/scrape/readiness, keystore crypto — all without any provider.
- Live-provider E2E is a separate informational job with honest verdict
  classification (PASS / RATE_LIMITED / FAIL / FAIL_TIMEOUT) backed by
  measured evidence (provider pump latency distribution, starvation-failure
  signature; no fake greens, no guessed rate limits).
- 1,074 unit tests, green on every commit of the 1.6.0 → 1.7.0-rc.1 arc.

---

## Partially Implemented

- **Multi-agent orchestration**: a CEO loop delegates bounded, circuit-broken
  sub-agent calls (`SubAgentRouter.research` / `researchSafely`), but this is
  sequential delegation, NOT a concurrent task graph. No `SubAgentTask`
  dependency DAG, no parallel execution with branch cancellation, no role
  scoped tool budgets. (God-Tier Phase 2 target: `SubAgentGraph`.)
- **Vision computer use (continuous)**: one-shot grounding to a tapped
  coordinate exists; a bounded multi-step vision driving loop (max steps,
  per-step confidence, dangerous-target approval) does not.
- **Proactive background intelligence**: deterministic scheduling exists
  (`MacroSchedulerWorker`), but there is no opt-in background LLM suggestion
  worker with battery/idle constraints. What exists schedules the user's own
  recorded macros; it does not generate recommendations.
- **Hermetic UI chat flow**: the agent pipeline is hermetically tested at the
  component seam level, and the UI journey (onboarding → dashboard → new
  chat) is hermetically tested end-to-end, but the full-UI chat conversation
  with a fake provider requires a test DI harness that does not exist yet;
  full-UI chat coverage is live-E2E only.

## Not Implemented

- **Canvas workspace UI** — no split artifact/conversation workspace exists.
- **Vector / embedding semantic memory** — no embedding generation, no
  vector storage, no similarity recall. (The only "embedding" code is a
  filter that EXCLUDES embedding endpoints from the chat model list.)
- **Full autonomous multi-agent swarm execution** — see Partially
  Implemented above; sequential delegation only.

---

## Known Limitations (honest)

- Live E2E depends on the OpenCode Zen free tier, which throttles under
  load (measured: single streaming turns stretching from ~3s median to
  60–90s; failures rotate between runs and pass on healthy days). This is
  why the live gate is informational and the deterministic hermetic gate
  is the release truth.
- Remote CI runners are pinned to `ubuntu-24.04`; the `ubuntu-latest`
  migration to Ubuntu 26 (2026-10-19) is deliberately excluded from this
  release's verification surface.
- Some pinned GitHub Actions (`actions/setup-java@v4`,
  `android-actions/setup-android@v3`) emit Node 20 deprecation warnings;
  they are cosmetic and will be upgraded after the RC.
