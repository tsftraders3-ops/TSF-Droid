## TSF Droid v1.7.0-rc.1 — Stabilization, Safety, E2E Hardening


A release CANDIDATE: the audit-remediation arc (consent boundary, auto-reply
revocation, privacy redaction, vision grounding, sub-agent delegation,
goal restoration) plus the E2E pipeline restructure that separates the
deterministic release truth from the flaky free tier. This is NOT the
God-Tier release — see `FEATURE_STATUS.md` for the honest inventory of what
is missing (Canvas workspace, vector memory, true swarm, proactive worker).

### The audit-remediation arc (all verified: unit + E2E evidence)

- **Security (C-01)**: the consent boundary is enforced on every
  non-interactive dispatch path. Policy-critical actions (SEND_SMS, calls,
  contacts writes, …) inside macros, MCP tool calls, and background plan
  execution are refused and require interactive confirmation.
- **Privacy + reliability (M-05/M-06)**: a notification from the user
  cancels the pending auto-reply; the three-layer switches (global / per-app
  / per-contact) are re-checked at dispatch time. All user payloads in logs
  are redacted (`CrashLogRedactor`).
- **Vision (M-02)**: screenshot grounding returns structured
  `{target_found, x, y, confidence}`; only confidence > 0.85 dispatches a
  gesture tap. Low confidence never taps.
- **Orchestration (M-01)**: the CEO delegates to circuit-breaked, token-
  budgeted sub-agents; a timed-out or over-budget specialist is a missing
  brief, never a crash.
- **b3 goal restoration**: the plan's goal is the user's own words,
  verbatim — the live planner's paraphrase (which had silently downgraded
  "create a markdown file called X" into a price lookup) can no longer
  weaken the artifact signal. 5 unit tests replay the regression.
- **b6/onboarding stability**: the multi-paragraph answer is read in full;
  onboarding completes through the multi-attempt IME discipline that
  survived the worst free-tier days.

### E2E pipeline restructure (the run-37770800170 lesson)

Run 37770800170 exposed a structural flaw: one CI job held BOTH gates, so
when the free tier throttled (measured: streaming turns stretching from
~3s median to 60–90s, starvation failures rotating between attempts) the
whole run went red and the GREEN hermetic gate read as "blocked" to the
world. The pipeline is now:

- **hermetic-e2e (required release gate)** — its own CI job, parallel to
  the live job: app launch, the deterministic onboarding → dashboard →
  tabs → new-chat journey (`HermeticJourneyInstrumentedTest`, new),
  FakeLLMProvider agent-pipeline gates (circuit breaker, vision gate,
  consent boundary), accessibility targeting, keystore crypto. ~20 minutes,
  zero provider dependency, this job alone decides green.
- **live-e2e (informational)** — `continue-on-error`, honest verdict
  classification: `RATE_LIMITED` is claimed only with MEASURED degradation
  (pump latency distribution, starvation signatures — never a guessed
  "429 count", which this arc proved counts logcat timestamp milliseconds),
  and `FAIL` without degradation evidence means investigate-as-regression.
  Both attempts always preserved as artifacts.
- **e2e-verdict** — writes the combined summary block and re-fails the
  workflow if the hermetic gate is anything but PASS.
- Runners pinned to `ubuntu-24.04` (the 2026-10-19 `ubuntu-latest` → 26
  migration is excluded from this release's verification surface).

### Test evidence

- Unit: 1,074 tests, 0 failures (Remote CI, green on every commit of the
  arc).
- Hermetic E2E: 20/20 on every run of the arc (deterministic).
- Live E2E: 34/34 achieved on healthy-provider runs (run 37731897885
  retry); residual red on throttled days is classified `RATE_LIMITED` with
  measured evidence, never hidden, never blocking.

### Known limitations

- Live E2E depends on the OpenCode Zen free tier and throttles under load;
  the deterministic hermetic gate is the release truth.
- Full-UI hermetic chat (fake provider behind the real UI) needs a test DI
  harness — not in this RC.
- Node-20 deprecation warnings from pinned actions versions (cosmetic;
  upgrade after the RC).

