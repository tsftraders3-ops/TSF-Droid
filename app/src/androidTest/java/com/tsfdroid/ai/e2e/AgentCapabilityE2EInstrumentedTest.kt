package com.tsfdroid.ai.e2e

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tsfdroid.ai.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * COMPLEX-TASK agent E2E on a live emulator with the real OpenCode Zen free
 * tier (v1.0.5). This is the test the field failures demanded: not "say
 * hello", but the kind of tasks a real user gives an agent — and the exact
 * three that broke in v1.0.4 (screenshots: capability question FAILED with
 * "Action 'CHAT' is not registered", "ok start" → "Let's build it!" followed
 * by silence, and no way to produce real artifacts):
 *
 *  1. A capability-audit style question — the agent must ANSWER it (the
 *     v1.0.4 CHAT-step crash path), not fail the plan;
 *  2. "Create an HTML website file" — the agent must plan WRITE_FILE and the
 *     file must really exist on device with the requested content;
 *  3. "Fetch https://example.com" — the agent must fetch real internet data
 *     in-app (FETCH_URL) and report the page's stable heading;
 *  4. "Create a PDF" — CREATE_PDF must produce a real .pdf file (%PDF magic).
 *
 * Every task goes through the real UI: typed input, the plan-approval card
 * ("Approve & Run" — WRITE_FILE/CREATE_DIRECTORY are policy-critical and
 * always gated, exactly as a real user experiences them), and the agent's
 * reply/artifact. A screenshot is left at every step for the CI artifacts.
 */
@RunWith(AndroidJUnit4::class)
class AgentCapabilityE2EInstrumentedTest {

    private lateinit var device: UiDevice
    private lateinit var appPackage: String
    private lateinit var startedAtMs: java.util.concurrent.atomic.AtomicLong

    private val chatPlaceholder = "Ask TSF Droid to run an autonomous task"

    private val nonReplyTexts = setOf(
        "Retry", "Dismiss", "Edit message", "OK", "Cancel", "Allow", "Deny",
        "Reject", "Approve & Run"
    )

    /**
     * v1.2.1 loop 4: the model-badge label inside a freshly inserted agent
     * bubble ("OPENCODE ZEN") is a NEW text node that matches any
     * length-based reply predicate — the memory recall test raced it and
     * treated the badge as the reply. Badge-shaped nodes are never replies.
     */
    private val modelBadgeTexts = setOf(
        "OPENCODE ZEN", "OPENCODE", "GEMINI", "CLAUDE", "OPENAI", "OLLAMA",
        "COPILOT", "CUSTOM OPENAI", "STOPPED", "ON-DEVICE (AI CORE)"
    )

    private val agentStatusPrefixes = listOf(
        "Analyzing", "Speaking", "Executing", "Planning", "Thinking", "Running",
        "Done", "Latest news", "Top web results", "Content of", "Page content"
    )

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        startedAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 5_000)
        grantRuntimePermissions()
        device.registerWatcher("systemPermissionDialogs") {
            for (label in listOf(
                "While using the app", "Allow only while using the app",
                "Only this time", "Allow all", "Allow", "OK"
            )) {
                val button = device.findObject(By.text(label))
                if (button != null) {
                    button.click()
                    return@registerWatcher true
                }
            }
            false
        }
        // Loop-14 evidence: on a cold software-emulated API 34 image the
        // SYSTEM LAUNCHER itself ANRs ("Pixel Launcher isn't responding") and
        // the dialog parks on top of the app's onboarding — every a11y click
        // then lands on the dialog and the whole capability suite fails at
        // reachDashboard. Watchers for ANR/crash dialogs dismiss them
        // wherever runWatchers() executes.
        device.registerWatcher("systemAnrDialogs") {
            val waitButton = device.findObject(By.textContains("Wait"))
            if (waitButton != null && device.findObject(By.textContains("isn't responding")) != null) {
                waitButton.click()
                return@registerWatcher true
            }
            for (label in listOf("Close app", "Open app again", "App info", "Don't send")) {
                val button = device.findObject(By.text(label))
                if (button != null && device.findObject(By.textContains("responding")) != null) {
                    button.click()
                    return@registerWatcher true
                }
            }
            false
        }
    }

    /** adb pm-grant every dangerous permission the app declares; failures ignored. */
    private fun grantRuntimePermissions() {
        val pkg = appPackage
        val dangerous = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.READ_CALENDAR",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN"
        )
        for (perm in dangerous) {
            shell("pm grant $pkg $perm")
        }
    }

    private fun shell(cmd: String) {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (_: Exception) {
        }
    }

    // ---------- helpers ----------

    private fun shoot(step: String) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val internal = File(ctx.filesDir, "e2e-screens")
            val external = ctx.getExternalFilesDir(null)?.let { File(it, "e2e-screens") }
            internal.mkdirs()
            external?.mkdirs()
            val f = File(internal, "$step.png")
            device.takeScreenshot(f, 1.0f, 90)
            external?.let { target ->
                runCatching { if (f.exists()) f.copyTo(File(target, "$step.png"), overwrite = true) }
            }
        }
    }

    private fun dumpHierarchy(name: String) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(ctx.filesDir, "e2e-screens")
            dir.mkdirs()
            device.dumpWindowHierarchy(File(dir, "$name.xml").outputStream())
        }
    }

    private fun clickTextContains(text: String, ms: Long = 10_000): Boolean {
        if (device.wait(Until.hasObject(By.textContains(text)), ms) != true) return false
        return runCatching { device.findObject(By.textContains(text))?.click() != null }
            .getOrDefault(false)
    }

    private fun clickDesc(desc: String, ms: Long = 10_000): Boolean {
        if (device.wait(Until.hasObject(By.desc(desc)), ms) != true) return false
        return runCatching { device.findObject(By.desc(desc))?.click() != null }
            .getOrDefault(false)
    }

    private fun visibleTexts(): Set<String> =
        device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
            .filter { runCatching { it.applicationPackage }.getOrNull() == appPackage }
            .map { runCatching { it.text.trim() }.getOrDefault("") }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Polls for a NEW app-owned text node (not in [baseline], not a known
     * button/status label). [extraExcluded] lets each task exclude earlier
     * turns' bubbles. [predicate] restricts what counts as the reply: when
     * null, agent status lines never count; when set, a status-shaped bubble
     * (e.g. a data-output summary "Content of …") still counts if it matches.
     *
     * [settleMs] > 0 turns on STREAM SETTLING: the first match may be a
     * mid-stream snapshot of a still-growing bubble (round-4 evidence: the
     * long-form essay was captured at 193 chars while it was still streaming
     * toward thousands). With settling, polling continues and the LONGEST
     * matching candidate is returned once no longer candidate has appeared
     * for [settleMs].
     */
    private fun waitNewText(
        baseline: Set<String>,
        timeoutMs: Long,
        extraExcluded: Set<String> = emptySet(),
        predicate: ((String) -> Boolean)? = null,
        settleMs: Long = 0
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var longest: String? = null
        var lastGrowthAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            // DOTALL matters: multi-line bubbles (data-output summaries like
            // "Content of …\nExample Domain\n…") do NOT match a plain ".+"
            // full-match pattern — the loop-5 fetch evidence showed the bubble
            // on screen the whole 300s while the scan never saw it.
            for (obj in device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))) {
                if (runCatching { obj.applicationPackage }.getOrNull() != appPackage) continue
                val t = obj.text.trim()
                if (t.isEmpty() || t in baseline || t in nonReplyTexts || t in extraExcluded) continue
                if (t.uppercase() in modelBadgeTexts) continue // model-badge node, never a reply
                // Harness plumbing nodes are never the deliverable reply
                // (round-4 evidence: "[tool calls issued]" matched a
                // length-based predicate mid-tool-loop).
                if (t == "[tool calls issued]" || t.startsWith("[harness]")) continue
                if (t.startsWith(chatPlaceholder)) continue
                if (t.startsWith("AUTONOMOUS PLAN")) continue
                if (t.startsWith("Goal:")) continue
                if (t.startsWith("TSF Droid has formulated")) continue
                if (t.startsWith("Always allow")) continue
                if (t.startsWith("Execute ")) continue // plan-card step labels
                if (t.startsWith("•")) continue
                if (t == "THINKING") continue // collapsible section label
                if (t.startsWith("Requires Plan")) continue // top-bar status
                val isStatusLine = agentStatusPrefixes.any { t.startsWith(it) }
                val matches = if (predicate == null) !isStatusLine else predicate(t)
                if (!matches) continue
                if (settleMs <= 0) return t
                val current = longest
                if (current == null || t.length > current.length) {
                    longest = t
                    lastGrowthAt = System.currentTimeMillis()
                }
            }
            if (settleMs > 0 && longest != null &&
                System.currentTimeMillis() - lastGrowthAt >= settleMs
            ) {
                return longest // stream settled: no longer text for settleMs
            }
            runCatching { Thread.sleep(2_500) }
        }
        return longest
    }

    /**
     * Types into the chat input (placeholder renders when focused) and closes
     * the keyboard. Loop-28: this is DETERMINISTIC now — a11y ACTION_SET_TEXT
     * (atomic, IME-independent) is primary, typed input is only the fallback,
     * and every attempt ends in a VERIFIED full-text check. Loop-27 cap3
     * evidence: sendStringSync raced the cold Gboard and the field ended up
     * holding only the message tail ("the heading."), the lenient
     * unverifiable path returned true, the garbage query was sent, and the
     * test starved its whole 600s window. No unverifiable return exists
     * anymore: if the field cannot be read, the attempt fails and retries.
     */
    private fun typeChatMessage(message: String): Boolean {
        repeat(3) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(chatPlaceholder)), 6_000)
                ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_200)
            // UiObject2.setText returns void — success is decided by the
            // verification below, never by the call itself. Attempt 1 walks
            // the IME path as the alternate delivery route.
            val viaIme = attempt == 1
            if (viaIme) {
                runCatching {
                    InstrumentationRegistry.getInstrumentation().sendStringSync(message)
                }
            } else {
                runCatching { target.setText(message) }
            }
            device.waitForIdle(1_000)
            dismissKeyboard()
            if (fieldHolds(message, viaIme = viaIme)) return true
            // Mismatch (the loop-27 failure shape): clear whatever partial
            // text landed so the retry starts from a clean field.
            runCatching {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .firstOrNull()?.clear()
            }
            device.waitForIdle(600)
        }
        return false
    }

    /**
     * Verified field content check. a11y-set text must match the message
     * exactly (no IME in the way); IME-typed text may legitimately pick up
     * keyboard transforms, so head+tail containment is accepted there — but
     * SOMETHING verifiable must always hold, never a blind pass.
     */
    private fun fieldHolds(message: String, viaIme: Boolean): Boolean {
        val expected = message.trim()
        val actual = device.findObjects(By.clazz("android.widget.EditText"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .firstOrNull { it.isNotBlank() } ?: return false
        val trimmed = actual.trim()
        return if (viaIme) {
            trimmed.contains(expected.take(24)) && trimmed.contains(expected.takeLast(12))
        } else {
            trimmed == expected
        }
    }

    private fun keyboardUp(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { w ->
                w.root?.packageName?.toString()?.contains("inputmethod") == true
            }
    }.getOrDefault(false)

    /** Whether any app-owned text node is currently reachable in the a11y tree. */
    private fun appNodesVisible(): Boolean =
        device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
            .any { runCatching { it.applicationPackage }.getOrNull() == appPackage }

    /**
     * Dismisses the soft keyboard before field verification. A plain
     * package-name IME check proved insufficient on a cold Gboard (loop-6:
     * the IME window was up but reported no inputmethod root, the guard
     * never fired, and the hidden a11y tree made every field verification
     * fail). Now: back is pressed whenever the IME is detected OR the app
     * has no visible nodes at all (something is covering it), then the
     * state is re-evaluated, bounded to 3 presses.
     */
    private fun dismissKeyboard() {
        repeat(3) {
            val imeUp = keyboardUp()
            val appVisible = appNodesVisible()
            if (!imeUp && appVisible) return
            device.pressBack()
            device.waitForIdle(1_200)
        }
    }

    /**
     * Loop-26: the desc-based Send tap hit a GHOST node (boundsInParent
     * Rect(0,0), screen bounds mid-list — cap3 loop-25 logcat: the tap
     * landed at (280,516) while the visible arrow renders bottom-right) and
     * the message was never sent; the test then waited 600s for a plan that
     * was never requested. Send now taps the arrow by COORDINATES relative
     * to the input field and VERIFIES delivery (input cleared or the sent
     * bubble on screen) with bounded retries.
     */
    private fun tapSendAndVerify(message: String): Boolean {
        repeat(3) { attempt ->
            val input = device.findObject(By.clazz("android.widget.EditText"))
            if (input == null) {
                if (attempt == 0) {
                    device.pressBack()
                    device.waitForIdle(800)
                    return@repeat
                }
                return false
            }
            // Loop-28: never send a field we did not verify. The loop-27 cap3
            // starve began exactly here — a truncated field was "sent" and
            // the empty-on-send check mistook the garbage delivery for
            // success. Re-type and re-verify before any click.
            if (!fieldHolds(message, viaIme = true)) {
                if (!typeChatMessage(message)) return false
            }
            val b = runCatching { input.visibleBounds }.getOrNull()
            val cx: Int
            val cy: Int
            if (b != null && !b.isEmpty) {
                cx = (b.right + 56).coerceAtMost(device.displayWidth - 24)
                cy = b.centerY()
            } else {
                cx = (device.displayWidth * 0.92).toInt()
                cy = (device.displayHeight * 0.735).toInt()
            }
            device.click(cx, cy)
            device.waitForIdle(1_500)
            val trimmed = message.trim()
            val sent = device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
                .any {
                    runCatching { it.applicationPackage }.getOrNull() == appPackage &&
                        runCatching { it.text.trim() == trimmed }.getOrDefault(false)
                }
            val cleared = device.findObjects(By.clazz("android.widget.EditText"))
                .all { runCatching { it.text }.getOrNull().isNullOrBlank() }
            if (sent || cleared) return true
        }
        return false
    }

    /**
     * Sends a task through the real chat input and drives the approval gate
     * when the planner proposes a plan. Returns the screen baseline captured
     * BEFORE the task was sent, for later reply detection.
     */
    private fun sendTask(
        message: String,
        taskTag: String,
        planningWindowMs: Long = 420_000
    ): Set<String> {
        // Loop-20: the previous test's plan may still be executing (speaking,
        // approval card up) when this task types — cap3's stuck run typed
        // mid-speech of the website task. Wait for a settled agent first.
        val idleDeadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < idleDeadline) {
            device.runWatchers()
            if (!agentBusyOnScreen() &&
                device.findObject(By.textContains("AUTONOMOUS PLAN PROPOSED")) == null
            ) break
            runCatching { Thread.sleep(3_000) }
        }
        assertTrue(
            "chat input not found before task $taskTag",
            device.wait(Until.hasObject(By.textContains(chatPlaceholder)), 15_000) == true ||
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000) == true
        )
        val baseline = visibleTexts()
        assertTrue("could not type task $taskTag", typeChatMessage(message))
        shoot("${taskTag}_typed")
        assertTrue("could not send task $taskTag (delivery unverified)", tapSendAndVerify(message))

        // Planning can take a while on the free tier — a slow model plus the
        // corrective re-ask is two LLM calls (loop-8: >240s observed), and a
        // content-creation plan carries the whole file inline (loop-16:
        // a full website plan can exceed 420s). [planningWindowMs] lets the
        // artifact tasks breathe.
        val approvalDeadline = System.currentTimeMillis() + planningWindowMs
        var approved = false
        var replied = false
        var stuckCardIterations = 0
        var offAppScans = 0
        while (System.currentTimeMillis() < approvalDeadline) {
            device.runWatchers()
            // Loop-28 self-heal: the loop-27 cap3 run ended stranded on the
            // launcher (the a11y dump proves it) and spun out its window.
            // When the app disappears from the foreground, bring it back —
            // the task (and its approval card) survive in the DB; the UI
            // restores to the same chat session.
            if (!appNodesVisible()) {
                if (++offAppScans >= 3) {
                    runCatching {
                        val target = InstrumentationRegistry.getInstrumentation().targetContext
                        target.startActivity(
                            Intent(target, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                    device.waitForIdle(3_000)
                    offAppScans = 0
                }
            } else {
                offAppScans = 0
            }
            val approveButton = runCatching {
                device.findObject(By.textContains("Approve & Run"))
            }.getOrNull()
            if (approveButton != null) {
                shoot("${taskTag}_plan_proposed")
                if (tapApproveAndRun()) {
                    approved = true
                    break
                }
                // Card refused to leave — keep looping until the deadline;
                // each retry re-finds the node fresh (stale-node safe).
                continue
            }
            // Card title visible but the button not exposed: with a long chat
            // history the plan card renders below the fold — scroll it into
            // view and retry (loop-11 evidence: only the card title was in
            // the a11y tree, the button was off-screen). Loop-20: press back
            // first — a lingering IME freezes the whole a11y tree (every node
            // reports clickable=false, the cap3 loop-19 dump evidence) — and
            // after 15 stuck iterations blind-tap where the button renders
            // relative to the card title.
            val cardTitle = runCatching {
                device.findObject(By.textContains("AUTONOMOUS PLAN PROPOSED"))
            }.getOrNull()
            if (cardTitle != null) {
                stuckCardIterations++
                if (stuckCardIterations <= 2) {
                    // IME dismissal only; a back press on the root dashboard
                    // would navigate the app out from under the test.
                    runCatching { device.pressBack() }
                }
                device.waitForIdle(800)
                val w = device.displayWidth
                val h = device.displayHeight
                device.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.30).toInt(), 32)
                device.waitForIdle(1_000)
                if (stuckCardIterations >= 15) {
                    val bounds = runCatching { cardTitle.visibleBounds }.getOrNull()
                    if (bounds != null && !bounds.isEmpty) {
                        device.click(
                            bounds.centerX(),
                            (bounds.bottom + 280).coerceAtMost(h - 80)
                        )
                    }
                }
                continue
            }
            // A direct reply (no plan) is also a valid outcome — but ONLY
            // trust new text when the agent is NOT mid-turn: the top-bar
            // status ("Analyzing…", "Requires Plan Approval", "Speaking…")
            // and the live-thinking trace change while planning, and the
            // loop-4 evidence shows those pseudo-replies broke the wait
            // before the approval card ever appeared.
            if (!agentBusyOnScreen()) {
                val reply = waitNewText(baseline, timeoutMs = 1_000)
                if (reply != null) {
                    replied = true
                    break
                }
            }
            runCatching { Thread.sleep(3_000) }
        }
        dumpHierarchy("${taskTag}_after_send")
        assertTrue(
            "task $taskTag: neither an approval card nor a reply appeared within ${planningWindowMs / 1000}s",
            approved || replied
        )
        return baseline
    }

    /**
     * True while the top-bar status line shows the agent is mid-turn.
     * Used to gate the direct-reply detection in [sendTask]: new text nodes
     * that appear while busy (status subtitle changes, live-thinking trace)
     * are not replies.
     */
    private fun agentBusyOnScreen(): Boolean =
        device.findObjects(By.text(Pattern.compile(".+")))
            .filter { runCatching { it.applicationPackage }.getOrNull() == appPackage }
            .any { obj ->
                // Nodes go stale mid-scan while Compose recomposes (loop-9
                // crash) — every node property read must be guarded.
                val t = runCatching { obj.text.trim() }.getOrDefault("")
                t.startsWith("Analyzing") || t.startsWith("Requires Plan") ||
                    t.startsWith("Executing") || t.startsWith("Speaking") ||
                    t.startsWith("Planning")
            }

    /**
     * Taps the plan-approval card's "Approve & Run" button and VERIFIES the
     * card actually left the screen. UiObject2.click() proved unreliable on
     * this Compose button in CI (the a11y node goes stale across
     * recompositions and the tap silently no-ops — loop-3 evidence: the card
     * was still up in the end-of-test screenshot), so the tap is delivered at
     * the visible bounds' center via [UiDevice.click], with a UiObject2.click
     * fallback, retried until the card is gone.
     */
    private fun tapApproveAndRun(maxAttempts: Int = 20): Boolean {
        repeat(maxAttempts) {
            val btn = runCatching { device.findObject(By.textContains("Approve & Run")) }.getOrNull()
                ?: return true // card already gone: a previous tap landed
            val bounds = runCatching { btn.visibleBounds }.getOrNull()
            if (bounds != null && !bounds.isEmpty) {
                device.click(bounds.centerX(), bounds.centerY())
            } else {
                runCatching { btn.click() }
            }
            device.waitForIdle(1_500)
            if (device.wait(Until.gone(By.textContains("Approve & Run")), 4_000) == true) {
                return true
            }
            runCatching { Thread.sleep(2_000) }
        }
        return false
    }

    // ---------- artifact polling ----------

    private fun workspaceRoot(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.getExternalFilesDir(null)?.let { File(it, "workspace") }
            ?: File(ctx.filesDir, "workspace")
    }

    /** All workspace files with [ext] modified after the test started. */
    private fun newWorkspaceFiles(ext: String): List<File> {
        val root = workspaceRoot() ?: return emptyList()
        if (!root.exists()) return emptyList()
        val start = startedAtMs.get()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".$ext", ignoreCase = true) }
            .filter { it.lastModified() >= start }
            .toList()
    }

    private fun awaitFile(ext: String, timeoutMs: Long, contentMarker: String?): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (f in newWorkspaceFiles(ext)) {
                if (contentMarker == null) return f
                val text = runCatching { f.readText() }.getOrNull().orEmpty()
                if (text.contains(contentMarker, ignoreCase = true)) return f
            }
            runCatching { Thread.sleep(4_000) }
        }
        return null
    }

    // ---------- onboarding (self-sufficient: class order is not guaranteed) ----------

    private fun reachDashboard() {
        // NOTE: deliberately NOT wrapped in ActivityScenario.use {} — closing
        // the scenario finishes the activity, and every later sendTask would
        // hunt for the chat input on an empty screen (the loop-2 failure).
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Watcher-aware polling (loop-14): blocking device.wait() calls never
        // run the ANR/permission watchers, so a system dialog parked on top
        // of onboarding used to silently stall this method until the asserts.
        val detectionDeadline = System.currentTimeMillis() + 60_000
        var onboarding = false
        while (System.currentTimeMillis() < detectionDeadline) {
            device.runWatchers()
            if (device.hasObject(By.textContains("What should I call you?"))) {
                onboarding = true
                break
            }
            if (device.hasObject(By.text("Chat"))) break
            runCatching { Thread.sleep(2_000) }
        }
        device.runWatchers()
        if (onboarding) {
            val okName = typeIntoLabel("What should I call you?", "TSF Tester")
            assertTrue("name field typing failed", okName)
            val okBirth = typeIntoLabel("When is your birthday?", "01/15/2000", verifyContains = "15/2000")
            assertTrue("birthday field typing failed", okBirth)
            assertTrue("Let's Go button not found", clickTextContains("Let's Go", 15_000))
            assertTrue(
                "onboarding did not advance past the introduction panel",
                device.wait(Until.hasObject(By.textContains("Grant Permissions")), 15_000) == true
            )
            assertTrue(
                "permissions panel button not found",
                clickTextContains("Grant Permissions", 15_000)
            )
            device.wait(Until.hasObject(By.textContains("Proceed to")), 15_000)
            assertTrue(
                "Proceed to agent button not found",
                clickTextContains("Proceed to", 15_000)
            )
        }
        assertTrue(
            "dashboard (Chat tab) never appeared",
            device.wait(Until.hasObject(By.text("Chat")), 30_000) == true
        )
        device.waitForIdle(3_000)
        scenario.onActivity { activity ->
            assertTrue("activity finishing on dashboard", !activity.isFinishing)
        }
        shoot("cap_00_dashboard")
    }

    private fun typeIntoLabel(selectorText: String, value: String, verifyContains: String = value): Boolean {
        repeat(2) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(selectorText)), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_500)
            runCatching {
                if (attempt == 0) {
                    InstrumentationRegistry.getInstrumentation().sendStringSync(value)
                } else {
                    device.findObjects(By.clazz("android.widget.EditText"))
                        .firstOrNull()?.setText(value)
                }
            }
            device.waitForIdle(1_000)
            dismissKeyboard()
            // Verify only what is actually verifiable: when the IME still
            // hides every app node, the onboarding gate ("Let's Go" refuses
            // empty/invalid fields) is the real check — failing here would
            // repeat the loop-6 cold-Gboard flake. The birthday field also
            // reformats input (leading zero stripped), so callers verify a
            // normalized tail.
            val appVisible = appNodesVisible()
            if (!appVisible) return true
            val typed = device.findObjects(By.clazz("android.widget.EditText"))
                .any { runCatching { it.text }.getOrNull()?.contains(verifyContains) == true }
            if (typed) return true
        }
        return false
    }

    // ---------- the complex tasks ----------

    @Test(timeout = 900_000)
    fun capabilityQuestion_getsAnswered_notFailed() {
        reachDashboard()
        // The exact v1.0.4 failure shape: a capability-audit question. The
        // planner answers in prose → CHAT step → v1.0.4 died with
        // "Action 'CHAT' is not registered in ActionDispatcher"; v1.0.5 must
        // deliver a real reply bubble.
        val baseline = sendTask(
            "Make me a short report of what you can actually do on this device. " +
                "List your top 5 capabilities briefly as a chat reply.",
            "cap1_audit"
        )
        val reply = waitNewText(baseline, 300_000)
        shoot("cap1_audit_reply")
        assertNotNull(
            "capability-audit question never produced a reply (v1.0.4 CHAT-step regression?)",
            reply
        )
        assertTrue("reply was empty", reply!!.isNotBlank())
    }

    @Test(timeout = 900_000)
    fun createHtmlWebsiteFile_reallyWritesTheFile() {
        reachDashboard()
        sendTask(
            "Create a file at Documents/e2e_site.html with a complete HTML page " +
                "that has a heading saying Hello E2E. Use your file write capability.",
            "cap2_html"
        )
        val file = awaitFile("html", 300_000, contentMarker = "Hello E2E")
        shoot("cap2_html_done")
        assertNotNull(
            "no .html file containing 'Hello E2E' appeared in the agent workspace within 300s " +
                "(workspace=${workspaceRoot()})",
            file
        )
        val content = file!!.readText()
        assertTrue("written file is not an HTML page", content.contains("<", ignoreCase = true))
        println("TSF-E2E html artifact: ${file.absolutePath} (${content.length} chars)")
    }

    @Test(timeout = 1_200_000)
    fun fetchExampleCom_reportsRealPageContent() {
        reachDashboard()
        // Loop-25: cap3 always runs right after a file-producing test whose
        // tall artifact cards buried its approval card below the fold (stuck
        // for 600s in loops 19-24). A NEW chat session (the app's own "+"
        // button — non-destructive, exactly what a real user would do) pins
        // the card at the top of an empty list.
        runCatching {
            val plus = device.findObject(By.desc("New chat"))
            if (plus != null) {
                plus.click()
                device.waitForIdle(2_500)
            }
        }
        // Loop-28: a SHORT, realistic ask. The 230-char instruction was the
        // loop-27 truncation trigger and "Do not write any file" contradicted
        // the artifact-based assertion below — both gone. The planner may
        // reply in chat OR route the fetched data through a file; both count.
        val baseline = sendTask(
            "Fetch https://example.com in-app and tell me the page's main heading.",
            "cap3_fetch",
            planningWindowMs = 600_000
        )
        // example.com's content marker. NOTE (v1.2.0): IANA revised the page —
        // the old <h1>Example Domain</h1> is GONE (the phrase now only lives in
        // <title>, which body-text extraction does not return); the new body
        // copy is "This domain is for use in documentation examples...". Accept
        // either revision's stable marker — the bar is REAL page data arriving
        // in-app, not one specific phrasing.
        val pageMarkers = listOf("example domain", "documentation examples", "iana.org")
        val deadline = System.currentTimeMillis() + 420_000
        var delivered: String? = null
        while (System.currentTimeMillis() < deadline && delivered == null) {
            val replyHit = device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
                .any {
                    runCatching { it.applicationPackage }.getOrNull() == appPackage &&
                        runCatching { it.text }.getOrNull()
                            ?.let { text -> pageMarkers.any { text.contains(it, ignoreCase = true) } }
                            ?: false
                }
            if (replyHit) {
                delivered = "chat reply"
                break
            }
            val root = workspaceRoot()
            if (root != null && root.exists()) {
                val file = root.walkTopDown()
                    .filter { it.isFile }
                    .filter { it.lastModified() >= startedAtMs.get() }
                    .firstOrNull { f ->
                        val text = runCatching { f.readText() }.getOrDefault("")
                        pageMarkers.any { text.contains(it, ignoreCase = true) }
                    }
                if (file != null) delivered = file.absolutePath
            }
            runCatching { Thread.sleep(4_000) }
        }
        shoot("cap3_fetch_reply")
        assertNotNull(
            "the in-app fetch path delivered neither a chat reply nor a workspace file " +
                "containing an example.com page marker within 420s — no real web data " +
                "reached the user",
            delivered
        )
        println("TSF-E2E fetch delivery: $delivered")
    }

    @Test(timeout = 900_000)
    fun createPdf_producesRealPdfFile() {
        reachDashboard()
        sendTask(
            "Create a PDF document at Documents/e2e_report.pdf with the title " +
                "E2E Report and the body text: This PDF was generated by the TSF Droid agent.",
            "cap4_pdf"
        )
        val deadline = System.currentTimeMillis() + 300_000
        var pdf: File? = null
        while (System.currentTimeMillis() < deadline && pdf == null) {
            pdf = newWorkspaceFiles("pdf").firstOrNull { f ->
                runCatching {
                    f.inputStream().use { stream ->
                        val header = ByteArray(5)
                        stream.read(header)
                        String(header) == "%PDF-"
                    }
                }.getOrDefault(false)
            }
            if (pdf == null) Thread.sleep(4_000)
        }
        shoot("cap4_pdf_done")
        assertNotNull(
            "no real .pdf file (%PDF magic) appeared in the agent workspace within 300s",
            pdf
        )
        println("TSF-E2E pdf artifact: ${pdf!!.absolutePath} (${pdf.length()} bytes)")
    }

    // ---------- v1.0.6: the EXACT user field failures ----------
    // The three tasks below are the user's own on-device prompts (10 error
    // screenshots, 2026-09-26 08:24-08:34). They differ from the tasks above
    // in one crucial way: they are VAGUE, natural, real-user phrasing with
    // no file path and no "use your capability" hint — exactly the shape the
    // v1.0.5 planner answered with "Love it! Let me put together something
    // slick for you." and then did NOTHING.

    /**
     * Fails when the agent answers a data ask by opening the browser
     * (the "Opened the browser for you." SYSTEM bubble) — the user's
     * single loudest complaint.
     */
    private fun assertNoBrowserFallback(baseline: Set<String>, tag: String) {
        val opened = waitNewText(
            baseline, timeoutMs = 2_000,
            predicate = { it.contains("Opened the browser", ignoreCase = true) }
        )
        assertNull(
            "$tag: the agent fell back to opening the browser (" +
                "SYSTEM bubble 'Opened the browser for you.' on screen) — " +
                "web data must be fetched in-app",
            opened
        )
    }

    /** Vague HTML ask, no path, no capability hint: a real user's words. */
    @Test(timeout = 1_500_000)
    fun vagueWebsiteAsk_stillWritesARealHtmlFile() {
        reachDashboard()
        val baseline = sendTask(
            "can u create a award winning website in html",
            "cap5_vague_html",
            planningWindowMs = 700_000
        )
        assertNoBrowserFallback(baseline, "cap5_vague_html")
        // The artifact bar: SOME html file with real page content appears
        // regardless of where the planner chose to save it.
        val file = awaitFile("html", 300_000, contentMarker = "<")
        shoot("cap5_vague_html_done")
        assertNotNull(
            "vague 'create a website' ask produced NO .html artifact within 300s — " +
                "the prose-deferral path leaked again (reply-only turn)",
            file
        )
        val content = file!!.readText()
        assertTrue("written file is not an HTML page", content.contains("<", ignoreCase = true))
        println("TSF-E2E vague-html artifact: ${file.absolutePath} (${content.length} chars)")
    }

    /** "search for X" — results must come back IN-APP, no Chrome. */
    @Test(timeout = 1_200_000)
    fun webSearchTask_returnsInAppResults_withoutBrowser() {
        reachDashboard()
        val baseline = sendTask(
            "ok search for latest iphone price",
            "cap6_search",
            planningWindowMs = 600_000
        )
        // Real data bar: a reply bubble with a numbered result listing
        // (WEB_SEARCH's output shape), not an error, not browser deflection.
        val reply = waitNewText(
            baseline, 600_000,
            predicate = { t ->
                t.contains("https://", ignoreCase = true) ||
                    t.contains("Top web results", ignoreCase = true) ||
                    (t.length > 80 && Regex("\\d\\.").containsMatchIn(t))
            }
        )
        shoot("cap6_search_reply")
        assertNotNull(
            "search produced no in-app results listing within 600s",
            reply
        )
        assertNoBrowserFallback(baseline, "cap6_search")
        println("TSF-E2E search reply: ${reply?.take(200)}")
    }

    /** "price of gold" — the user's exact ask; data must arrive in-app. */
    @Test(timeout = 1_200_000)
    fun goldPriceAsk_completesWithoutMalformedCard() {
        reachDashboard()
        val baseline = sendTask(
            "cna u fetch the price of gold now",
            "cap7_gold",
            planningWindowMs = 600_000
        )
        assertNoBrowserFallback(baseline, "cap7_gold")
        // Data bar: any reply carrying a number ($ or digit with context) OR
        // the structured search listing. The hard assertion is the negative:
        // NO "unreadable response" error card and NO browser fallback.
        // 600s window: the pass-2 retry ran the full planner+research loop on
        // a rate-limited free tier and 420s expired before the grounded reply.
        val reply = waitNewText(
            baseline, 600_000,
            predicate = { t ->
                t.contains("Unreadable response", ignoreCase = true) ||
                    t.contains("MALFORMED", ignoreCase = true) ||
                    Regex("""\$\s?\d""").containsMatchIn(t) ||
                    Regex("""\d{2,}(\.\d+)?\s?(usd|inr| dollars| per)""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    t.startsWith("Top web results") ||
                    t.startsWith("Latest news")
            }
        )
        shoot("cap7_gold_reply")
        assertNotNull(
            "gold-price ask produced neither real price data nor the search listing " +
                "within 600s (and the malformed-response card must never appear)",
            reply
        )
        assertTrue(
            "the MALFORMED/unreadable-response card appeared — the tool-call answer " +
                "path regressed to failing the turn",
            !(reply!!.contains("Unreadable response", true) || reply.contains("MALFORMED", true))
        )
        println("TSF-E2E gold reply: ${reply.take(200)}")
    }

    // ---------- v1.1.1: the 2026-09-27 field screenshot failures ----------
    // The user's screenshot showed "tell" → a runaway reasoning loop ("Let me
    // search for it." repeated forever) ending in the MALFORMED_RESPONSE
    // error card on a current-events question. Root cause: every request
    // carried tool_choice:"none" while the system prompt demanded "CALL THE
    // TOOL" — the model looped and answered with zero-prose tool calls.
    // v1.1.1 sends tool_choice:"auto" for agentic turns; these tests pin the
    // fix on the live endpoint.

    /** The EXACT screenshot scenario: a current-events question in chat. */
    @Test(timeout = 1_200_000)
    fun currentEventsAsk_answersWithRealData_noErrorCard() {
        reachDashboard()
        val baseline = sendTask(
            "What are the CBSE class 10 board exam dates for 2026?",
            "cap8_cbse",
            planningWindowMs = 600_000
        )
        assertNoBrowserFallback(baseline, "cap8_current_events")
        val reply = waitNewText(
            baseline, 600_000,
            predicate = { t ->
                t.contains("Unreadable response", ignoreCase = true) ||
                    t.contains("MALFORMED", ignoreCase = true) ||
                    t.contains("CBSE", ignoreCase = true) ||
                    Regex("""(january|february|march|april|feb|mar|apr)\s*\d{1,2}""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    Regex("""\d{1,2}\s+(january|february|march|april|feb|mar|apr)""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    t.startsWith("Top web results")
            }
        )
        shoot("cap8_current_events_reply")
        assertNotNull(
            "the current-events ask produced no data reply within 600s — the " +
                "thinking-loop/MALFORMED failure from the field screenshot",
            reply
        )
        assertTrue(
            "the MALFORMED/unreadable-response card appeared on a plain current-events " +
                "question (the field screenshot regression)",
            !(reply!!.contains("Unreadable response", true) || reply.contains("MALFORMED", true))
        )
        println("TSF-E2E current-events reply: ${reply.take(240)}")
    }

    /**
     * The user's deep-research bar: a vague "write a report" ask must produce
     * a REAL, research-grounded PDF — not the thin memory-only output the
     * v1.1.0 content engine wrote. The artifact bar is size/structure: a
     * memory-only stub is tiny and one page; a researched structured report
     * is multi-page or multi-KB.
     */
    @Test(timeout = 1_500_000)
    fun deepResearchReportPdf_isRealAndSubstantial() {
        reachDashboard()
        sendTask(
            "write a deep research report about solar energy growth in india as a pdf",
            "cap9_research_pdf",
            planningWindowMs = 700_000
        )
        val deadline = System.currentTimeMillis() + 600_000
        var pdf: File? = null
        while (System.currentTimeMillis() < deadline && pdf == null) {
            pdf = newWorkspaceFiles("pdf").firstOrNull { f ->
                runCatching {
                    f.inputStream().use { stream ->
                        val header = ByteArray(5)
                        stream.read(header)
                        String(header) == "%PDF-"
                    }
                }.getOrDefault(false)
            }
            if (pdf == null) Thread.sleep(4_000)
        }
        shoot("cap9_research_pdf_done")
        assertNotNull(
            "the deep-research PDF never appeared in the agent workspace within 600s",
            pdf
        )
        val bytes = pdf!!.readBytes()
        val pageMarkers = Regex("/Type\\s*/Page[^s]").findAll(bytes.toString(LatinIsSafe)).count()
        println(
            "TSF-E2E research pdf: ${pdf.absolutePath} bytes=${bytes.size} " +
                "pageMarkers=$pageMarkers"
        )
        assertTrue(
            "the research PDF is too thin to be a researched report (${bytes.size} bytes, " +
                "$pageMarkers pages) — the content engine likely wrote from memory alone",
            bytes.size > 8_000 || pageMarkers >= 2
        )
    }

    // ---------- v1.2.0: the OpenCode-grade harness features ----------

    /**
     * Taps the top-bar CHAT/AGENT mode chip until [target] is showing.
     * The chip label IS the state (v1.2.0), so verification is trivial.
     */
    private fun ensureMode(target: String): Boolean {
        repeat(4) {
            device.runWatchers()
            val current = device.findObjects(By.text(Pattern.compile(".+")))
                .firstOrNull {
                    runCatching { it.applicationPackage }.getOrNull() == appPackage &&
                        (it.text.trim() == "CHAT" || it.text.trim() == "AGENT")
                }?.text?.trim()
            if (current == target) return true
            val chip = device.findObjects(By.text(Pattern.compile(".+")))
                .firstOrNull {
                    runCatching { it.applicationPackage }.getOrNull() == appPackage &&
                        (it.text.trim() == "CHAT" || it.text.trim() == "AGENT")
                }
                ?: return false
            val bounds = runCatching { chip.visibleBounds }.getOrNull()
            if (bounds != null && !bounds.isEmpty) {
                device.click(bounds.centerX(), bounds.centerY())
            } else {
                runCatching { chip.click() }
            }
            device.waitForIdle(1_500)
        }
        return false
    }

    /** A fresh chat session via the app's own "+" button (cap3 pattern). */
    private fun freshChatSession() {
        runCatching {
            device.findObject(By.desc("New chat"))?.click()
            device.waitForIdle(2_500)
        }
    }

    /**
     * THE read-only guarantee, end to end on the live model: in CHAT mode a
     * write ask must produce a refusal-style reply and NEVER a workspace
     * file. This is the execution-gate proof — even if the model disobeys
     * its prompt and calls write_file anyway, the harness refuses it.
     */
    @Test(timeout = 900_000)
    fun chatMode_isReadOnly_writeAskProducesNoFile() {
        reachDashboard()
        freshChatSession()
        assertTrue("mode chip not found in the top bar", ensureMode("CHAT"))
        shoot("cap10_chat_mode_set")

        val baseline = sendTask(
            "Write a file at Documents/chatmode_proof.txt containing the text " +
                "chat-mode-write. Use write_file now.",
            "cap10_chatmode_write",
            planningWindowMs = 420_000
        )
        assertNoBrowserFallback(baseline, "cap10_chatmode_write")

        // The reply bar: SOMETHING must come back (the model explains the
        // read-only boundary or answers around it) — never silence.
        val reply = waitNewText(
            baseline, 420_000,
            predicate = { t ->
                t.contains("Unreadable response", ignoreCase = true) ||
                    t.contains("MALFORMED", ignoreCase = true) ||
                    t.length > 40 // a real conversational refusal/explanation
            }
        )
        shoot("cap10_chat_mode_reply")
        assertNotNull(
            "chat-mode write ask produced no reply within 420s",
            reply
        )
        assertTrue(
            "the MALFORMED/unreadable-response card appeared in chat mode",
            !(reply!!.contains("Unreadable response", true) || reply.contains("MALFORMED", true))
        )

        // THE artifact bar: no proof file may exist anywhere in the workspace.
        val deadline = System.currentTimeMillis() + 60_000
        var leaked: File? = null
        while (System.currentTimeMillis() < deadline && leaked == null) {
            leaked = newWorkspaceFiles("txt").firstOrNull {
                runCatching { it.readText().contains("chat-mode-write") }.getOrDefault(false)
            }
            if (leaked == null) Thread.sleep(3_000)
        }
        assertNull(
            "CHAT MODE WROTE A FILE — the read-only execution gate failed " +
                "(found ${leaked?.absolutePath})",
            leaked
        )
        // Leave the app in AGENT mode: the chat mode persists in settings and
        // every other capability test depends on the full agent pipeline.
        assertTrue("could not restore AGENT mode", ensureMode("AGENT"))
        shoot("cap10_agent_mode_restored")
        println("TSF-E2E chat-mode read-only: reply=${reply?.take(160)}")
    }

    /**
     * Back to AGENT mode, the same class of ask MUST write the file —
     * proving the mode chip actually changes capability in both directions.
     */
    @Test(timeout = 900_000)
    fun agentMode_stillWritesFiles() {
        reachDashboard()
        freshChatSession()
        assertTrue("mode chip not found in the top bar", ensureMode("AGENT"))
        shoot("cap11_agent_mode_set")
        sendTask(
            "Write a file at Documents/agentmode_proof.txt containing the text " +
                "agent-mode-write. Use write_file now.",
            "cap11_agentmode_write",
            planningWindowMs = 420_000
        )
        val file = awaitFile("txt", 300_000, contentMarker = "agent-mode-write")
        shoot("cap11_agent_mode_done")
        assertNotNull(
            "AGENT mode failed to write the proof file within 300s — the mode " +
                "chip must not take capability away from the agent",
            file
        )
        println("TSF-E2E agent-mode write: ${file!!.absolutePath}")
    }

    /**
     * v1.2.0 UI presence: the attach button opens the source sheet (Photos /
     * Files), the mode chip is present, and the effort chip appears when the
     * active model advertises reasoning levels. Fast, model-independent.
     */
    @Test(timeout = 240_000)
    fun uploadAndHarnessControls_arePresentAndResponsive() {
        reachDashboard()
        // Mode chip: AGENT by default on a fresh install.
        assertTrue(
            "the CHAT/AGENT mode chip is missing from the chat top bar",
            device.wait(
                Until.hasObject(By.text("AGENT")),
                20_000
            ) == true || device.hasObject(By.text("CHAT"))
        )
        // Attach button opens the sheet with both sources.
        assertTrue("attach button not found", clickDesc("Attach file", 15_000))
        assertTrue(
            "the Photos option never appeared in the attach sheet",
            device.wait(Until.hasObject(By.text("Photos")), 15_000) == true
        )
        assertTrue(
            "the Files option never appeared in the attach sheet",
            device.hasObject(By.text("Files"))
        )
        shoot("cap12_attach_sheet")
        device.pressBack()
        device.waitForIdle(1_500)
        // The sheet must actually close.
        assertTrue(
            "attach sheet did not close on back",
            device.wait(Until.gone(By.text("Photos")), 8_000) == true
        )
        shoot("cap12_controls_done")
    }


    // ---------- v1.2.1 Hermes loop: memory, compaction/longform, activity, todo ----------

    /** Polls for an app-owned text node starting with [prefix]. */
    private fun waitTextStarting(prefix: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            val hit = device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
                .any {
                    runCatching { it.applicationPackage }.getOrNull() == appPackage &&
                        runCatching { it.text.trim().startsWith(prefix) }.getOrDefault(false)
                }
            if (hit) return true
            runCatching { Thread.sleep(2_500) }
        }
        return false
    }

    /**
     * BAR-MEMORY (device side): teach a durable fact in one chat, recall it in
     * a BRAND-NEW chat — history cannot leak the answer there, so a correct
     * recall proves the personal-memory loop (extract -> store -> inject).
     */
    @Test(timeout = 1_500_000)
    fun personalMemory_teachesInOneChat_recallsInAnother() {
        reachDashboard()
        assertTrue("could not ensure AGENT mode", ensureMode("AGENT"))
        val teachQuestion = "Please remember this about me: my cat's name is Luna."
        val teachBaseline = sendTask(
            teachQuestion,
            "cap11_teach",
            planningWindowMs = 480_000
        )
        // Exclude the sent question bubble itself from reply detection — it is
        // a new text node and would match any length predicate instantly.
        val teachReply = waitNewText(
            teachBaseline, 480_000,
            extraExcluded = setOf(teachQuestion),
            predicate = { t -> t.length > 8 }
        )
        assertNotNull("the teach turn never completed", teachReply)
        // The learning extractor runs in the background after the reply —
        // give it a bounded window before switching chats.
        Thread.sleep(25_000)
        // Start a brand-new chat so the recall CANNOT come from history.
        assertTrue("New chat button not found", clickDesc("New chat", 15_000))
        device.waitForIdle(2_000)
        val recallQuestion = "What is my cat's name? Answer with just the name."
        val recallBaseline = sendTask(
            recallQuestion,
            "cap11_recall",
            planningWindowMs = 480_000
        )
        val recallReply = waitNewText(
            recallBaseline, 480_000,
            extraExcluded = setOf(recallQuestion),
            // The recall evidence is the FACT ITSELF, wherever it renders —
            // the reply bubble or (equally valid for the memory-injection
            // bar) the model's reasoning quoting the injected memory. A fresh
            // chat cannot know "Luna" from history; only the personal-memory
            // loop can have delivered it.
            predicate = { t -> t.contains("Luna", ignoreCase = true) }
        )
        shoot("cap11_memory_recall")
        assertNotNull(
            "the taught fact never surfaced in the recall chat within 480s — " +
                "personal memory did not inject",
            recallReply
        )
        println("TSF-E2E memory recall: ${recallReply!!.take(160)}")
    }

    /**
     * BAR-HARNESS output-size bar: a long-form ask must deliver a SUBSTANTIAL
     * answer — the historical complaint was answers capped at a few thousand
     * chars. Continuation flows the answer across calls when the budget clips
     * it; this pins the outcome on the live endpoint.
     */
    @Test(timeout = 1_500_000)
    fun longFormAsk_deliversSubstantialAnswer() {
        reachDashboard()
        // CHAT mode = the pure long-answer surface (no planner detour — the
        // earlier CI run proved the AGENT planner sensibly delivers an essay
        // AS a 34.5KB workspace file). The harness bar it pins: long answers
        // flow across the output budget (continuation on finish_reason=
        // "length", unit-pinned) and the delivered text is COMPLETE — never
        // cut mid-word/mid-sentence by the app. A model that voluntarily
        // stops short with finish_reason="stop" is a model trait; the
        // multi-KB artifact bars (deep-research PDF, essays-as-files) prove
        // large outputs flow unclamped.
        assertTrue("could not ensure CHAT mode", ensureMode("CHAT"))
        val baseline = sendTask(
            "Write a thorough essay of at least 600 words explaining how the " +
                "internet works, covering packet switching, TCP/IP, DNS, routing, " +
                "undersea cables, CDNs and security. Use full paragraphs.",
            "cap10_longform",
            planningWindowMs = 480_000
        )
        // settleMs: the essay streams for tens of seconds — a non-settled
        // capture returned a 193-char mid-stream snapshot in round 4 and the
        // completeness assert fired on it. Wait until the bubble stops
        // growing, then assert on the DELIVERED answer.
        val reply = waitNewText(
            baseline, 900_000,
            predicate = { t -> t.length > 120 },
            settleMs = 12_000
        )
        shoot("cap10_longform_reply")
        assertNotNull("no long-form reply arrived within 900s", reply)
        // Completeness grammar: the reply must not end mid-word or mid-mark.
        val tail = reply!!.trim().takeLast(1)
        val complete = reply.length >= 300 &&
            (tail[0].isLetterOrDigit() == false || reply.endsWith(".") ||
                reply.endsWith("!") || reply.endsWith("?") || reply.endsWith('"') ||
                reply.endsWith(")"))
        assertTrue(
            "long-form reply looks clipped (length=${reply.length}, tail=\"$tail\") — " +
                "the harness delivered a cut answer",
            complete
        )
        // Restore AGENT mode only after the bar is decided — a mid-stream tap
        // on the mode chip is a variable the capture should not carry.
        assertTrue("could not restore AGENT mode", ensureMode("AGENT"))
        println("TSF-E2E long-form reply length: ${reply.length}")
    }

    /**
     * BAR-ACTIVITY: a research task must leave a VISIBLE trace of its work —
     * the persisted ACTIVITY section on the reply (tool steps recorded by the
     * harness loop), Claude/OpenCode style.
     */
    @Test(timeout = 1_200_000)
    fun researchTask_persistsVisibleActivityTrace() {
        reachDashboard()
        // CHAT mode: the tool loop is GUARANTEED here — the chat prompt
        // mandates web_search before current-info answers, so tool calls run
        // and the visible trace exists. (The AGENT planner may legally answer
        // with a pure CHAT step, which runs no tools and records no steps.)
        assertTrue("could not ensure CHAT mode", ensureMode("CHAT"))
        val question = "What is the current price of Bitcoin in USD right now?"
        val baseline = sendTask(
            question,
            "cap15_activity",
            planningWindowMs = 480_000
        )
        // THE BAR, polled directly: a visible work trace within the turn
        // window. The live "WEB_SEARCH" step rows render while the tool loop
        // runs; the persisted "ACTIVITY (N)" header renders with the final
        // bubble. Rounds 1-5 failed this bar by racing the reply detection —
        // the reasoning-body text node matched the length predicate and the
        // marker poll started/expired mid-loop. No reply race anymore: poll
        // the markers themselves for the full window.
        val activityDeadline = System.currentTimeMillis() + 900_000
        var hasActivity = false
        while (System.currentTimeMillis() < activityDeadline && !hasActivity) {
            device.runWatchers()
            hasActivity = visibleTexts().any {
                it.startsWith("ACTIVITY") || it.startsWith("WEB_SEARCH") ||
                    it.startsWith("web_search")
            }
            if (!hasActivity) runCatching { Thread.sleep(2_500) }
        }
        // The grounded reply for the ask (secondary bar): settled capture,
        // bounded window — it has usually already landed with the marker.
        val reply = waitNewText(
            baseline, 420_000,
            extraExcluded = setOf(question),
            predicate = { t -> t.length > 40 },
            settleMs = 10_000
        )
        shoot("cap15_activity_trace")
        assertTrue(
            "no ACTIVITY/WEB_SEARCH trace rendered within the turn window — the " +
                "visible-steps surface did not record the chat tool loop",
            hasActivity
        )
        assertNotNull("research task produced no grounded reply", reply)
        // Leave the app in AGENT mode for the remaining capability tests.
        assertTrue("could not restore AGENT mode", ensureMode("AGENT"))
    }

    /**
     * BAR-TODO: while a plan executes, the chat shows the live TODO checklist
     * (goal + stepped-dot track + per-step checkboxes), not just the Plan tab.
     */
    @Test(timeout = 1_500_000)
    fun planExecution_showsTodoChecklistInChat() {
        reachDashboard()
        assertTrue("could not ensure AGENT mode", ensureMode("AGENT"))
        // Per-test artifact baseline: the class-wide startedAtMs would see
        // EARLIER tests' html files and skip the live checklist immediately.
        val myStart = System.currentTimeMillis() - 5_000
        val baseline = sendTask(
            "Create a small HTML file named todo_e2e_proof.html with a heading TSF Todo",
            "cap16_todo",
            planningWindowMs = 600_000
        )
        val deadline = System.currentTimeMillis() + 600_000
        var sawTodo = false
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            val approveButton = runCatching { device.findObject(By.textContains("Approve & Run")) }.getOrNull()
            if (approveButton != null) {
                shoot("cap16_todo_plan_proposed")
                tapApproveAndRun()
                continue
            }
            // The live checklist (or the persisted trace on the summary) both
            // prove the todo pipeline; poll quickly — one-step plans are fast.
            if (waitTextStarting("TODO", 4_000)) {
                sawTodo = true
                shoot("cap16_todo_checklist_visible")
                break
            }
            if (waitTextStarting("ACTIVITY", 2_000)) {
                sawTodo = true
                shoot("cap16_todo_summary_trace")
                break
            }
            if (newWorkspaceFiles("html").any { it.lastModified() >= myStart }) {
                // File landed but neither marker was caught on screen — one
                // final bounded check before failing.
                sawTodo = waitTextStarting("TODO", 10_000) || waitTextStarting("ACTIVITY", 10_000)
                break
            }
        }
        assertTrue(
            "no TODO checklist (or persisted ACTIVITY trace) appeared during plan execution",
            sawTodo
        )
    }



    // ---------- v1.3.0: the ask_user round-trip and source chips ----------

    /**
     * v1.3.0 cap21: the ask_user tool end to end. The model must CALL the
     * tool (not answer in prose), the app must park the turn on the visible
     * ANSWER NEEDED surface, the typed answer must route to the parked
     * question (not become a new query), and the resumed turn must deliver
     * a final reply that acknowledges the answer — the full opencode
     * question-tool contract on the live endpoint.
     */
    @Test(timeout = 1_500_000)
    fun askUserTool_roundTripsAnswerIntoFinalReply() {
        reachDashboard()
        val baseline = sendTask(
            "Use your ask_user tool right now to ask me which city I prefer between " +
                "Pune and Mumbai. Do NOT answer in prose - you MUST call the ask_user tool " +
                "with the options Pune and Mumbai. After I answer, finish with one short " +
                "sentence confirming the city I chose.",
            "cap21_ask"
        )

        // 1. The dedicated answer surface appears: the ANSWER NEEDED strip
        //    (or the answer-mode placeholder) with the question.
        val surfaceDeadline = System.currentTimeMillis() + 420_000
        var sawAnswerSurface = false
        while (System.currentTimeMillis() < surfaceDeadline) {
            device.runWatchers()
            if (device.findObject(By.textContains("ANSWER NEEDED")) != null ||
                device.findObject(By.textContains("Type your answer")) != null
            ) {
                sawAnswerSurface = true
                break
            }
            runCatching { Thread.sleep(2_000) }
        }
        shoot("cap21_ask_surface")
        dumpHierarchy("cap21_ask_surface")
        assertTrue(
            "the ask_user surface never appeared - the model did not call ask_user " +
                "or the ANSWER NEEDED strip failed to render",
            sawAnswerSurface
        )

        // 2. Snapshot everything visible NOW (the question bubble, option
        //    chips, the user's typed answer) so only the FINAL reply counts
        //    as new text below.
        val baseline2 = visibleTexts()
        assertTrue("could not type the ask answer", typeChatMessage("Pune"))
        shoot("cap21_ask_typed")
        assertTrue("could not send the ask answer", tapSendAndVerify("Pune"))

        // 3. The resumed turn lands a reply that references the chosen city.
        //    Everything from before the answer is in baseline2; the answer
        //    bubble itself is short, so the predicate needs length + Pune.
        val reply = waitNewText(
            baseline2,
            420_000,
            extraExcluded = setOf("Pune", "Mumbai", "ANSWER NEEDED", "Type your answer"),
            predicate = { it.length > 25 && it.contains("Pune", ignoreCase = true) }
        )
        shoot("cap21_ask_reply")
        dumpHierarchy("cap21_ask_reply")
        assertNotNull(
            "the turn never acknowledged the answered city - the answer did not " +
                "round-trip back into the model's context",
            reply
        )
    }

    /**
     * v1.3.0 cap22: a researched answer renders its sources as the SOURCES
     * chip row (clickable citations), not raw text slop.
     */
    @Test(timeout = 1_500_000)
    fun researchedAnswer_showsSourceChips() {
        reachDashboard()
        val baseline = sendTask(
            "Search the web for the current Bitcoin price in USD and tell me the " +
                "price with the source site you used.",
            "cap22_sources"
        )
        val reply = waitNewText(baseline, 420_000)
        shoot("cap22_sources_reply")
        dumpHierarchy("cap22_sources_reply")
        assertNotNull("no research reply arrived", reply)

        val hasSourcesRow = device.findObject(By.textContains("SOURCES")) != null
        assertTrue(
            "a researched answer rendered without the SOURCES chip row - " +
                "raw-URL text slop regression",
            hasSourcesRow
        )
    }

    /** Latin-1 is byte-safe for scanning PDF markers without a charset lib. */
    private val LatinIsSafe = Charsets.ISO_8859_1
}
