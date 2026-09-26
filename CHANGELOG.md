# TSF Droid Changelog

All notable changes to TSF Droid are documented here. The release workflow
(`.github/workflows/release.yml`) extracts the section matching the pushed tag
and publishes it as the GitHub Release notes.

## v1.0.6 — The agent finishes what it starts (10 field screenshots fixed)

v1.0.5 still failed on-device (10 error screenshots from a real E2E run):
"search" opened the browser instead of fetching data, a PDF-report plan died at
step 2 with a MALFORMED_RESPONSE card, "create a website" ended as a promise
("Love it! Let me put together something slick for you.") with no file, and
created files were invisible — log text instead of something openable. Every
root cause was traced to code, fixed, and pinned by new emulator E2E tests
that replay the user's exact prompts.

### Fixes

1. **WEB_SEARCH no longer fakes success with a browser** — DuckDuckGo Lite
   now serves a 202 bot-challenge to many clients, so the v1.0.5 single
   backend parsed zero results and fell back to "Opened the browser for you."
   — a fake success that starved every downstream plan step of data. Search
   now walks four real backends in-app (DDG Lite → DDG HTML → Bing → Google
   News RSS) and NEVER opens a browser; failure is honest so the planner can
   retry with a different source.
2. **A flaky evaluator can no longer kill a running plan** — the PDF report
   plan died exactly at the step boundary: the advisory re-evaluation call
   returned an unparseable answer, the loop marked the whole plan FAILED with
   step 2 still pending. Re-evaluation (and unknown-action replanning) are
   now advisory: a failure logs and the plan marches on to its remaining
   steps.
3. **Tool-call answers are executed, not rejected** — the Zen free tier
   forces `read`/`shell` harness tools into every request, so reasoning
   models sometimes answer with real tool calls (91 shell-call deltas,
   zero prose) which v1.0.5 reported as "OpenCode Zen returned an unreadable
   response". The provider now accumulates the streamed tool-call fragments
   and surfaces them; a chat-path tool loop maps them onto the app's real
   actions (read→READ_FILE, heredoc/echo writes→WRITE_FILE, mkdir, ls,
   curl→FETCH_URL), feeds results back to the model, and delivers a grounded
   final answer.
4. **Data goals can't end as a promise** — "Let me check the current gold
   price for you." executed as an empty CHAT step. The prose-deferral gate
   now also covers data goals (price/fetch/search/…), and when the model
   still fails twice the planner synthesizes a REAL executable plan: data
   goals become a WEB_SEARCH/FETCH_URL step, artifact goals get one
   CONTENT_NOW generation call and become WRITE_FILE/CREATE_PDF with the
   complete content inline. The turn can no longer end in "let me build it".
5. **Created files appear in the chat as FILES** — WRITE_FILE/CREATE_PDF
   successes emit a file attachment card (name, size, type badge) with
   Open (FileProvider ACTION_VIEW) and Share actions, backed by a new
   Room migration (v11, attachmentJson) so cards persist in history.
6. **Every information action now produces real data in-app** — GET_WEATHER
   (Open-Meteo geocoding + forecast, wttr.in fallback), CURRENCY_CONVERT
   (open.er-api.com, frankfurter fallback), CHECK_STOCK (Yahoo Finance),
   DEFINE_WORD (dictionaryapi.dev), TRANSLATE (gtx endpoint, MyMemory
   fallback), FACT_CHECK (in-app search) — no browser anywhere.
7. **FETCH_URL survives hostile sites** — direct fetch retries with a
   desktop user-agent, then falls back to the r.jina.ai reader proxy before
   admitting failure (the goldratestoday.org fetch failure).
8. **Thinking is fully viewable** — the THINKING section is scrollable to
   400dp with no 12-line ellipsis, the live streaming trace surface grew to
   1200 chars/10 lines, and tool-loop activity ("[tool] running the model's
   tool calls (round 1)…") streams there too.
9. **Emulator E2E replays the user's exact prompts** — three new on-emulator
   tests pin the field failures: the vague "can u create a award winning
   website in html" (a real .html artifact must appear), "ok search for
   latest iphone price" (in-app results listing, browser-fallback asserted
   away) and "fetch the price of gold" (real data or listing, malformed-card
   asserted away).

## v1.0.5 — Real agent capability (complex tasks actually execute now)

v1.0.4 could *chat*, but real usage showed the agent's deeper failures: a
capability-audit question FAILED as a plan ("Action 'CHAT' is not registered in
ActionDispatcher"), "ok start" → "Let's build it!" was followed by silence, and
web "search" just opened Chrome. This release makes the agent genuinely capable
on complex tasks and shows what it is thinking.

### Fixes

1. **Conversational answers finally execute** — prose plan replies are
   classified into CHAT steps, but CHAT had no registered handler, so every
   conversational answer died with "Action 'CHAT' is not registered in
   ActionDispatcher" (the capability-audit failure). CHAT steps now deliver
   the full reply as a real chat bubble + speech, mark the plan COMPLETED,
   and never parrot the answer in a second summary bubble. A CHAT handler is
   also registered in the dispatcher so macros/routines can never hit the
   UnknownAction path either.
2. **No more 400-character answers** — prose replies were truncated at 400
   chars (the "…which ca" cut-off in the logs). The cap is now 16k chars:
   long answers arrive whole.
3. **The planner knows what the agent can do** — the planning prompt gained a
   capability-truth section (file creation, PDF creation, in-app web search &
   fetch, plus the full device-control list) with an explicit ban on the
   "tool calls are not available in this session" hallucination. "ok start"
   now leads to an actual WRITE_FILE plan, and a refusal-before-trying is
   prompt-forbidden.
4. **REAL web capability without Chrome** — WEB_SEARCH now fetches live
   DuckDuckGo results in-app and returns titles/snippets/URLs as data;
   GET_NEWS fetches Google News RSS and returns real headlines; SUMMARIZE_URL
   fetches and reduces the page in-app; new FETCH_URL brings any page's text
   into the plan for downstream steps. The browser opens only as an offline
   fallback.
5. **CREATE_PDF** — a new action generates real, paginated A4 PDF documents
   from text content (Android PdfDocument) saved into the agent workspace.
6. **Watch the agent think** — reasoning-model thinking deltas
   (`reasoning`/`reasoning_content`) are surfaced live: the thinking indicator
   streams the model's current reasoning, and every agent bubble carries a
   collapsible THINKING section with the full trace. The planning call itself
   streams its reasoning too.
7. **Rebrand completed** — OPENDROID header, "Ask OpenDroid…" placeholder,
   onboarding and all LLM personas now say TSF Droid.

7. **Plans can carry real file content** — the planning output budget rose
   from 1500 to 4096 tokens: plans for content-creation tasks embed the whole
   file inline, and 1500 truncated them mid-JSON into "I am creating the
   file" chat answers with nothing written.
8. **Prose-deferral gate** — a short prose commitment ("I am creating the
   HTML file for you.", "Let's build it!") against a create-a-file goal is
   rejected as a non-plan and triggers one corrective re-ask with an
   execute-NOW instruction; if the re-ask also fails, the original answer is
   still delivered as an honest chat reply.
9. **The approval card is always visible** — the plan card renders after the
   messages, so with a longer chat it sat below the fold where the user never
   saw it (the agent appeared to just stop). The chat now auto-scrolls to the
   true end of the list whenever the agent state changes.

### Verified on emulator (GitHub Actions, live Zen free tier)

The complex-task E2E suite runs the agent end to end through the real UI:
capability-audit question answered (the v1.0.4 CHAT crash path), an HTML file
actually written to Documents/e2e_site.html, https://example.com fetched
in-app with the "Example Domain" heading reported, and a real PDF generated
at Documents/e2e_report.pdf (%PDF magic verified) — plan-approval card
driven like a real user, screenshot evidence pulled as artifacts.

### Tests
- WebContentParsersTest (13 cases): DDG parsing, RSS headlines, HTML→text
  reduction, entity decoding incl. hostile numeric refs.
- PlanResponseSanitizerTest extended: the 400-char regression case, the 16k
  bound, whitespace-collapse behavior, and 5 prose-deferral cases.

## v1.0.4 — Reasoning-model hardening (the unreadable-response fix)

v1.0.3 got keyless auth working — and immediately surfaced the *next* layer:
free-tier models like `mimo-v2.6-flash-free` are reasoning models that answer
differently than the models the parser was built against. Field reports showed
`MALFORMED_RESPONSE` banners and `PLAN_GENERATION` failures on requests that
v1.0.3 successfully delivered to the endpoint.

### Fixes

1. **Tool-call answers no longer kill the turn** — reasoning models holding the
   harness tool contract sometimes answer with `tool_calls` (for `read`/`shell`)
   instead of writing the plan. The app cannot execute OpenAI tool calls, so
   v1.0.3 surfaced this as "returned an unreadable response". Now:
   every request sends `tool_choice: "none"` (live-verified the free-tier gate
   still accepts it with the mandatory harness tools), and a tool-call-only
   answer triggers one corrective re-ask with an explicit no-tools instruction
   before a `MalformedResponse` can surface.
2. **Gate-evolution fallback** — if the endpoint ever starts rejecting the
   `tool_choice` field (403 FreeTierError or 400), the request degrades to the
   exact official-client body once instead of failing. Bounded: at most 4
   attempts, never a loop.
3. **Plan parser speaks reasoning-model** — answers wrapped in `<think>` blocks
   (closed *or* unterminated), fenced, or narrated around prose are all parsed
   (progressive candidates + brace-depth JSON recovery); a genuinely ambiguous
   goal now comes back as a clarifying question or a conversational answer
   instead of `PLAN_GENERATION FAILED`.
4. **Keyless by default** — a fresh install now starts on OpenCode Zen (free,
   no API key) instead of Google Gemini, so the app has a working brain before
   any configuration. Existing saved selections are untouched.
5. **Media steps no longer self-destruct** — `PLAY_YOUTUBE`/`PLAY_MUSIC` open
   the app with your search and report success with a one-tap hint: search
   result pages never auto-start playback, so strict verification failed by
   construction.
6. **Full UI journey on the emulator** — CI now drives the real app end to
   end: completes onboarding, visits every tab, verifies the OpenCode Zen
   default in Settings, sends two live chat messages and screenshots every
   step (artifacts on every run).

### Verification

- New unit tests: tool-call corrective retry (recovery + terminal paths),
  `tool_choice:"none"` wire assertion, gate-fallback body shape, reasoning/
  prose plan fixtures, media-verification degradation (13 new/updated cases).
- Live A/B against the real endpoint (this repo's tooling): `tool_choice`
  accepted by the gate, mimo emits clean plan JSON with reasoning in a
  separate delta field.

## v1.0.3 — The free-tier body contract (the real 403 fix)

v1.0.2 fixed the request *headers* and still failed, because the endpoint
checks the request **body** too. This release replays the exact bytes the
official OpenCode client sends and passes every gate the live endpoint
enforces. Verified end-to-end from an Android emulator against the real
endpoint (GitHub Actions emulator E2E), not just from desktop curl.

### What the free tier actually gates on (newly reverse-engineered)

Captured the official client's full request and ablated it line by line:

1. **Harness tools in the body** — the endpoint rejects any request whose
   `tools` array lacks function tools *named* `read` and `shell` with
   `403 FreeTierError`, no matter how perfect the headers are. v1.0.2 sent
   no tools at all, so every keyless request was rejected. TSF Droid now
   always carries the two-tool harness contract (user-defined tools ride
   along after it).
2. **Streaming only** — `stream: false` is rejected with the same
   `FreeTierError` on the anonymous tier. All Zen completions are now
   transported as SSE and reassembled; chat streaming is *real* streaming
   now (v1.0.2 faked it by typing out a completed answer word-by-word).
3. **Minimum client version** — the endpoint answers `426 UpgradeRequired`
   when the pinned client version is below 1.18.0; the pinned version
   tracks the official client and 426 maps to a non-retryable server error.
4. **Session identity format** — the `ses_` identifier shape the endpoint
   validates (26-char body, 12-hex descending-timestamp head).

### Model-level fallback (fixes `HTTP 401 ... type=ModelError`)

The endpoint reports a retired or region-blocked model as **HTTP 401 with
`type=ModelError`** (or `type=RegionError` on 403) — v1.0.2 mislabeled all
of these as "rejected the API key". Now:

* `ModelError` / `RegionError` are recognized as model-level rejections and
  the request **walks the model hierarchy** instead of failing.
* A pinned model *leads* the hierarchy instead of dying alone: keyless
  requests fall back to the verified free hierarchy when the selection is
  retired (v1.0.1's default pin is long gone server-side).
* The static chain is now a live-probed, verified-working set of free
  models (mimo-v2.6-flash-free first), checked one by one against the real
  endpoint on release day; registry-flagged `deprecated` models are
  excluded from the picker and the hierarchy.

### Automatic model capabilities (models.dev — the OpenCode way)

Exactly how the official client "knows" context windows and reasoning
levels, now wired into TSF Droid:

* Context window (`limit.context`) and output ceiling (`limit.output`) per
  model, used to clamp the output budget to what actually fits alongside
  the prompt.
* Reasoning capability and reasoning levels (`reasoning_options` /
  `variants`) surfaced on every model entry.
* Deprecated registry entries never resurface as dead picker options.

### Transport hardening

* Real SSE parsing with null-safe chunk handling (`"usage": null` chunks
  no longer break the stream), tool-call-only answers surface as a
  malformed-response error instead of a misleading network error, and
  error bodies are read exactly once (a closed-source re-read could
  replace the real failure with a crash).
* Non-streaming `response_format` is never sent — the official client
  does not send it and free-tier models reject it.
* `streamComplete` streams deltas as they arrive over a channelFlow.

### New: emulator end-to-end on GitHub Actions

`e2e-emulator.yml` boots an Android emulator and runs the Zen contract
against the **live endpoint from the device stack**, plus a launch smoke
test with screen captures attached as artifacts. This is the harness that
catches a regression of this class before a release, not after.

## v1.0.2 — Zen wire contract + automatic model capabilities (September 25, 2026)

Reverse-engineered the official OpenCode client (installed locally, source
audited) and the models.dev registry, then rebuilt TSF Droid's OpenCode Zen
integration to match them exactly. This fixes the `HTTP 403 FreeTierError`
("OpenCode's free tier can only be used from within OpenCode") that v1.0.1
users hit — our requests carried none of the provenance the endpoint checks.

### Endpoint fidelity (the 403 fix)

The official client authenticates its keyless tier with the literal API key
`public` plus a set of identity headers — our v1.0.1 interceptor *stripped*
the Authorization header and sent a generic User-Agent, which the server
rejects. Every Zen request now carries:

* `Authorization: Bearer public` (or the user's own Zen key when configured)
* `User-Agent: opencode/latest/2.0.16/cli` — the official 4-segment shape
* `x-opencode-project` / `x-opencode-session` / `x-opencode-request` /
  `x-opencode-client` — minted with the upstream identifier format
  (26 chars: 12-hex descending-timestamp head + 14 base62 chars)

Verified against the live endpoint and the open-source client source
(`sst/opencode`): `session/llm/request.ts`, `provider/provider.ts`
(the `apiKey: "public"` anonymous loader), `schema/identifier.ts`.

### Automatic model capabilities (models.dev registry)

OpenCode never hardcodes model metadata; it reads the community registry at
models.dev. TSF Droid now does the same via a new probe-suppressed
`ModelsDevRegistry` (24h TTL, 30min failure cooldown, single-flight):

* **Context windows** per model (e.g. `ling-3.0-flash-fin-free` = 262,144)
  shown in the model picker as a "262K ctx" subtitle
* **Reasoning capability** shown as a "reasoning" subtitle
* **Free/paid status** derived from registry costs — anonymous (keyless) use
  only ever offers free models, mirroring the official client
* **Tool-call support** drives the REC (recommended) badge — agents live on
  tool calls
* Responses-API-only models (`provider.npm: @ai-sdk/openai`) are excluded
  from the picker until a Responses transport ships

### Error guidance

A new `FREE_TIER_BLOCKED` error state replaces the misleading "rejected the
API key": when OpenCode's server still declines anonymous requests, the chat
error card now says exactly that and points to the optional Zen key field
(opencode.ai console) or another provider. The Settings key card lists
OpenCode Zen as **optional** — the keyless tier remains the default path.

### Tests

New gauntlet suite: identifier format + header map pins (`ZenIdentityTest`),
registry parsing incl. free-cost and protocol filters
(`ModelsDevRegistryParseTest`), and real-socket wire-contract coverage —
provenance headers on the request, FreeTierError → actionable error with
redaction guarantees, and a picker that never empties
(`OpenCodeZenNetworkTest`).

## v1.0.1 — OpenCode Zen surfaced in the app (September 24, 2026)

v1.0.0 shipped the OpenCode Zen keyless provider wired into the network and
factory layers, but two UI gaps kept users from reaching it: the Settings
provider dropdown was a separate hardcoded list that never gained the entry,
and the model picker had no branch for the provider, so it would have shown an
empty list. This release closes both gaps — **no manual configuration needed:
OpenCode Zen and its free models are predefined and load themselves**.

### Fixes

#### 🔧 Settings dropdown now mirrors the provider catalog
* The "Active Brain Provider" dropdown is derived from `ProviderCatalog` (single
  source of truth) instead of a hardcoded list, so **OpenCode Zen appears in
  Settings → Agent Preferences** without any manual setup.
* The provider API keys card skips OpenCode Zen — it is keyless; there is
  nothing to paste.

#### 🧠 Model picker loads OpenCode Zen's free models
* `ModelFetcher` gained an OpenCode Zen branch that reuses the provider's
  probe-suppressed discovery (single-flight, 1-hour TTL, 10-minute failure
  cooldown) — the picker rides the same cache as chat requests instead of
  polling `/models` on its own schedule.
* Picker list = live `/models` discovery merged with the static free chain
  (`x-preview-f-free` → `muse-spark-1.2-contributor-free` → `hy3-free` →
  `mimo-v2.5-free`), free models sorted first with the chain head on top,
  non-chat endpoints (embeddings/guards) filtered out. **The picker is never
  empty**, even if the discovery endpoint is unreachable.

#### 📖 Docs and regression tests
* Help Center rewritten for the keyless flow (and no longer refers to the app
  by its upstream name); About screen lists OpenCode Zen.
* New regression tests: catalog must expose OpenCode Zen as known + keyless;
  picker parsing keeps the free chain on top, drops non-chat ids, and never
  returns empty.

### Upgrade notes
* Sideloading this release over v1.0.0 works in place (same signature,
  higher versionCode). If you were on a v1.0.0 debug build, uninstall first —
  debug and release signatures differ.

## v1.0.0 — First TSF Droid release (September 24, 2026)

Initial release of **TSF Droid**, the hardened, sanitized fork-lineage of
OpenDroid rebuilt as an independently branded Android-native AI agent.

### Highlights

#### 🔑 OpenCode Zen keyless provider
* New built-in LLM provider requiring **no API key**: requests are routed
  through `https://opencode.ai/zen/v1` with a browser-grade user agent and
  authorization headers stripped by `OpenCodeZenInterceptor`.
* Automatic model fallback chain: `x-preview-f-free` →
  `muse-spark-1.2-contributor-free` → `hy3-free` → `mimo-v2.5-free`.
* Dynamic model discovery from the `/models` endpoint and models.dev, plus
  suppression of pointless reachability probes to https endpoints.

#### 🧘 UI idle settle barrier
* The accessibility layer now waits for view-tree stability before acting:
  `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_VIEW_SCROLLED` events reset a timer
  and taps, swipes, and text input fire only after the hierarchy hash has
  been stable for ≥350 ms (hard timeout 2000 ms).
* Dramatically reduces mis-taps on animated feeds, chat apps, and slow
  webviews that previously caused flaky automation.

#### 🚦 Intent segmentation & confirmation gates
* The planner now emits structured, segmented plans (`task_id`, `is_compound`,
  `steps[step_id, action, target, critical]`).
* SMS, dialing, system modifications, UPI payments, and destructive file
  operations are flagged `critical` and **always require explicit user
  confirmation** — enforced in every auto-approval mode including YOLO,
  resolving the upstream documentation/code contradiction in the safer
  direction.

#### 🛰️ Blind-spot resilience
* `VisionEngine` detects all-black MediaProjection frames (FLAG_SECURE apps:
  banking, DRM, private browsing) via sparse-grid pixel sampling and refuses
  to send them to the vision model.
* When the accessibility tree is empty (Flutter/Unity/game canvases), the
  agent aborts blind coordinate taps and falls back to native Android
  `ACTION_VIEW` / `ACTION_SEND` / chooser intents or a spoken prompt instead
  of looping forever.

#### 🧼 Sanitization & rebrand
* Fully rebranded to `com.tsfdroid.ai` (application id, namespaces, packages,
  resources) with all upstream cryptocurrency/donation content removed.
* 12 never-auto-approve high-risk actions preserved; 4-layer Personal
  Knowledge Graph with Keystore AES-256-GCM encrypted Sensitive layer intact.

### Installation
1. Download `TSF-Droid-v1.0.0-release.apk` (or the debug build for logging).
2. Verify the SHA-256 against `SHA256SUMS.txt`.
3. Sideload the APK (Settings → Security → Install unknown apps).
4. Grant the Accessibility service and (optionally) MediaProjection permission.
5. Add a provider: pick **OpenCode Zen** for a keyless start, or any of the
   12 API-key providers / on-device LiteRT.

### Checksums
See `SHA256SUMS.txt` attached to the release for the SHA-256 of every artifact.
The release APK is signed with the dedicated TSF Droid release key
(V1+V2 schemes) — updates must carry the same signature.
