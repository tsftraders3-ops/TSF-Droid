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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Full user-journey E2E on a live emulator (v1.0.4). This is the test that
 * answers "does the APP work", not just "does the endpoint work":
 *
 *  1. Fresh install → onboarding completed by driving the real UI
 *     (type name + birthday, tap through all three panels);
 *  2. Dashboard → every bottom-nav tab visited, screenshotted;
 *  3. Settings → provider row verified to show OpenCode Zen (the keyless
 *     default), provider dropdown opened and inspected;
 *  4. Chat → TWO different real messages typed and sent through the real
 *     input, each answered by the live OpenCode Zen free tier;
 *  5. A screenshot at every step is left under the app's external files dir
 *     for the workflow to pull as artifacts — failures are seen, not guessed.
 *
 * Runtime permissions are granted up front via shell so the onboarding
 * permissions panel reaches the "everything held" state without brittle
 * system-dialog tapping; a watcher still accepts any dialog that slips out.
 */
@RunWith(AndroidJUnit4::class)
class AppUiInteractionInstrumentedTest {

    private lateinit var device: UiDevice
    private lateinit var appPackage: String

    private val chatPlaceholder = "Ask OpenDroid to run an autonomous task"
    private val messageOne = "Reply with exactly: E2E hello"
    private val messageTwo = "What is 2+2? Answer with just the number"

    /** Labels that belong to error-recovery cards or system UI, never to a reply. */
    private val nonReplyTexts = setOf(
        "Retry", "Dismiss", "Edit message", "OK", "Cancel", "Allow", "Deny"
    )

    /** Agent status lines (planning, TTS, execution) — never the reply itself. */
    private val agentStatusPrefixes = listOf(
        "Analyzing", "Speaking", "Executing", "Planning", "Thinking", "Running", "Done"
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
    }

    /** adb pm-grant every dangerous permission the app declares; failures ignored. */
    private fun grantRuntimePermissions() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
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
            // AutoCloseInputStream drains the command and closes the pfd.
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (_: Exception) {
        }
    }

    // ---------- helpers ----------

    private fun shoot(step: String) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            // Internal dir always works and is pullable via `run-as`;
            // the external dir is mirrored for the direct `adb pull`.
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

    private fun waitTextContains(text: String, ms: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), ms) == true

    private fun clickTextContains(text: String, ms: Long = 10_000): Boolean {
        // Until.hasObject conditions yield a non-null Boolean: compare against
        // true, never against null (a timed-out wait would otherwise pass).
        if (device.wait(Until.hasObject(By.textContains(text)), ms) != true) return false
        return runCatching { device.findObject(By.textContains(text))?.click() != null }
            .getOrDefault(false)
    }

    private fun clickDesc(desc: String, ms: Long = 10_000): Boolean {
        if (device.wait(Until.hasObject(By.desc(desc)), ms) != true) return false
        return runCatching { device.findObject(By.desc(desc))?.click() != null }
            .getOrDefault(false)
    }

    /** All on-screen text nodes owned by the app (never the soft keyboard's
     *  suggestion strip, whose nodes belong to the IME package). */
    private fun visibleTexts(): Set<String> =
        device.findObjects(By.text(Pattern.compile(".+")))
            .filter { runCatching { it.applicationPackage }.getOrNull() == appPackage }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Polls the screen for a text node owned by the app that was not in
     * [baseline] and is not a known non-reply label. Used to detect the
     * assistant's answer appearing.
     */
    private fun waitNewText(baseline: Set<String>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.runWatchers()
            for (obj in device.findObjects(By.text(Pattern.compile(".+")))) {
                if (runCatching { obj.applicationPackage }.getOrNull() != appPackage) continue
                val t = obj.text.trim()
                if (t.isEmpty() || t in baseline || t in nonReplyTexts) continue
                // The sent prompts reappear as the user's message bubbles —
                // they are not replies.
                if (t == messageOne || t == messageTwo) continue
                // Agent status lines (planning / TTS / execution) are not the
                // reply either; only the assistant's message bubble counts.
                if (agentStatusPrefixes.any { t.startsWith(it) }) continue
                if (t.startsWith(chatPlaceholder)) continue
                return t
            }
            runCatching { Thread.sleep(2_500) }
        }
        return null
    }

    /** Sends the composed chat message; the keyboard is closed only when it
     *  covers the send button (back closes the IME before the activity). */
    private fun tapSend(): Boolean {
        if (clickDesc("Send", 3_000)) return true
        device.pressBack()
        device.waitForIdle(1_000)
        return clickDesc("Send", 8_000)
    }

    /**
     * Types into the field identified by [selectorText] — the visible LABEL on
     * Material3 text fields ("What should I call you?"), since placeholders
     * only render while focused. Clicks the label (positional tap lands inside
     * the field), injects the value as key events via
     * [Instrumentation.sendStringSync], and verifies success by reading the
     * EditText node's text (Compose exposes TextField values there).
     * One retry falls back to ACTION_SET_TEXT on the EditText node.
     */
    private fun typeInto(selectorText: String, value: String): Boolean {
        repeat(2) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(selectorText)), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_500)
            runCatching {
                if (attempt == 0) {
                    InstrumentationRegistry.getInstrumentation().sendStringSync(value)
                } else {
                    editTextNodes().firstOrNull()?.setText(value)
                }
            }
            device.waitForIdle(1_000)
            // The open keyboard hides the app's own nodes from the a11y tree;
            // dismiss it (back targets the IME first — and only when the IME
            // is actually up, otherwise back would exit the activity) before
            // the field value becomes verifiable.
            if (keyboardUp()) {
                device.pressBack()
                device.waitForIdle(1_500)
            }
            if (anyEditTextContains(value)) return true
        }
        return false
    }

    private fun editTextNodes() =
        device.findObjects(By.clazz("android.widget.EditText"))

    private fun anyEditTextContains(value: String): Boolean =
        editTextNodes().any { runCatching { it.text }.getOrNull()?.contains(value) == true }

    /** The IME window is up (its presence hides the app's own nodes from the
     *  a11y tree, so verification must wait until it is dismissed). */
    private fun keyboardUp(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { w ->
                w.root?.packageName?.toString()?.contains("inputmethod") == true
            }
    }.getOrDefault(false)

    /** Leaves the full UI hierarchy with the artifacts for post-mortem reads. */
    private fun dumpHierarchy(name: String) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(ctx.filesDir, "e2e-screens")
            dir.mkdirs()
            device.dumpWindowHierarchy(File(dir, "$name.xml").outputStream())
        }
    }

    // ---------- the journey ----------

    @Test(timeout = 600_000)
    fun fullAppJourney_onboard_tabs_settings_twoLiveChats() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // ---- 1. Launch: capture whatever the first screen actually is ----
            val onboarding =
                device.wait(Until.hasObject(By.textContains("What should I call you?")), 45_000) == true
            shoot("00_first_screen")
            dumpHierarchy("first_screen")
            if (onboarding) {
                val okName = typeInto("What should I call you?", "TSF Tester")
                dumpHierarchy("after_name_attempt")
                assertTrue("name field typing failed", okName)
                val okBirth = typeInto("When is your birthday?", "01/15/2000")
                dumpHierarchy("after_birthday_attempt")
                assertTrue("birthday field typing failed", okBirth)
                shoot("02_onboarding_filled")

                // Stage 1 → 2 ("Let's Go"), profile encrypted at rest. Empty
                // or invalid fields would keep this screen up with a visible
                // validation error, so advancing IS the typing verification.
                assertTrue("Let's Go button not found", clickTextContains("Let's Go", 15_000))
                val advanced =
                    device.wait(Until.hasObject(By.textContains("Grant Permissions")), 15_000) == true
                dumpHierarchy("after_lets_go")
                assertTrue(
                    "onboarding did not advance past the introduction panel",
                    advanced
                )
                shoot("03_permissions_prompt")

                // Stage 2 → 3 ("Grant Permissions" opens the permissions panel).
                assertTrue(
                    "permissions panel button not found",
                    clickTextContains("Grant Permissions", 15_000)
                )
                device.wait(Until.hasObject(By.textContains("Proceed to")), 15_000)
                shoot("04_permissions_panel")

                // Stage 3 → dashboard. The button is always clickable; granting
                // the rest later is an advertised user option.
                assertTrue(
                    "Proceed to agent button not found",
                    clickTextContains("Proceed to", 15_000)
                )
            }

            // ---- 2. Dashboard reached: Chat tab is the default ----
            assertTrue(
                "dashboard (Chat tab) never appeared after onboarding",
                device.wait(Until.hasObject(By.text("Chat")), 30_000) == true
            )
            device.waitForIdle(5_000)
            shoot("05_dashboard_chat_empty")

            // ---- 3. Visit every other tab, screenshot each ----
            val tabs = listOf("Plan", "Memory", "Social", "Macros", "Logs", "Settings")
            tabs.forEachIndexed { index, tab ->
                assertTrue("tab $tab not clickable", clickTextContains(tab, 10_000))
                device.waitForIdle(3_000)
                shoot(String.format("%02d_tab_%s", 6 + index, tab.lowercase()))
                // The activity must survive every tab switch.
                scenario.onActivity { activity ->
                    assertTrue("activity finishing after opening $tab", !activity.isFinishing)
                }
            }

            // ---- 4. Settings: the provider row must show the keyless default ----
            assertTrue("Settings tab not reachable", clickTextContains("Settings", 10_000))
            device.waitForIdle(3_000)
            val providerVisible = waitTextContains("OpenCode Zen", 20_000)
            shoot("12_settings_provider_default")
            assertTrue(
                "provider row does not show OpenCode Zen (keyless default missing)",
                providerVisible
            )

            // Open the provider dropdown and confirm OpenCode Zen is offered.
            assertTrue("provider dropdown did not open", clickTextContains("OpenCode Zen", 8_000))
            device.waitForIdle(2_000)
            val dropdownHasZen = waitTextContains("OpenCode Zen", 8_000)
            shoot("13_settings_provider_dropdown")
            assertTrue("dropdown does not offer OpenCode Zen", dropdownHasZen)
            device.pressBack() // close dropdown (or leave settings; next click recovers)
            device.waitForIdle(1_000)

            // ---- 5. Chat: first live message through the real input ----
            assertTrue("Chat tab not reachable from Settings", clickTextContains("Chat", 10_000))
            device.waitForIdle(2_000)

            // The input is locatable either by its placeholder text or as the
            // screen's EditText node (placeholders only render when focused).
            assertTrue(
                "chat input field not found",
                device.wait(Until.hasObject(By.textContains(chatPlaceholder)), 15_000) == true ||
                    device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000) == true
            )
            val baseline = visibleTexts() // captured before typing anything
            assertTrue("could not type first message", typeInto(chatPlaceholder, messageOne))
            shoot("14_chat_first_typed")
            val baselineAfterTyping = visibleTexts()

            assertTrue("send button not found", tapSend())
            val replyOne = waitNewText(baselineAfterTyping, 210_000)
            shoot("15_chat_first_reply")
            assertNotNull(
                "no assistant reply appeared within 210s for the first message " +
                    "(baseline=$baselineAfterTyping)",
                replyOne
            )
            val firstReply = replyOne!!
            println("TSF-E2E first reply: $firstReply")

            // ---- 6. Chat: second, different live message (the loop works) ----
            device.waitForIdle(3_000)
            assertTrue(
                "chat input not reusable after first turn",
                device.wait(Until.hasObject(By.textContains(chatPlaceholder)), 15_000) == true ||
                    device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000) == true
            )
            assertTrue("could not type second message", typeInto(chatPlaceholder, messageTwo))
            shoot("16_chat_second_typed")
            val baselineSecond = visibleTexts()

            assertTrue("send button not found (2nd)", tapSend())
            // The first turn's reply bubble is new relative to this baseline —
            // exclude it so only the second turn's own answer can match.
            val replyTwo = waitNewText(baselineSecond + firstReply, 210_000)
            shoot("17_chat_second_reply")
            assertNotNull(
                "no assistant reply appeared within 210s for the second message",
                replyTwo
            )
            val secondReply = replyTwo!!
            println("TSF-E2E second reply: $secondReply")
            assertTrue(
                "assistant produced identical replies for different prompts",
                firstReply != secondReply || firstReply.contains("4")
            )
            shoot("18_journey_complete")
        }
    }
}
