package com.tsfdroid.ai.e2e

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
     */
    private fun waitNewText(
        baseline: Set<String>,
        timeoutMs: Long,
        extraExcluded: Set<String> = emptySet(),
        predicate: ((String) -> Boolean)? = null
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
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
                if (predicate == null) {
                    if (isStatusLine) continue
                } else {
                    if (!predicate(t)) continue
                }
                return t
            }
            runCatching { Thread.sleep(2_500) }
        }
        return null
    }

    /** Types into the chat input (placeholder renders when focused) and closes the keyboard. */
    private fun typeChatMessage(message: String): Boolean {
        repeat(2) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(chatPlaceholder)), 6_000)
                ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_500)
            runCatching {
                if (attempt == 0) {
                    InstrumentationRegistry.getInstrumentation().sendStringSync(message)
                } else {
                    device.findObjects(By.clazz("android.widget.EditText"))
                        .firstOrNull()?.setText(message)
                }
            }
            device.waitForIdle(1_000)
            dismissKeyboard()
            // Same lenient verification as typeIntoLabel: a covering IME makes
            // the field unverifiable — the send + reply flow is the real gate.
            val appVisible = appNodesVisible()
            if (!appVisible) return true
            val typed = device.findObjects(By.clazz("android.widget.EditText"))
                .any { runCatching { it.text }.getOrNull()?.contains(message.take(24)) == true }
            if (typed) return true
        }
        return false
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

    private fun tapSend(): Boolean {
        if (clickDesc("Send", 3_000)) return true
        device.pressBack()
        device.waitForIdle(1_000)
        return clickDesc("Send", 8_000)
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
        assertTrue("send button not found for task $taskTag", tapSend())

        // Planning can take a while on the free tier — a slow model plus the
        // corrective re-ask is two LLM calls (loop-8: >240s observed), and a
        // content-creation plan carries the whole file inline (loop-16:
        // a full website plan can exceed 420s). [planningWindowMs] lets the
        // artifact tasks breathe.
        val approvalDeadline = System.currentTimeMillis() + planningWindowMs
        var approved = false
        var replied = false
        var stuckCardIterations = 0
        while (System.currentTimeMillis() < approvalDeadline) {
            device.runWatchers()
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
                runCatching { device.pressBack() }
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
        val baseline = sendTask(
            "Fetch the web page https://example.com with your URL fetch capability, " +
                "then REPLY IN CHAT with the main heading text shown on that page. " +
                "Do not write any file — just tell me the heading.",
            "cap3_fetch",
            planningWindowMs = 600_000
        )
        // example.com's content is stable: the page heading is "Example Domain".
        val reply = waitNewText(
            baseline, 600_000,
            predicate = { it.contains("Example Domain", ignoreCase = true) }
        )
        shoot("cap3_fetch_reply")
        assertNotNull(
            "no reply mentioning 'Example Domain' appeared within 600s — " +
                "the in-app fetch path did not deliver real web data",
            reply
        )
        println("TSF-E2E fetch reply: $reply")
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
        val reply = waitNewText(
            baseline, 420_000,
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
                "within 420s (and the malformed-response card must never appear)",
            reply
        )
        assertTrue(
            "the MALFORMED/unreadable-response card appeared — the tool-call answer " +
                "path regressed to failing the turn",
            !(reply!!.contains("Unreadable response", true) || reply.contains("MALFORMED", true))
        )
        println("TSF-E2E gold reply: ${reply.take(200)}")
    }
}
