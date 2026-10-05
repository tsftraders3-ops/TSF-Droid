# Phase 21 — The Bars (v1.5.0 field-failure fix session)

Gauntlet rules: every bar is **named, fetchable, comparable**, and every one carries a
**measurable half** — a test that fails on the field evidence and passes on the fix.
The field evidence itself is the primary bar reference: `docs/field-reports/2026-10-05-corpus/`
(7 real-usage exports, 30 turns, every failure below happened to a real user on v1.5.0).

Judging protocol: replay the EXACT field inputs (verbatim goals, verbatim replies,
verbatim needs-input answers) through the fixed code paths. A bar is won only when the
field input produces an output that beats the field output on the named comparison.

---

## B1 Truthfulness — the confession is the default, not the fallback

**Reference (fetchable):** the model's own confession in chat-021218, 16:29 —
*"I don't have a platform to report — I jumped ahead earlier. No booking was actually
completed on Uber, Ola, or anywhere else."* That sentence, extracted under questioning,
is the honesty level the UNPROMPTED summary must meet.

**Comparable A/B:** field summary ("Your cab is booked for tomorrow at 5 AM from your
current location to Kolkata.") vs the fixed summary for the same plan shape
(ASK_USER-only steps, no booking action exists). A judge picks which one they'd trust
after learning no booking tool exists. The fixed one wins iff it never asserts an
outcome no executed action produced.

**Measurable gate:** unit test replays the cab plan shape (ASK_USER steps, all
COMPLETED, goal "book cab…") through the summary path → output must NOT match
`book(ed)?|scheduled|confirmed` as a completion claim, and must state that no booking
was performed. Same for the gold-transcript shape (template PDF content). The
`askConfirmedSummary` prompt itself must contain the no-fabrication instruction.

---

## B2 Answer hygiene — nothing internal ever reaches the user

**Reference (fetchable):** any real ChatGPT/Claude answer (neither ships tool syntax,
harness stubs, or raw JSON envelopes as the visible reply), and the three field
failures as anti-references: chat-033518's six leading `web_search {"query":…}` lines;
chat-021145's `I used web_search(… ) to work on this.` stub as the entire answer;
chat-021218's fenced `{"speech": …}` wrapper.

**Comparable A/B:** field answer vs fixed answer for the same raw model output. The
fixed one wins iff the user sees zero tool-syntax lines, zero stub sentences, zero
JSON envelopes, zero unfilled `[placeholder]` brackets — without losing any real
content the model produced after the garbage.

**Measurable gate:** unit tests feed the four exact field shapes + clean answers from
the corpus (gold reply, workout file reply, jetpack answer) → (a) all four poison
shapes are neutralized, (b) zero of the clean answers is altered (no false positives —
the anti-regression half matters as much as the fix half).

---

## B3 Deliverables — the file the user named, with content that passes the gate

**Reference (fetchable):** the user's own words in the corpus: *"compile a
comprehensive report called ondevice_llm_benchmark_2026.md … save it as
llm_perf_metrics.csv"* (chat-033518) and *"write a python script call
scrap_titles.py"* (chat-021145). The bar is literally the filename in the sentence.

**Comparable A/B:** field outcome (document.txt containing a WhatsApp-hallucination;
data.json containing markdown; a 1-sentence 201KB PDF) vs fixed outcome (the named
file, with the requested content, or an honest chat delivery when content fails the
gate). Also: the 02:31/03:14 double `report.pdf` overwrite vs auto-rename.

**Measurable gate:** unit tests: (a) the filename parser extracts both names from the
verbatim field goals; (b) the content gate rejects the exact field garbage contents
("I'll first check the environment…", the `[today 24k price]` template); (c) writing
the same name twice renames, never overwrites; (d) a 2-file goal synthesizes 2 steps.

---

## B4 One storage — every created file lands where the user chose, with a card

**Reference (fetchable):** the v1.5.0 zip itself: an empty `MarketReports/` in the
user's folder while the gold PDF sat in `Android/data` — the physical evidence of the
split brain. 12/12 WRITE_FILE turns had no artifact card; 4/4 CREATE_PDF had one.

**Comparable A/B:** field state (PDF in app-private, folder empty in user space, no
cards on text files) vs fixed state (both write kinds land in the SAME user-selected
root when one is set; both kinds carry cards). A user zipping their folder after a
day of use must find every promised file without mining Android/data.

**Measurable gate:** storage-layer unit tests for the dispatch + collision + path
reporting (pure-extracted helpers); E2E on the emulator: with a SAF folder granted,
one WRITE_FILE and one CREATE_PDF in a live turn → both land in the folder, both
messages carry artifact cards (asserted from the exported chat JSON).

---

## B5 Respect the user — "stop" stops, prompts speak human, failures are instant

**Reference (fetchable):** the flipkart transcript (chat-111835, 10:40→11:08): four
explicit stop commands consumed as param text; prompts exposing `searchText`/`content`
param names; `Action 'TAP' is not registered` arriving after 19 minutes of execution.

**Comparable A/B:** the 28-minute field loop vs the fixed flow: same needs-input
sequence replayed → the first "stop" aborts the plan with an honest summary; the
prompt reads "Which text should I tap?" not "I need the searchText…"; an invented
action name dies at validation time (before any accessibility step runs), remapped
when an obvious mapping exists (TAP → CLICK_TEXT).

**Measurable gate:** unit tests: (a) the verbatim field stop-replies ("stop don't
need to do anything", "stop", "cancel") trip the cancel lexicon while "forward",
"ooo", "kjn" don't; (b) TAP/TAP_ELEMENT/CLICK/PRESS map to real actions; (c) a plan
containing an unregistered action fails validation with the action named; (d) the
double-persist sites are single-write.

---

## B6 Routing — questions are answered, compound goals are planned, queries are sanitized

**Reference (fetchable):** chat-021145 02:08 — *"can you tell me the location in
device where the export chats are saved"* → a WRITE_FILE that overwrote
scrap_titles.py. chat-111835 10:39 — the 403-char first-person morning-briefing ask
sent verbatim to public web search after a screen-read alias no-op'd.

**Comparable A/B:** field routing (question → file-write + data loss; compound ask →
alias no-op + privacy leak) vs fixed routing (question → chat answer naming
`workspace/Exports/`; compound ask → multi-step plan; any web search query contains
zero first-person pronouns / zero private request text).

**Measurable gate:** unit tests on the verbatim field queries: (a) the interrogative
guard blocks WRITE_FILE/CREATE_PDF plan steps for the export-location query;
(b) the morning-briefing query does not match the read-and-remember alias and routes
to planning; (c) the de-personalized search query for the briefing ask contains no
"my calendar"/"my emails" phrases.

---

## B7 Export completeness — the next field report can answer what this one couldn't

**Reference (fetchable):** section 10 of the field report — the four conclusions the
analyst could NOT draw: CHAT vs AGENT per turn, the 9-minute unaccounted expansion
window, thinking-segment boundaries, per-turn plan approval state. The bar is the
next report's "Honest limitations" section being SHORTER because these are now
answerable.

**Comparable A/B:** the v1.5.0 export schema vs the fixed schema for the same
session. The fixed one wins iff it answers: which mode ran this turn, how long the
turn took wall-clock, and cleanly separated thinking segments.

**Measurable gate:** unit test: the exporter emits `mode` and `usage.wallMs` per
message (round-trip parse); E2E: the on-device export of a live turn contains a
non-null mode for every message and `wallMs >= thinking.durationMs` on turns with
thinking.

---

## Exit condition

All seven bars won on their measurable gates; the full existing suite (715 unit tests,
46 E2E tests) stays green; the new field-corpus regression tests are in CI. A fresh
blind critic, shown the field report and the fixed behavior side by side, must pick
the fixed behavior on every bar.
