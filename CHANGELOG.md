# TSF Droid Changelog

All notable changes to TSF Droid are documented here. The release workflow
(`.github/workflows/release.yml`) extracts the section matching the pushed tag
and publishes it as the GitHub Release notes.

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
