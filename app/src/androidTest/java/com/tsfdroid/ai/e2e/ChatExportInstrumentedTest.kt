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
 * v1.4.0 chat export E2E — the feature the user's debugging loop depends on:
 * after a real agentic turn, one tap produces a raw-text (JSON) file carrying
 * EVERYTHING the model did, and the file is verifiably complete.
 *
 * This is the gauntlet's measurable half for the export feature:
 *  1. a REAL chat turn with live tool calls (fetch example.com in CHAT mode),
 *  2. "Export chat" tapped through the real UI (Chats menu),
 *  3. the produced JSON parsed ON DEVICE and every required field asserted:
 *     the user message, the assistant answer, the model identity, the full
 *     tool-call record (params + result + duration), the activity trace, the
 *     thinking key (present honestly even when the model didn't reason), and
 *     the session round-trip (message count matches the DB's own view),
 *  4. the file copied into the e2e-screens dir so the CI artifact carries it
 *     for the critic's blind comparison against the named bars.
 */
@RunWith(AndroidJUnit4::class)
class ChatExportInstrumentedTest {

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
        "[harness]"
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

    // ---------- helpers (same contracts as the capability suite) ----------

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
        extraExcluded: Set<String> = emptySet(),
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
                if (t.isEmpty() || t in baseline || t in nonReplyTexts || t in extraExcluded) continue
                if (t.uppercase() in modelBadgeTexts) continue
                if (t == "[tool calls issued]" || t.startsWith("[harness]")) continue
                if (t.startsWith(chatPlaceholder)) continue
                if (t.startsWith("AUTONOMOUS PLAN")) continue
                if (t.startsWith("Goal:")) continue
                if (t.startsWith("TSF Droid has formulated")) continue
                if (t.startsWith("Always allow")) continue
                if (t.startsWith("Execute ")) continue
                if (t.startsWith("•")) continue
                if (t == "THINKING" || t == "ACTIVITY") continue
                if (t.startsWith("Requires Plan")) continue
                if (t.startsWith("Chat exported")) continue // our own toast, never the reply
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

    /**
     * v1.4.0 export E2E: waits until the top-bar status leaves the busy
     * states (their capability suite's agentBusyOnScreen contract) — an
     * export taken mid-turn would slice the turn's tool log.
     */
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
            assertTrue("Let's Go button not found", clickTextContains("Let's Go", 15_000))
            device.wait(Until.hasObject(By.textContains("Grant Permissions")), 15_000)
            assertTrue("permissions panel button not found", clickTextContains("Grant Permissions", 15_000))
            device.wait(Until.hasObject(By.textContains("Proceed to")), 15_000)
            assertTrue("Proceed to agent button not found", clickTextContains("Proceed to", 15_000))
        }
        assertTrue(
            "dashboard (Chat tab) never appeared",
            device.wait(Until.hasObject(By.text("Chat")), 30_000) == true
        )
        device.waitForIdle(3_000)
        shoot("export_00_dashboard")
    }

    // ---------- the test ----------

    @Test(timeout = 900_000)
    fun chatExport_carriesTheWholeTurn() {
        reachDashboard()

        // A fresh chat isolates this turn from the rest of the suite's history.
        assertTrue("New chat button not found", clickDesc("New chat", 30_000))
        device.waitForIdle(2_000)

        // CHAT mode: the streamed -> harness-handoff path with native tool
        // calls (fetch_url is read-only allowed) — the path whose save sites
        // the v1.4.0 capture touches most. The chip must FLIP (default is
        // AGENT); staying in AGENT mode would route the task through plan
        // approval and change what this test verifies.
        val modeChip = device.findObject(By.text("AGENT"))
        if (modeChip != null) {
            runCatching { modeChip.click() }
            device.waitForIdle(1_200)
            assertTrue(
                "mode chip did not flip to CHAT mode",
                device.wait(Until.hasObject(By.text("CHAT")), 5_000) == true
            )
            shoot("export_01_chat_mode")
        } else {
            assertTrue(
                "neither AGENT nor CHAT chip visible — wrong screen",
                device.hasObject(By.text("CHAT"))
            )
        }

        // 2026 example.com reality check (round-26 forensics): the page no
        // longer carries an <h1> — it is one paragraph stating what the
        // domain is for. Asking for "the main heading" produced an HONEST
        // "there is no heading in the fetched result" reply. The task asks
        // for what the page actually says; the assert quotes its real body.
        val task = "Fetch https://example.com and tell me exactly what the page says this domain is for."
        assertTrue("could not type the export task", typeChatMessage(task))
        shoot("export_02_typed")
        assertTrue("could not send the export task", tapSendAndVerify(task))
        // Round-25 lesson (their cap22 round-23 fix applies here too): the
        // baseline is captured AFTER the send so the TYPED MESSAGE ITSELF can
        // never match as the reply — this task's text contains "example.com",
        // and the settle timer returned it ~a minute before the real grounded
        // answer while the status still read "Analyzing intent & planning…".
        val baseline = visibleTexts()

        // Round-26 lesson: the turn must be FINISHED before any reply is
        // trusted or exported — the settle timer alone can fire mid-stream
        // during a free-tier pause and capture a partial bubble (the 120-char
        // prefix had not yet reached the grounded quote). The agent's own
        // busy-state is the completion signal; the export taken after it
        // carries the whole turn, never a slice.
        waitAgentIdle(420_000)
        shoot("export_03_idle")

        // The final, complete reply — grounded in the REAL fetched body.
        val reply = waitNewText(baseline, timeoutMs = 120_000, settleMs = 10_000)
        shoot("export_04_reply")
        assertNotNull("the fetch task never produced a reply", reply)
        assertTrue(
            "reply is not grounded in the fetched page (got: ${reply!!.take(160)})",
            reply.contains("documentation examples", ignoreCase = true) && reply != task
        )

        // The export itself, through the real UI. The actions lead the menu
        // (round-25 UI fix) so they are visible without scrolling past the
        // session list — the suite's earlier classes leave 8+ chats behind.
        assertTrue("Chats menu button not found", clickDesc("Chats", 15_000))
        shoot("export_04_menu_open")
        assertTrue(
            "Export chat item not found in the Chats menu",
            clickTextContains("Export chat", 10_000)
        )
        shoot("export_05_tapped")

        // The share chooser covers the app on success — dismiss it ONLY while
        // it is actually up (a blind second back could exit the app to the
        // launcher); the FILE is the deliverable being verified either way.
        device.waitForIdle(2_000)
        if (device.wait(Until.hasObject(By.textContains("Share")), 6_000) == true) {
            runCatching { device.pressBack() }
            device.waitForIdle(1_500)
        }

        // The file must exist in workspace/Exports/ (the CI-pulled dir).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val exportsDir = File(ctx.getExternalFilesDir(null), "workspace/Exports")
        val exportFile = waitForExportFile(exportsDir, 30_000)
        assertNotNull("no export JSON appeared in ${exportsDir.absolutePath}", exportFile)
        shoot("export_06_file_present")

        // Parse ON DEVICE and assert completeness — the whole point.
        val doc = JSONObject(exportFile!!.readText())
        assertExportCompleteness(doc, task)

        // Leave the file where the CI artifact collection picks everything
        // up, plus a copy in e2e-screens named for the critic.
        val screens = File(ctx.filesDir, "e2e-screens").apply { mkdirs() }
        exportFile.copyTo(File(screens, "chat_export_verified.json"), overwrite = true)
        ctx.getExternalFilesDir(null)?.let {
            File(it, "e2e-screens").mkdirs()
            runCatching {
                exportFile.copyTo(
                    File(it, "e2e-screens/chat_export_verified.json"),
                    overwrite = true
                )
            }
        }
        shoot("export_07_done")

        // Restore AGENT mode — the stored setting is global and later classes
        // in the suite (and the next installs) expect the default. If a share
        // chooser is still covering the app, dismiss it first.
        runCatching {
            if (device.findObject(By.text("CHAT")) == null &&
                device.findObject(By.textContains("Share")) != null
            ) {
                device.pressBack()
                device.waitForIdle(1_500)
            }
            device.findObject(By.text("CHAT"))?.click()
            device.waitForIdle(1_000)
        }
    }

    /** Polls for a fresh chat-*.json in [dir]; returns the newest one, or null. */
    private fun waitForExportFile(dir: File, timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val newest = dir.listFiles { f -> f.name.startsWith("chat-") && f.name.endsWith(".json") }
                ?.maxByOrNull { it.lastModified() }
            if (newest != null && newest.length() > 0L) return newest
            runCatching { Thread.sleep(1_000) }
        }
        return dir.listFiles { f -> f.name.startsWith("chat-") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * The completeness contract, asserted from the parsed document: everything
     * the user demanded in the export — the message, the answer, the model,
     * the full tool-call record (params + result + duration), the activity
     * trace, honest thinking — or the test fails with the specific gap.
     */
    private fun assertExportCompleteness(doc: JSONObject, sentText: String) {
        assertEquals("format id", "tsfdroid-chat-export", doc.getString("format"))
        assertEquals("format version", 2, doc.getInt("version"))
        assertTrue("exportedAt stamp missing", doc.getJSONObject("exportedAt").has("iso8601"))
        assertTrue("app version missing", doc.getJSONObject("app").getString("versionName").isNotBlank())

        val session = doc.getJSONObject("session")
        val messages = doc.getJSONArray("messages")
        assertTrue("session must carry at least a user+assistant pair", messages.length() >= 2)
        assertEquals(
            "session.messageCount must round-trip the message array",
            messages.length(),
            session.getInt("messageCount")
        )

        // The user's message, verbatim.
        val userObj = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .firstOrNull { it.getString("role") == "user" && it.getString("text") == sentText }
        assertNotNull("the sent user message is missing from the export", userObj)
        assertTrue(
            "user message must carry the timestamp stamp",
            userObj!!.getJSONObject("timestamp").has("iso8601")
        )

        // The assistant reply, with everything the v1.4.0 capture records.
        val agentObj = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.getString("role") == "assistant" }
            .maxByOrNull { it.getJSONObject("timestamp").getLong("epochMs") }
        assertNotNull("no assistant message in the export", agentObj)
        val replyObj = agentObj!!
        assertTrue(
            "assistant text empty",
            replyObj.getString("text").isNotBlank()
        )

        val model = replyObj.getJSONObject("model")
        assertTrue(
            "model identity missing (provider empty and modelId null)",
            !model.isNull("provider") || !model.isNull("modelId")
        )
        assertTrue(
            "the concrete modelId must be recorded for the chat path (v1.4.0 capture)",
            !model.isNull("modelId") && model.getString("modelId").isNotBlank()
        )

        // thinking: the key must EXIST (honest null when the model didn't
        // reason); a reasoning model carries text + durationMs.
        assertTrue("thinking key missing", replyObj.has("thinking"))
        if (!replyObj.isNull("thinking")) {
            val thinking = replyObj.getJSONObject("thinking")
            if (!thinking.isNull("durationMs")) {
                assertTrue(
                    "thinking duration must be a positive number",
                    thinking.getLong("durationMs") > 0
                )
            }
        }

        // The tool-call log — the core of the debugging contract.
        val toolCalls = replyObj.getJSONArray("toolCalls")
        assertTrue(
            "the fetch turn recorded ZERO tool calls — the export would lie about what the agent did",
            toolCalls.length() > 0
        )
        val fetchCall = (0 until toolCalls.length())
            .map { toolCalls.getJSONObject(it) }
            .firstOrNull {
                it.getString("action") == "FETCH_URL" || it.getString("action") == "WEB_SEARCH"
            }
        assertNotNull("no FETCH_URL/WEB_SEARCH call in the tool log", fetchCall)
        val call = fetchCall!!
        assertTrue("tool params empty", call.getJSONObject("params").length() > 0)
        assertTrue(
            "tool result missing (neither result nor error)",
            !call.isNull("result") || !call.isNull("error")
        )
        assertTrue(
            "tool durationMs must be recorded",
            call.getLong("durationMs") >= 0
        )
        assertTrue(
            "tool startedAt stamp missing",
            call.getJSONObject("startedAt").has("epochMs")
        )
        if (!call.isNull("result")) {
            assertTrue(
                "FETCH result must actually contain the page's real body text",
                call.getString("result").contains("documentation examples", ignoreCase = true)
            )
        }

        // The visible activity trace rides along too.
        assertTrue(
            "activitySteps empty — the visible trace must survive the export",
            replyObj.getJSONArray("activitySteps").length() > 0
        )
    }
}
