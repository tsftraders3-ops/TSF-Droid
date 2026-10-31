# EGM Drift Report — TSF Droid v2.0.0 Arc

*   Format per `egm-drift-report` SKILL.md: report ONLY differences;
    "No drift detected" when the code matches the design.
*   Baseline: `feature/egm-v2.0.0-arc` @ `4cd8d99` (Milestone 1 Phase B
    complete).

---

## 2026-10-31 — docs/designs/milestone_1_vision-driving-wiring.md (rev 2)

**No functional drift detected.** All 18 target files exist with the
specified names, signatures, and behaviors; all specified test cases are
implemented and green (1191 unit tests, 0 failures: 1143 pre-existing
untouched except the one mechanical File-17 constructor update, + 48 new).

Six implementation refinements, each traceable to a spec-internal
requirement or a mechanical necessity — none change specified behavior:

-   **`VisionGoalVerifier.screenCapture` seam added** (File 4): the spec's
    own Test 11 case `visionCheck_blackFrameCapture_isFalse` requires
    "`visionEngine.captureScreen()` faked to the FLAG_SECURE result"; a seam
    defaulting to the real `VisionEngine.captureScreen()` is the established
    pattern for that (VisionEngine seams). The production default path is
    identical to the spec's code.
-   **`VisionGoalVerifier.windowContextProvider` recycles the root node**
    (File 4): the spec's sketch read `rootInActiveWindow` without recycling
    (an AccessibilityNodeInfo leak); the implementation wraps the read in
    try/finally with `root.recycle()`. Semantically identical output.
-   **`DriveScreenGoalAction` is `internal`, not `private`** (File 6): the
    spec's Test 13 requires direct companion access
    (`isApproval` cases) and a "fake clock seam"
    (`staleConsentExpiredTtl_doesNotApprove`) — both need module-internal
    visibility of the nested class and its `nowProvider` seam. Behavior and
    placement (inside `AdvancedControlActions`) are unchanged.
-   **`stepsOf()` helper in `DriveScreenGoalAction`** (File 6):
    `VisionDriveResult` is a sealed class whose `steps` live per-subclass;
    the gesture-budget accumulation (`gesturesSpent += result.steps.size`)
    needs the exhaustive extractor. Purely mechanical.
-   **`requireVision = true` on the two vision LLM requests**
    (Files 4-5 vision fallback / bitmap locate): not spelled out in the
    spec's request sketches, but required by the `LLMRequest` contract for
    image-carrying calls (the established VisionEngine pattern).
-   **Two additive test cases beyond the spec's exact lists**
    (`render_multipleMarks_oneLinePerMark` in VisionScreenMarkTest,
    `currentScreenText_readsThroughTheScreenTextSeam` in
    VisionGoalVerifierTest): spec File 6 step 3 defines
    `currentScreenText()` and the renderer's one-line-per-mark contract;
    both additions pin that specified behavior directly. No production
    behavior added or changed.

Environment notes (build-time only, deliberately NOT committed):

-   `settings.gradle` carries a local-only Aliyun-maven-first repository
    order (sandbox mitigation: `repo.maven.apache.org` 429-throttles this
    environment). Uncommitted; CI uses the upstream order.
-   `local.properties` (gitignored) points at the sandbox Android SDK;
    `~/.gradle/gradle.properties` pins the JDK path and the
    memory-constrained kotlin daemon (sandbox has 3.9 GB RAM, no swap).
-   Robolectric `android-all-instrumented` artifacts for SDK 28/32/34/35/36
    pre-seeded into `~/.m2` from the Aliyun mirror.

Verification evidence (Phase B gate, this milestone):

-   `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL;
    112 test classes, **1191 tests, 0 failures, 0 errors**.
-   `./gradlew compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL (the
    File 16 hermetic test compiles; it executes in the CI hermetic gate,
    which needs an emulator this sandbox does not have).
-   `lintDebug` deferred to the Milestone-8 final acceptance gate per the
    spec's Verification Plan.
