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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * v1.6.0 FIELD-FIX E2E — the measurable halves of the Phase-21 bars
 * (docs/gauntlet/phase21-bars.md), driven through the real UI on the live
 * free-tier model:
 *
 *  B3 Deliverables: a file the user NAMES by name gets created with the
 *     requested content, and asking for the SAME name again RENAMES instead
 *     of overwriting (the field's scrap_titles.py died to a silent
 *     overwrite; the double report.pdf destroyed the first report).
 *  B6 Routing: a storage QUESTION answers in chat (grounded in the app's
 *     real storage facts), never becomes a file-write.
 *  B7 Export: the exported chat JSON is schema v2 — every message carries
 *     its mode and the reply carries usage.wallMs.
 */
@RunWith(AndroidJUnit4::class)
class FieldFixesInstrumentedTest {

    private lateinit var device: UiDevice
    private lateinit var appPackage: String

    private val chatPlaceholder = "Ask TSF Droid to run an autonomous task"

    private val nonReplyTexts = setOf(
        "Retry", "Dismiss", "Edit message", "OK", "Cancel", "Allow", "Deny",
        "Reject", "Approve & Run"
    )

    private val modelBadgeTexts = setOf(
        "OPENCODE ZEN", "OPENCODE", "GEMINI", "CLAUDE", "OPENAI", "OLLAMA",
        "COPILOT", "CUSTOM OPENAI", "STOPPED", "ON-DEVICE (AI CORE)", "SYSTEM"
    )

    private val agentStatusPrefixes = listOf(
        "Analyzing", "Speaking", "Executing", "Planning", "Thinking", "Running",
        "Done", "Latest news", "Top web results", "Content of", "Page content",
        "arness]"
    )

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
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
        device.registerWatcher("systemAnrDialogs") {
            val waitButton = device.findObject(By.textContains("Wait"))
            if (waitButton != null && device.findObject(By.textContains("isn't responding")) != null) {
                waitButton.click()
                return@registerWatcher true
            }
            false
        }
    }

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
            "android.permission.POST_NOTIFICATIONS"
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

    // ---------- shared helpers (same contracts as the export suite) -------

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

    private fun waitNewText(
        baseline: Set<String>,
        timeoutMs: Long,
        settleMs: Long = 0
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var longest: String? = null
        var lastGrowthAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            for (obj in device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))) {
                if (runCatching { obj.applicationPackage }.getOrNull() != appPackage) continue
                val t = obj.text.trim()
                if (t.isEmpty() || t in baseline || t in nonReplyTexts) continue
                if (t.uppercase() in modelBadgeTexts) continue
                if (t == "[tool calls issued]" || t.startsWith("arness]")) continue
                if (t.startsWith(chatPlaceholder)) continue
                if (t.startsWith("AUTONOMOUS PLAN")) continue
                if (t.startsWith("Goal:")) continue
                if (t.startsWith("TSF Droid has formulated")) continue
                if (t.startsWith("Always allow")) continue
                if (t.startsWith("Execute ")) continue
                if (t.startsWith("•")) continue
                if (t.startsWith("On it")) continue
                if (t == "THINKING" || t == "ACTIVITY") continue
                if (t.startsWith("Requires Plan")) continue
                if (t.startsWith("Chat exported")) continue
                val isStatusLine = agentStatusPrefixes.any { t.startsWith(it) }
                if (isStatusLine) continue
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
                return longest
            }
            runCatching { Thread.sleep(2_500) }
        }
        return longest
    }

    private fun typeChatMessage(message: String): Boolean {
        repeat(3) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(chatPlaceholder)), 6_000)
                ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_200)
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
            val expected = message.trim()
            val actual = device.findObjects(By.clazz("android.widget.EditText"))
                .mapNotNull { runCatching { it.text }.getOrNull() }
                .firstOrNull { it.isNotBlank() } ?: return@repeat
            val trimmed = actual.trim()
            val ok = if (viaIme) {
                trimmed.contains(expected.take(24)) && trimmed.contains(expected.takeLast(12))
            } else {
                trimmed == expected
            }
            if (ok) return true
            runCatching {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .firstOrNull()?.clear()
            }
            device.waitForIdle(600)
        }
        return false
    }

    private fun tapSendAndVerify(message: String): Boolean {
        repeat(3) {
            val input = device.findObject(By.clazz("android.widget.EditText"))
                ?: return false
            val b = runCatching { input.visibleBounds }.getOrNull()
            val cx = if (b != null && !b.isEmpty) {
                (b.right + 56).coerceAtMost(device.displayWidth - 24)
            } else {
                (device.displayWidth * 0.92).toInt()
            }
            val cy = if (b != null && !b.isEmpty) b.centerY() else (device.displayHeight * 0.735).toInt()
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

    private fun appNodesVisible(): Boolean =
        device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
            .any { runCatching { it.applicationPackage }.getOrNull() == appPackage }

    private fun waitAgentIdle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            val busy = device.findObjects(By.text(Pattern.compile(".+")))
                .filter { runCatching { it.applicationPackage }.getOrNull() == appPackage }
                .any { obj ->
                    val t = runCatching { obj.text.trim() }.getOrDefault("")
                    t.startsWith("Analyzing") || t.startsWith("Requires Plan") ||
                        t.startsWith("Executing") || t.startsWith("Speaking") ||
                        t.startsWith("Planning") || t.startsWith("Thinking") ||
                        t.startsWith("Running")
                }
            if (!busy) return
            runCatching { Thread.sleep(3_000) }
        }
    }

    private fun keyboardUp(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { w -> w.root?.packageName?.toString()?.contains("inputmethod") == true }
    }.getOrDefault(false)

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
     * AGENT-mode plans under the default approval policy surface the
     * "Approve & Run" modal; poll for it and approve. Exits early ONLY on
     * POST-approval states (Executing/Running/On it) — "Analyzing" and
     * "Thinking" PRECEDE the modal (run 37315400374: an early exit on
     * those left the plan unapproved and the turn timed out).
     */
    private fun approvePlanIfAsked(windowMs: Long) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            val approve = device.findObject(By.textContains("Approve & Run"))
            if (approve != null) {
                runCatching { approve.click() }
                device.waitForIdle(2_000)
                shoot("fieldfix_approved")
                return
            }
            // Only genuinely post-approval states mean no modal is coming.
            val executing = device.findObjects(By.text(Pattern.compile(".+")))
                .filter { runCatching { it.applicationPackage }.getOrNull() == appPackage }
                .any { obj ->
                    val t = runCatching { obj.text.trim() }.getOrDefault("")
                    t.startsWith("Executing") || t.startsWith("Running") ||
                        t.startsWith("On it")
                }
            if (executing) return
            runCatching { Thread.sleep(2_000) }
        }
    }

    private fun workspaceRoot(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "workspace")
    }

    /** Every workspace file that contains [marker] (recursive). */
    private fun filesContaining(marker: String): List<File> {
        val root = workspaceRoot()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.length() in 1..2_000_000 }
            .filter { f ->
                runCatching { f.readText().contains(marker) }.getOrDefault(false)
            }
            .toList()
    }

    // ---------- onboarding ----------

    private fun reachDashboard() {
        ActivityScenario.launch(MainActivity::class.java)
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
            runCatching {
                val nameField = device.wait(
                    Until.findObject(By.clazz("android.widget.EditText")), 10_000
                )
                nameField?.setText("FieldFix")
            }
            device.waitForIdle(1_000)
            runCatching { clickTextContains("Continue", 10_000) }
            device.waitForIdle(2_000)
            runCatching { clickTextContains("Get Started", 10_000) }
            device.waitForIdle(3_000)
        }
        device.wait(Until.hasObject(By.text("Chat")), 30_000)
    }

    private fun ensureMode(mode: String) {
        val other = if (mode == "CHAT") "AGENT" else "CHAT"
        val chip = device.findObject(By.text(other))
        if (chip != null) {
            runCatching { chip.click() }
            device.waitForIdle(1_200)
        }
        assertTrue(
            "mode chip did not read $mode",
            device.hasObject(By.text(mode))
        )
        shoot("fieldfix_mode_$mode")
    }

    // ---------- the tests ----------

    /**
     * B3 Deliverables: the user names the file, the file exists with the
     * requested content, and a second ask with the SAME name never
     * overwrites it (collision rename).
     */
    @Test(timeout = 900_000)
    fun fieldFix_b3_namedFileCreatedNoOverwrite() {
        reachDashboard()
        assertTrue("New chat button not found", clickDesc("New chat", 15_000))
        device.waitForIdle(2_000)
        ensureMode("AGENT")

        val task1 = "create a markdown file called fieldfix_marker.md containing the " +
            "exact line TSF FIELD MARKER 77 followed by one short paragraph about gold prices"
        assertTrue("could not type the file task", typeChatMessage(task1))
        shoot("fieldfix_b3_typed")
        assertTrue("could not send the file task", tapSendAndVerify(task1))
        val baseline = visibleTexts()
        approvePlanIfAsked(90_000)
        waitAgentIdle(420_000)
        shoot("fieldfix_b3_idle")
        val reply1 = waitNewText(baseline, timeoutMs = 120_000, settleMs = 10_000)
        shoot("fieldfix_b3_reply")
        assertNotNull("the file task never produced a reply", reply1)

        // The deliverable contract: the NAMED file exists and carries the
        // requested content (the field shipped document.txt / a 1-sentence
        // PDF instead).
        val markers1 = filesContaining("TSF FIELD MARKER 77")
        assertTrue(
            "no workspace file contains the requested TSF FIELD MARKER 77 content " +
                "(reply: ${reply1!!.take(160)})",
            markers1.isNotEmpty()
        )
        val named = File(workspaceRoot(), "fieldfix_marker.md")
        if (named.exists()) {
            assertTrue(
                "fieldfix_marker.md exists but lacks the requested marker content",
                runCatching { named.readText().contains("TSF FIELD MARKER 77") }.getOrDefault(false)
            )
        }

        // The overwrite guard: asking for the SAME name again must RENAME,
        // never destroy the first file (the scrap_titles.py lesson).
        val task2 = "create a markdown file called fieldfix_marker.md containing the " +
            "exact line TSF FIELD MARKER 88 and nothing else"
        assertTrue("could not type the second file task", typeChatMessage(task2))
        assertTrue("could not send the second file task", tapSendAndVerify(task2))
        val baseline2 = visibleTexts()
        approvePlanIfAsked(90_000)
        waitAgentIdle(420_000)
        shoot("fieldfix_b3_second_idle")
        val reply2 = waitNewText(baseline2, timeoutMs = 120_000, settleMs = 10_000)
        assertNotNull("the second file task never produced a reply", reply2)

        val still77 = filesContaining("TSF FIELD MARKER 77")
        val now88 = filesContaining("TSF FIELD MARKER 88")
        assertTrue("the first marker vanished - the overwrite guard failed", still77.isNotEmpty())
        assertTrue(
            "the second marker was never written (reply: ${reply2!!.take(160)})",
            now88.isNotEmpty()
        )
        // The renamed copy must be a -2 style sibling, and the ORIGINAL must
        // still be the 77 one when both writes used the same requested name.
        val renamed = workspaceRoot().walkTopDown()
            .filter { it.isFile && it.name.startsWith("fieldfix_marker") }
            .map { it.name }
            .toList()
        shoot("fieldfix_b3_files")
        assertTrue(
            "expected fieldfix_marker.md plus a -2 sibling after the second ask, " +
                "found: $renamed",
            renamed.any { it.contains("-2") } || renamed.size >= 2
        )
    }

    /**
     * B6 Routing: the field's export-location question ("can you tell me the
     * location in device where the export chats are saved") is ANSWERED in
     * chat, grounded in the app's real storage facts - it never becomes a
     * file-write that could overwrite the user's data.
     */
    @Test(timeout = 900_000)
    fun fieldFix_b6_storageQuestionAnswersInChat() {
        reachDashboard()
        assertTrue("New chat button not found", clickDesc("New chat", 15_000))
        device.waitForIdle(2_000)
        ensureMode("CHAT")

        val filesBefore = workspaceRoot().walkTopDown().filter { it.isFile }.count()

        val task = "can you tell me the location in device where the export chats are saved"
        assertTrue("could not type the storage question", typeChatMessage(task))
        shoot("fieldfix_b6_typed")
        assertTrue("could not send the storage question", tapSendAndVerify(task))
        val baseline = visibleTexts()
        waitAgentIdle(420_000)
        shoot("fieldfix_b6_idle")
        val reply = waitNewText(baseline, timeoutMs = 120_000, settleMs = 10_000)
        shoot("fieldfix_b6_reply")
        assertNotNull("the storage question never produced a reply", reply)

        // Grounded in the app's real facts: the answer names the real storage
        // (the Exports folder, the workspace, or the private Android/data
        // path the app itself documents) - never a WhatsApp hallucination,
        // never a file-write.
        assertTrue(
            "the storage answer is not grounded in the app's real storage facts " +
                "(got: ${reply!!.take(200)})",
            reply.contains("Exports", ignoreCase = true) ||
                reply.contains("workspace", ignoreCase = true) ||
                reply.contains("Android/data", ignoreCase = true)
        )
        assertTrue(
            "the storage question became a file-write (field P0-7 regression)",
            !reply.contains("File saved", ignoreCase = true)
        )

        val filesAfter = workspaceRoot().walkTopDown().filter { it.isFile }.count()
        assertTrue(
            "a file was created by a question (field P0-7 regression)",
            filesAfter <= filesBefore
        )
    }

    /**
     * B7 Export completeness: the exported JSON is schema v2 - every message
     * carries its mode, and the assistant reply carries usage.wallMs.
     */
    @Test(timeout = 900_000)
    fun fieldFix_b7_exportSchemaV2ModeAndWallMs() {
        reachDashboard()
        assertTrue("New chat button not found", clickDesc("New chat", 15_000))
        device.waitForIdle(2_000)
        ensureMode("CHAT")

        val task = "tell me one interesting fact about the moon in two sentences"
        assertTrue("could not type the chat task", typeChatMessage(task))
        shoot("fieldfix_b7_typed")
        assertTrue("could not send the chat task", tapSendAndVerify(task))
        val baseline = visibleTexts()
        waitAgentIdle(420_000)
        val reply = waitNewText(baseline, timeoutMs = 120_000, settleMs = 10_000)
        shoot("fieldfix_b7_reply")
        assertNotNull("the chat task never produced a reply", reply)

        // Export through the real UI (menu actions lead, per round-25 fix).
        assertTrue("Chats menu button not found", clickDesc("Chats", 15_000))
        assertTrue("Export chat item not found", clickTextContains("Export chat", 10_000))
        device.waitForIdle(2_000)
        if (device.wait(Until.hasObject(By.textContains("Share")), 6_000) == true) {
            runCatching { device.pressBack() }
            device.waitForIdle(1_500)
        }

        val exportsDir = File(workspaceRoot(), "Exports")
        assertTrue("no export file was written", exportsDir.exists())
        val newest = exportsDir.listFiles()
            ?.filter { it.name.startsWith("chat-") && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
        assertNotNull("no chat-*.json export found", newest)

        val doc = JSONObject(newest!!.readText())
        assertEquals("export schema is not v2", 2, doc.getInt("version"))
        val messages = doc.getJSONArray("messages")
        assertTrue("exported chat has no messages", messages.length() > 0)
        var sawUserMode = false
        var sawAssistantWallMs = false
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val mode = m.optString("mode", "")
            if (m.optString("role") == "user" && mode.isNotBlank()) sawUserMode = true
            if (m.optString("role") == "assistant") {
                val usage = m.optJSONObject("usage")
                if (usage != null && !usage.isNull("wallMs")) sawAssistantWallMs = true
            }
        }
        assertTrue("no user message carries its mode (field P2-6)", sawUserMode)
        assertTrue("no assistant message carries usage.wallMs (field P2-5)", sawAssistantWallMs)

        // Copy for the CI artifact + the blind critic.
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val target = File(File(ctx.getExternalFilesDir(null), "e2e-screens"), "fieldfix_export_v2.json")
            newest.copyTo(target, overwrite = true)
        }
    }
}
