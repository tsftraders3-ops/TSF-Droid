#!/usr/bin/env python3
"""
TSF Droid QA diagnostic report generator (v1.3.1).

Adopted from the user's Gemini consult (2026-10-02): the useful core of its
"3-tier diagnostic matrix" idea — an automated report at the end of every E2E
run that maps failures to the exact tests, the agent-loop evidence and a
recommended action, so nobody digs through 17MB of logcat by hand. The
vision-model-screenshot-tap loop it proposed was NOT adopted (our a11y-tree
E2E is deterministic, faster and token-free); this report layer is the part
that makes forensics cheap.

Reads (all optional, run from the repo root in the E2E workflow):
  e2e-screens/instrument-output.txt  — full `am instrument` stdout
  e2e-screens/instrument-logcat.txt  — full device logcat of the run

Writes:
  QA_REPORT.md                        — the human-readable diagnostic
  $GITHUB_STEP_SUMMARY (appended)     — same content on the run page
"""
from __future__ import annotations

import os
import re
import sys
from datetime import datetime, timezone

OUT_FILE = "e2e-screens/instrument-output.txt"
LOGCAT_FILE = "e2e-screens/instrument-logcat.txt"

# Logcat evidence lines worth surfacing — the exact signals the forensic
# rounds actually used, so a fresh run's report answers them upfront.
EVIDENCE_PATTERNS = [
    (re.compile(r"PlanValidator"), "plan repair"),
    (re.compile(r"PlanStallWatchdog"), "plan watchdog"),
    (re.compile(r"searchWeb query="), "search chain"),
    (re.compile(r"ActionDispatcher"), "action dispatch"),
    (re.compile(r"HararnessLoop|HarnessLoop", re.I), "harness loop"),
    (re.compile(r"ContextCompactor|75% context", re.I), "compaction"),
]


def read(path: str) -> str:
    try:
        with open(path, "r", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def parse_instrument(out: str) -> dict:
    info = {
        "ok": None,
        "tests": None,
        "failures": [],
        "instrumentation_failed": "INSTRUMENTATION_FAILED" in out,
    }
    m = re.search(r"OK \((\d+) tests?\)", out)
    if m:
        info["ok"] = int(m.group(1))
    m = re.search(r"Tests run: (\d+),\s*Failures: (\d+)", out)
    if m:
        info["tests"] = int(m.group(1))
    # junit4 text: "1) testName(className)" under "There were N failures:"
    block = re.search(r"There were \d+ failures?:(.*?)(?=\n\n|\Z)", out, re.S)
    if block:
        for line in block.group(1).splitlines():
            m = re.match(r"\s*\d+\)\s+(\S+)\((\S+)\)", line)
            if m:
                info["failures"].append(f"{m.group(1)} ({m.group(2)})")
    return info


def first_stack_hints(out: str, failures: list) -> dict:
    """One-line cause per failure, from the stack that follows it."""
    hints = {}
    for f in failures:
        name = f.split(" (")[0]
        idx = out.find(name)
        if idx < 0:
            continue
        window = out[idx: idx + 2000]
        cause = re.search(
            r"(java\.lang\.\w+|android\.util\.\w+|junit\.framework\.\w+)[::]([^\n]+)", window)
        if cause:
            hints[f] = f"{cause.group(1)}: {cause.group(2).strip()[:120]}"
    return hints


def harvest_evidence(logcat: str) -> list:
    lines, seen = [], set()
    for raw in logcat.splitlines():
        for pat, _label in EVIDENCE_PATTERNS:
            if pat.search(raw):
                line = raw.strip()
                if line not in seen and len(line) < 400:
                    seen.add(line)
                    lines.append(f"- `{line[:380]}`")
                break
        if len(lines) >= 40:
            break
    return lines


def main() -> int:
    out = read(OUT_FILE)
    logcat = read(LOGCAT_FILE)
    if not out and not logcat:
        print("QA report: no inputs found (expected on a workflow that did "
              "not reach the test step)")
        return 0

    inst = parse_instrument(out)
    hints = first_stack_hints(out, inst["failures"])
    evidence = harvest_evidence(logcat)
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    lines = []
    a = lines.append
    a("# TSF Droid QA Diagnostic Report")
    a("")
    a(f"_Generated {now} · inputs: {OUT_FILE}, {LOGCAT_FILE}_")
    a("")
    verdict = "GREEN"
    if inst["ok"] is not None:
        verdict = f"GREEN — OK ({inst['ok']} tests)"
    elif inst["instrumentation_failed"] or inst["failures"]:
        verdict = "RED"
    elif inst["tests"] is not None:
        verdict = f"RED — {len(inst['failures'])} failure(s) of {inst['tests']} tests"
    a(f"**Verdict: {verdict}**")
    a("")
    a("## Test outcome")
    if inst["ok"] is not None:
        a(f"- Instrumented suite: **OK ({inst['ok']} tests)**")
    if inst["tests"] is not None:
        a(f"- Tests run: {inst['tests']}, failures: {len(inst['failures'])}")
    if inst["instrumentation_failed"]:
        a("- INSTRUMENTATION_FAILED: the runner itself died (crash or "
          "emulator fault) — check the tail of the logcat for the abort.")
    if inst["failures"]:
        a("")
        a("## Failed tests")
        for f in inst["failures"]:
            a(f"- `{f}`" + (f" — {hints[f]}" if f in hints else ""))
        a("")
        a("## Recommended action")
        a("1. Read the failure stack above; map it to the test's source in "
          "`app/src/androidTest`.")
        a("2. Check the evidence section for the agent-loop view of the same "
          "turn (plan repairs, watchdog, search chain).")
        a("3. A single flaky test on a free-tier endpoint → re-run; a "
          "deterministic failure → fix before the next release tag.")
    else:
        a("- No test failures recorded.")
    a("")
    a("## Agent-loop evidence (keyed logcat lines)")
    if evidence:
        lines.extend(evidence)
    else:
        a("- (no PlanValidator / watchdog / search-chain lines captured)")
    a("")
    a("## Notes")
    a("- This report is generated by `scripts/generate_qa_report.py` from "
      "the run's own artifacts — the a11y-tree E2E stays deterministic; "
      "no vision-model tokens are spent to produce it.")
    a("- Screenshot forensics remain in `tsf-droid-e2e-artifacts` "
      "(e2e-screens/) when a visual verdict is needed.")

    report = "\n".join(lines) + "\n"
    with open("QA_REPORT.md", "w") as f:
        f.write(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("\n" + report)
    print(f"QA_REPORT.md written ({len(lines)} lines, verdict: {verdict})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
