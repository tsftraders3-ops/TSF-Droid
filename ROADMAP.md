# TSF Droid Roadmap

Living document. Reflects the state of `tsftraders3-ops/TSF-Droid` (a fork of
`JMAN730/opendroid` ← `yashab-cyber/opendroid`) as of **2026-10-01**, shipping
version **1.3.0** (`versionCode 12`).

---

## Where we are

| Dimension | State |
|---|---|
| **App** | Production-ready autonomous Android AI agent: OpenCode-style multi-call tool harness, Chat/Agent dual modes, ask_user round-trip, per-model reasoning effort, markdown answers with clickable source chips, Hermes personal memory with 75% auto-compaction, 4-tier knowledge graph, AI social media management across 7 platforms, habit/routine detection with a live cron macro scheduler, on-device LiteRT-LM inference, and a localhost MCP server |
| **SDK** | `minSdk 26`, `compileSdk`/`targetSdk 36`, JDK 21 (pinned) |
| **Toolchain** | Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.0 (AGP built-in Kotlin), Retrofit 3.0.0, OkHttp 5.4.0 |
| **Database** | Room DB v14 with explicit sequential migrations (`MIGRATION_1_2` through `MIGRATION_13_14`) |
| **Tests** | ~790 JVM unit tests across 84 test classes (agent harness, LLM providers, SSE streaming, bounded fetches, memory, security, macro cron) plus a 40-test instrumented E2E suite (10 classes) that runs against the live keyless OpenCode Zen endpoint on an API-34 emulator |
| **CI** | 3 workflows — `build-and-test` (unit tests + assembleDebug + lint), `e2e-emulator` (the 40-test suite with one clean retry pass on failure), `release` (signed R8 APK + debug APK + SHA256SUMS on tag) |
| **Distribution** | GitHub Releases with 4-part signing secrets (keystore base64, store/key passwords, alias); FOSS packaging |

---

## Recently Shipped

### v1.3.0 — Core agent experience: ask_user, real effort levels, rich answers & the memory identity fix

- **ask_user**: the model can ask the user a question in both modes — option
  chips + free text, a parked ANSWER NEEDED surface, and the answer returns as
  the tool result so the turn continues with it in context.
- **Effort levels everywhere**: all 8 OpenAI-compatible providers send
  `reasoning_effort`, gated on the model's models.dev registry levels.
- **Rich answers**: markdown-lite rendering, every URL clickable, SOURCES chip
  rows, "THOUGHT FOR Xs" headers.
- **The Farhan fix**: deterministic naming detection runs before the memory
  extractor; assistant identity renders in its own section; a self-heal pass
  purges misfiled rows from before the fix.
- **Context that carries**: agent planning sees recent history (bounded to
  25% of the model window); the chat window is token-aware (60% of the real
  context window, 200-message ceiling, image token weights).
- **Honesty sweep**: REAL SSE streaming for every cloud provider, the Gemini
  nano mock removed, the social scheduler actually scheduled, bounded HTTP
  fetches and plan steps (ASK_USER deliberately unbounded), OpenDroid branding
  remnants replaced.
- Room v13 → v14 (ask options column), E2E suite extended.

### v1.2.1 — Hermes loop: personal memory, context compaction, visible work & todo list

- Per-message context injection with 75% automatic OpenCode-style compaction.
- The visible ACTIVITY trace renders inside the streaming reply bubble and
  persists on every save path (including the malformed-stream recovery path
  that research turns take).
- Stream bounds: 120s idle / 480s per-call / 900s per-turn, per-line
  cancellation in every pump.
- 20/20 E2E green in a single pass against the live keyless Zen endpoint.

### v1.2.0 — The OpenCode-grade harness

- Multi-call tool loop (10 harness rounds, 32k output ceiling with automatic
  continuation, doom-loop guard), Chat/Agent modes fully separated, no output
  caps, file uploads (image/PDF/text) end to end.

### v1.1.1 — Agentic quality & reliability (field failures root-caused)

### v1.1.0 — Aurora: the prototype design system, implemented

### v1.0.5–v1.0.7 — Field-failure fixes, habit/routine detection, AI social media management

Earlier history lives in `CHANGELOG.md`.

---

## What's deliberately NOT in v1.3.0 (the honest list)

- **FB/YouTube/LinkedIn inbox + comments adapters are sandbox-mode only** —
  real-API method bodies for those three platforms' engagement surfaces are
  still stubs (posting works via their APIs; fetching comments does not).
- **Cron granularity is 15 minutes** — the MacroSchedulerWorker rides
  WorkManager's 15-minute minimum period; `*/5` expressions fire every 15.
- **On-device models plan without history** — the 4k-context-class models keep
  the no-history behavior to avoid overflow.
- **The `website/` folder is still upstream OpenDroid's marketing site** —
  kept for reference, not deployed for TSF.

---

## Next (candidates, unstarted)

- Real engagement adapters for FB/YouTube/LinkedIn (close the last sandbox-only
  surfaces behind feature flags).
- A settings surface for the macro scheduler (see next-run times, run history,
  manual "run now").
- Per-provider vision routing beyond the Zen chain (multi-modal pins for
  OpenRouter/Custom endpoints).
- Play Store listing assets (screenshots at the Aurora design system's
  best).
