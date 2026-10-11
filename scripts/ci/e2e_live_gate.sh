#!/usr/bin/env bash
# INFORMATIONAL LIVE-PROVIDER E2E — honest classification, never silent.
#
# Runs the live OpenCode Zen free-tier suites on the emulator. This gate is
# INFORMATIONAL: it never blocks the release (the hermetic gate is the
# release-blocking truth), but its verdict is classified and reported with
# evidence, never faked:
#
#   PASS          final attempt clean AND at least LIVE_MIN_TESTS ran
#   RATE_LIMITED  failures exist AND measured provider degradation
#                 (pump p90 > 15s, or >=3 pumps > 30s, or explicit 429 /
#                  FreeTierBlocked / RATE_LIMITED lines in logcat)
#   FAIL          failures WITHOUT provider-degradation evidence - treat as a
#                 potential real regression and investigate; also used when
#                 the tests did not actually run (outage, never a pass)
#   FAIL_TIMEOUT  the suite itself timed out (exit 124)
#
# Run 38107493396 lesson (the fake PASS): the APKs must be INSTALLED on the
# fresh emulator, instrumentation errors arrive on stderr, and "no output /
# zero tests" must classify as FAIL — an empty run is an outage, not a pass.
#
# IMPORTANT (measured on run 37770800170): a naive `grep -c 429` over logcat
# counts TIMESTAMP MILLISECONDS (".429"), not HTTP errors. The honest evidence
# for free-tier throttling is the provider pump latency distribution
# ("OpenCodeZen: pump BEGIN/END" gaps: median ~3s healthy vs 60-90s degraded)
# plus starvation-style test failures ("no reply within NNNs").
#
# Outputs (all under e2e-screens/):
#   live-attempt-1.txt / live-attempt-2.txt  both attempts preserved
#   instrument-output.txt                    final attempt (QA report input)
#   instrument-logcat.txt                    full device logcat
#   live-summary.txt                         machine-readable verdict
#
# Exit codes: 0 = PASS, 1 = anything else (the workflow step is
# continue-on-error; the classification lives in live-summary.txt).
set -u

CLASSES="com.tsfdroid.ai.e2e.ZenFreeTierInstrumentedTest,com.tsfdroid.ai.e2e.AppUiInteractionInstrumentedTest,com.tsfdroid.ai.e2e.AgentCapabilityE2EInstrumentedTest,com.tsfdroid.ai.e2e.AttachmentPipelineInstrumentedTest,com.tsfdroid.ai.e2e.ChatExportInstrumentedTest,com.tsfdroid.ai.e2e.FieldFixesInstrumentedTest"
RUNNER="com.tsfdroid.ai.test/androidx.test.runner.AndroidJUnitRunner"

# Floor for the PASS verdict: 34 tests as of 1.7.0-rc.1. Below this the run
# did not really exercise the suites - classify FAIL, never PASS.
LIVE_MIN_TESTS=25

mkdir -p e2e-screens

reinstall() {
  adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
  adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null 2>&1
}

run_suite() {
  # stdout AND stderr: instrumentation failures arrive on stderr
  timeout 4200 adb shell am instrument -w -e class "$CLASSES" "$RUNNER" 2>&1
}

collect_artifacts() {
  timeout 60 adb logcat -d > e2e-screens/instrument-logcat.txt 2>/dev/null || true
  timeout 60 adb pull /sdcard/Android/data/com.tsfdroid.ai/files/e2e-screens/. e2e-screens/ 2>/dev/null || true
  timeout 90 adb exec-out run-as com.tsfdroid.ai tar cf - files/e2e-screens 2>/dev/null \
    | tar xf - -C e2e-screens --strip-components=2 || true
  mkdir -p e2e-screens/workspace
  timeout 90 adb pull /sdcard/Android/data/com.tsfdroid.ai/files/workspace/. e2e-screens/workspace/ 2>/dev/null || true
}

count_tests() {
  local ok_n run_n fail_n err_n
  ok_n=$(printf '%s\n' "$1" | sed -nE 's/^OK \(([0-9]+) tests?\)$/\1/p' | tail -1)
  if [ -n "$ok_n" ]; then
    echo "pass=$ok_n total=$ok_n fails=0 errors=0"
    return
  fi
  run_n=$(printf '%s\n' "$1" | sed -nE 's/^Tests run: ([0-9]+),.*$/\1/p' | tail -1)
  fail_n=$(printf '%s\n' "$1" | sed -nE 's/^Tests run: [0-9]+,[[:space:]]*Failures: ([0-9]+).*/\1/p' | tail -1)
  err_n=$(printf '%s\n' "$1" | sed -nE 's/^Tests run: [0-9]+,[[:space:]]*Failures: [0-9]+,[[:space:]]*Errors: ([0-9]+).*/\1/p' | tail -1)
  run_n=${run_n:-0}; fail_n=${fail_n:-0}; err_n=${err_n:-0}
  echo "pass=$((run_n - fail_n - err_n)) total=$run_n fails=$fail_n errors=$err_n"
}

extract_total() {
  printf '%s\n' "$1" | sed -nE 's/.*total=([0-9]+).*/\1/p' | tail -1
}

failed_tests_list() {
  # junit4 text runner: "N) testName(className)" under "There was/were N failure(s):"
  printf '%s\n' "$1" \
    | sed -nE 's/^[[:space:]]*[0-9]+\) ([A-Za-z0-9_]+)\(([A-Za-z0-9_.]+)\)$/\1 (\2)/p' \
    | paste -sd '; ' -
}

# Provider pump latency distribution from logcat. Lines look like:
#   10-08 12:43:29.644 10507 11692 I OpenCodeZen: pump BEGIN model=...
#   10-08 12:44:37.738 10507 11692 I OpenCodeZen: pump END model=... finish=stop
# Each BEGIN is paired with the next END (the app drives turns sequentially).
pump_stats() {
  awk '
    /OpenCodeZen: pump BEGIN/ { split($2, t, ":"); begin = t[1]*3600 + t[2]*60 + t[3]; next }
    /OpenCodeZen: pump END/ && begin != "" {
      split($2, t, ":"); end = t[1]*3600 + t[2]*60 + t[3]
      d = end - begin; if (d < 0) d += 86400
      n++; dur[n] = d; total += d
      if (d > 30) slow++
      begin = ""
    }
    END {
      if (n == 0) { print "pumps=0"; exit }
      # selection sort for the small n of a single run
      for (i = 1; i <= n; i++) for (j = i+1; j <= n; j++) if (dur[j] < dur[i]) { tmp=dur[i]; dur[i]=dur[j]; dur[j]=tmp }
      p90 = dur[int(n * 0.9)]; if (p90 == "") p90 = dur[n]
      printf "pumps=%d median=%.1fs p90=%.1fs max=%.1fs slow_pumps=%d\n", n, dur[int((n+1)/2)], p90, dur[n], slow+0
    }
  ' e2e-screens/instrument-logcat.txt 2>/dev/null || echo "pumps=0"
}

# Explicit throttle evidence, UNAMBIGUOUS forms only. A bare "429" in logcat
# is usually numeric noise (object hashes, "max=429.93ms", timestamps), so a
# standalone 429 does NOT count: the line must carry provider context.
explicit_throttle_lines() {
  grep -cE 'Too Many Requests|FreeTierBlocked|RATE_LIMITED|(HTTP|http|status|code)[ =:]+429' \
    e2e-screens/instrument-logcat.txt 2>/dev/null || true
}

starvation_failures() {
  # Failures whose message is a bounded-wait timeout, the free-tier signature.
  printf '%s\n' "$1" | grep -cE 'within [0-9]+s|never (surfaced|appeared|produced)' || true
}

# ---- install: a fresh emulator has no app on it ---------------------------
./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace || {
  collect_artifacts
  {
    echo "LIVE_GATE: FAIL"
    echo "LIVE_TESTS: pass=0 total=0 fails=0 errors=0"
    echo "LIVE_ATTEMPTS: 0"
    echo "FAILED_TESTS: none"
    echo "RATE_LIMIT_EVIDENCE: gradle install failed - the APKs never reached the emulator"
  } > e2e-screens/live-summary.txt
  cat e2e-screens/live-summary.txt
  exit 1
}

echo "=== GATE 2: LIVE-PROVIDER SUITE (OpenCode Zen free tier; INFORMATIONAL) ==="
l=$(run_suite); lcode=$?
printf '%s\n' "$l" > e2e-screens/live-attempt-1.txt
printf '%s\n' "$l" | tail -15

if [ "$lcode" -eq 124 ]; then
  echo "LIVE SUITE TIMED OUT (exit 124) - quota/latency diagnosis needed, artifacts retained"
fi

final="$l"; attempts=1
if [ "$lcode" -ne 124 ] && { [ -z "$l" ] || printf '%s\n' "$l" | grep -qE "FAILURES|INSTRUMENTATION_FAILED"; }; then
  echo "=== live retry pass (free-tier latency flakes move between runs; both attempts preserved) ==="
  reinstall
  final=$(run_suite); fcode=$?
  attempts=2
  printf '%s\n' "$final" > e2e-screens/live-attempt-2.txt
  printf '%s\n' "$final" | tail -15
fi

printf '%s\n' "$final" > e2e-screens/instrument-output.txt
counts=$(count_tests "$final")

# logcat AFTER the suite so the pumps of both attempts are captured
collect_artifacts

total=$(extract_total "$counts")
fails_n=$(printf '%s\n' "$counts" | sed -nE 's/.*fails=([0-9]+).*/\1/p' | tail -1)
errors_n=$(printf '%s\n' "$counts" | sed -nE 's/.*errors=([0-9]+).*/\1/p' | tail -1)
stats=$(pump_stats)
p90=$(printf '%s\n' "$stats" | sed -nE 's/.*p90=([0-9.]+)s.*/\1/p')
slow=$(printf '%s\n' "$stats" | sed -nE 's/.*slow_pumps=([0-9]+).*/\1/p')
explicit=$(explicit_throttle_lines)
starved=$(starvation_failures "$final")
failed=$(failed_tests_list "$final")
[ -z "$failed" ] && failed="none"

status=FAIL
reason=""
if [ "$lcode" -eq 124 ]; then
  status=FAIL_TIMEOUT
  reason="live suite timed out (exit 124)"
elif [ -z "$final" ]; then
  reason="NO INSTRUMENT OUTPUT - the tests did not run at all (outage, not a pass)"
elif printf '%s\n' "$final" | grep -qE "FAILURES|INSTRUMENTATION_FAILED" \
  || [ "${fails_n:-0}" -gt 0 ] 2>/dev/null || [ "${errors_n:-0}" -gt 0 ] 2>/dev/null; then
  # Rate-limit classification requires MEASURED degradation, not a guess:
  # p90 above 15s, or 3+ slow pumps, or explicit 429/FreeTierBlocked lines.
  degraded=0
  awk -v p90="${p90:-0}" 'BEGIN { exit !(p90 > 15) }' && degraded=1
  [ "${slow:-0}" -ge 3 ] 2>/dev/null && degraded=1
  [ "${explicit:-0}" -ge 1 ] 2>/dev/null && degraded=1
  if [ "$degraded" -eq 1 ]; then
    status=RATE_LIMITED
  else
    reason="failures WITHOUT measured provider degradation - investigate as a regression"
  fi
elif [ "$total" -lt "$LIVE_MIN_TESTS" ] 2>/dev/null; then
  reason="only $total tests ran (floor is $LIVE_MIN_TESTS) - class discovery broke; not a pass"
else
  status=PASS
fi

evidence="pump ${stats:-unknown}; explicit_throttle_lines=${explicit:-0}; starvation_failures=${starved:-0}"

{
  echo "LIVE_GATE: $status"
  echo "LIVE_TESTS: $counts"
  echo "LIVE_ATTEMPTS: $attempts"
  echo "FAILED_TESTS: $failed"
  echo "RATE_LIMIT_EVIDENCE: $evidence"
  [ -n "$reason" ] && echo "LIVE_NOTE: $reason"
} > e2e-screens/live-summary.txt
cat e2e-screens/live-summary.txt

# The step exits non-zero on any non-PASS outcome so the run page shows the
# red X; the job's continue-on-error keeps it informational (never blocks
# the release), and the classification above is the honest verdict.
if [ "$status" = "PASS" ]; then
  exit 0
else
  echo "::warning::LIVE PROVIDER STATUS: FAIL_${status} (${reason}; ${evidence})"
  exit 1
fi
