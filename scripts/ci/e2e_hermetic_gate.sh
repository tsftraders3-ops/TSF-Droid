#!/usr/bin/env bash
# REQUIRED HERMETIC E2E GATE — the release-blocking, deterministic truth.
#
# Runs on the emulator with FakeLLMProvider-seamed components: no external
# network provider is involved, so this gate cannot be starved or poisoned by
# endpoint quota. A green hermetic gate means the app's own machinery (launch,
# onboarding journey, dashboard navigation, new chat, agent pipeline seams,
# accessibility targeting, keystore crypto) works on a real device.
#
# Run 38107493396 lesson (the fake PASS): (a) the APKs must be INSTALLED on
# the emulator before `am instrument` — a fresh emulator has nothing on it;
# (b) instrumentation errors go to stderr, so stdout capture alone sees an
# EMPTY output and must be judged FAIL, never PASS; (c) PASS requires a
# parsed test count of at least HERMETIC_MIN_TESTS — "no tests ran" is not
# a verdict, it is an outage.
#
# Outputs:
#   e2e-screens/hermetic-attempt-1.txt   first pass, full `am instrument` output
#   e2e-screens/hermetic-attempt-2.txt   retry pass (only if attempt 1 failed)
#   e2e-screens/instrument-output.txt    final attempt's full output (QA report input)
#   e2e-screens/instrument-logcat.txt   full device logcat
#   e2e-screens/hermetic-summary.txt    machine-readable verdict (workflow reads this)
#
# Exit codes: 0 = PASS, 1 = FAIL, 2 = FAIL_TIMEOUT (a hang, never a quota issue).
set -u

CLASSES="com.tsfdroid.ai.e2e.AppLaunchInstrumentedTest,com.tsfdroid.ai.e2e.HermeticAgentPipelineInstrumentedTest,com.tsfdroid.ai.e2e.HermeticJourneyInstrumentedTest,com.tsfdroid.ai.ui.CanvasUiTest,com.tsfdroid.ai.accessibility.ClickTargetingAccuracyInstrumentationTest,com.tsfdroid.ai.accessibility.KnownScreenScrapeInstrumentationTest,com.tsfdroid.ai.accessibility.ServiceReadinessInstrumentationTest,com.tsfdroid.ai.core.security.AndroidKeyStoreAeadCipherInstrumentationTest,com.tsfdroid.ai.core.security.AndroidProviderCredentialStoreInstrumentationTest"
RUNNER="com.tsfdroid.ai.test/androidx.test.runner.AndroidJUnitRunner"

# Floor for the PASS verdict: 29 tests as of 1.8.0-rc.1 (21 + 8 CanvasUiTest; the
# journey gained the Canvas stage inside its existing method).
# A count below this means class discovery broke — fail loudly, update the
# floor when tests are added.
HERMETIC_MIN_TESTS=15

mkdir -p e2e-screens

reinstall() {
  adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
  adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null 2>&1
}

run_gate() {
  # stdout AND stderr: instrumentation failures arrive on stderr
  timeout 1500 adb shell am instrument -w -e class "$CLASSES" "$RUNNER" 2>&1
}

collect_artifacts() {
  timeout 60 adb logcat -d > e2e-screens/instrument-logcat.txt 2>/dev/null || true
  timeout 60 adb pull /sdcard/Android/data/com.tsfdroid.ai/files/e2e-screens/. e2e-screens/ 2>/dev/null || true
  timeout 90 adb exec-out run-as com.tsfdroid.ai tar cf - files/e2e-screens 2>/dev/null \
    | tar xf - -C e2e-screens --strip-components=2 || true
  mkdir -p e2e-screens/workspace
  timeout 90 adb pull /sdcard/Android/data/com.tsfdroid.ai/files/workspace/. e2e-screens/workspace/ 2>/dev/null || true
}

# Parse "OK (N tests)" or "Tests run: N, Failures: F, Errors: E" from the
# final instrument output into test counts.
count_tests() {
  # $1 = output text
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

write_summary() {
  # $1 = status, $2 = counts, $3 = attempts, $4 = note (optional)
  {
    echo "HERMETIC_GATE: $1"
    echo "HERMETIC_TESTS: $2"
    echo "HERMETIC_ATTEMPTS: $3"
    [ -n "${4:-}" ] && echo "HERMETIC_NOTE: $4"
  } > e2e-screens/hermetic-summary.txt
  cat e2e-screens/hermetic-summary.txt
}

# ---- install: a fresh emulator has no app on it ---------------------------
./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace || {
  collect_artifacts
  write_summary "FAIL" "pass=0 total=0 fails=0 errors=0" 0 \
    "gradle install failed - the APKs never reached the emulator"
  exit 1
}

echo "=== GATE 1: HERMETIC (no live provider; deterministic; REQUIRED) ==="
h=$(run_gate); hcode=$?
printf '%s\n' "$h" > e2e-screens/hermetic-attempt-1.txt
printf '%s\n' "$h" | tail -15

if [ "$hcode" -eq 124 ]; then
  echo "HERMETIC GATE TIMED OUT (exit 124) - this is a HANG, not a quota failure"
  collect_artifacts
  write_summary "FAIL_TIMEOUT" "pass=0 total=0 fails=0 errors=0" 1 \
    "hermetic gate timed out (exit 124); the emulator or the app hung"
  adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
  adb uninstall com.tsfdroid.ai.test >/dev/null 2>&1 || true
  exit 2
fi

final="$h"; attempts=1
if [ -z "$h" ] || printf '%s\n' "$h" | grep -qE "FAILURES|INSTRUMENTATION_FAILED"; then
  echo "=== hermetic retry pass (both attempts preserved) ==="
  reinstall
  final=$(run_gate); fcode=$?
  attempts=2
  printf '%s\n' "$final" > e2e-screens/hermetic-attempt-2.txt
  printf '%s\n' "$final" | tail -15
fi

printf '%s\n' "$final" > e2e-screens/instrument-output.txt
counts=$(count_tests "$final")
collect_artifacts

total=$(extract_total "$counts")
fails_n=$(printf '%s\n' "$counts" | sed -nE 's/.*fails=([0-9]+).*/\1/p' | tail -1)
errors_n=$(printf '%s\n' "$counts" | sed -nE 's/.*errors=([0-9]+).*/\1/p' | tail -1)
status=FAIL
note=""
if printf '%s\n' "$final" | grep -qE "FAILURES|INSTRUMENTATION_FAILED"; then
  note="instrumentation reported failures (see attempts + logcat)"
elif [ -z "$final" ]; then
  note="NO INSTRUMENT OUTPUT - the tests did not run at all (outage, not a pass)"
elif [ "${fails_n:-0}" -gt 0 ] 2>/dev/null || [ "${errors_n:-0}" -gt 0 ] 2>/dev/null; then
  note="parsed $fails_n failure(s), $errors_n error(s) - the FAILURES!!! sentinel was not seen but the counts are red"
elif [ "$total" -lt "$HERMETIC_MIN_TESTS" ] 2>/dev/null; then
  note="only $total tests ran (floor is $HERMETIC_MIN_TESTS) - class discovery broke; not a pass"
else
  status=PASS
fi
write_summary "$status" "$counts" "$attempts" "$note"

adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
adb uninstall com.tsfdroid.ai.test >/dev/null 2>&1 || true

if [ "$status" = "PASS" ]; then exit 0; else exit 1; fi
