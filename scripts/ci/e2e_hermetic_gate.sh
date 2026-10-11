#!/usr/bin/env bash
# REQUIRED HERMETIC E2E GATE — the release-blocking, deterministic truth.
#
# Runs on the emulator with FakeLLMProvider-seamed components: no external
# network provider is involved, so this gate cannot be starved or poisoned by
# endpoint quota. A green hermetic gate means the app's own machinery (launch,
# onboarding journey, dashboard navigation, new chat, agent pipeline seams,
# accessibility targeting, keystore crypto) works on a real device.
#
# Outputs:
#   e2e-screens/hermetic-attempt-1.txt   first pass, full `am instrument` stdout
#   e2e-screens/hermetic-attempt-2.txt   retry pass (only if attempt 1 failed)
#   e2e-screens/instrument-output.txt    final attempt's full stdout (QA report input)
#   e2e-screens/instrument-logcat.txt   full device logcat
#   e2e-screens/hermetic-summary.txt    machine-readable verdict (workflow reads this)
#
# Exit codes: 0 = PASS, 1 = FAIL, 2 = FAIL_TIMEOUT (a hang, never a quota issue).
set -u

CLASSES="com.tsfdroid.ai.e2e.AppLaunchInstrumentedTest,com.tsfdroid.ai.e2e.HermeticAgentPipelineInstrumentedTest,com.tsfdroid.ai.e2e.HermeticJourneyInstrumentedTest,com.tsfdroid.ai.accessibility.ClickTargetingAccuracyInstrumentationTest,com.tsfdroid.ai.accessibility.KnownScreenScrapeInstrumentationTest,com.tsfdroid.ai.accessibility.ServiceReadinessInstrumentationTest,com.tsfdroid.ai.core.security.AndroidKeyStoreAeadCipherInstrumentationTest,com.tsfdroid.ai.core.security.AndroidProviderCredentialStoreInstrumentationTest"
RUNNER="com.tsfdroid.ai.test/androidx.test.runner.AndroidJUnitRunner"

mkdir -p e2e-screens

reinstall() {
  adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
  adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null 2>&1
}

run_gate() {
  timeout 1500 adb shell am instrument -w -e class "$CLASSES" "$RUNNER"
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
if printf '%s\n' "$h" | grep -qE "FAILURES|INSTRUMENTATION_FAILED"; then
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

status=PASS
if printf '%s\n' "$final" | grep -qE "FAILURES|INSTRUMENTATION_FAILED"; then
  status=FAIL
fi
write_summary "$status" "$counts" "$attempts" ""

adb uninstall com.tsfdroid.ai >/dev/null 2>&1 || true
adb uninstall com.tsfdroid.ai.test >/dev/null 2>&1 || true

if [ "$status" = "PASS" ]; then exit 0; else exit 1; fi
