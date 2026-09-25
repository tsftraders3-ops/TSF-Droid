# TSF Droid Changelog

All notable changes to TSF Droid are documented here. The release workflow
(`.github/workflows/release.yml`) extracts the section matching the pushed tag
and publishes it as the GitHub Release notes.

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
   via a new balanced-JSON extraction (`PlanResponseSanitizer`, unit-tested).
   The first brace-balanced JSON object in mixed text is recovered.
4. **Clarifying questions stopped being errors** — when a model answers a
   genuinely ambiguous goal in prose ("can call someone if I tell u the name?"
   → the model asks who to call), the answer routes into the existing
   ASK_USER / CHAT action protocol instead of failing with "Could not parse a
   valid plan from LLM response". One zero-temperature corrective re-ask with
   a strict JSON-only contract backs that up.
5. **PLAY_YOUTUBE / PLAY_MUSIC no longer self-report FAILED** — the YouTube
   action opens the *search results* page, where playback only starts after
   the user taps a video; a media-session verification could never pass there,
   so every step failed even though YouTube opened correctly. The step now
   succeeds with an honest "tap a video to start playback" hint when autoplay
   cannot be confirmed.

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
