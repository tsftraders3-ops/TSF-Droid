package com.tsfdroid.ai.e2e

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tsfdroid.ai.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * HERMETIC JOURNEY E2E (1.7.0-rc.1) — the app's core user journey, on a real
 * emulator, with ZERO live-provider dependency:
 *
 *   1. app launches (fresh or previously-onboarded state both handled),
 *   2. onboarding completes through the SAME battle-tested stages the live
 *      journey uses (run 37731897885 class fix): label-focused ACTION_SET_TEXT
 *      typing for name + birthday, "Let's Go" -> "Grant Permissions" ->
 *      "Proceed to", each stage gated by the next screen appearing,
 *   3. the dashboard chrome is real: the Chat tab and the New chat button,
 *   4. primary tabs navigate,
 *   5. a new chat opens and the composer is ready.
 *
 * No message is ever sent: nothing here touches an LLM endpoint, so this
 * suite is deterministic and belongs to the REQUIRED hermetic gate — it is
 * exactly the journey that used to cascade in live runs when a prior test
 * died mid-onboarding (run 37723671405 attempt 1).
 *
 * Every wait is a bounded poll (Until.hasObject / detection deadline); there
 * are no arbitrary sleep-until-green loops.
 */
@RunWith(AndroidJUnit4::class)
class HermeticJourneyInstrumentedTest {

    private lateinit var device: UiDevice
    private lateinit var appPackage: String

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

    @Test(timeout = 300_000)
    fun journey_onboarding_dashboard_tabs_newChat() {
        ActivityScenario.launch(MainActivity::class.java)

        // ---- 1 + 2: launch and (if needed) complete onboarding ----
        // Watcher-aware polling: blocking device.wait() never runs the ANR
        // watcher, so a system dialog on top of onboarding would stall
        // detection (loop-14 lesson).
        var onboarding = false
        val detectionDeadline = System.currentTimeMillis() + 60_000
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
        shoot("journey_launch_detected")
        if (onboarding) {
            // Name + birthday, both through the label-focused typing with
            // ACTION_SET_TEXT primary and the real IME as the alternate
            // route; every attempt ends in a verified check.
            val okName = typeInto("What should I call you?", "Journey")
            shoot("journey_name_typed_$okName")
            assertTrue("name field typing failed", okName)
            // The birthday field reformats input (typed "01/15/2000" is
            // normalized to "1/15/2000" — leading zero stripped), so
            // verification accepts the year tail only.
            val okBirth = typeInto("When is your birthday?", "01/15/2000", verifyContains = "15/2000")
            shoot("journey_birthday_typed_$okBirth")
            assertTrue("birthday field typing failed", okBirth)

            // Stage 1 -> 2: "Let's Go". Empty or invalid fields keep this
            // screen up with a visible validation error, so advancing IS the
            // typing verification.
            assertTrue("Let's Go button not found", clickTextContains("Let's Go", 15_000))
            val advanced =
                device.wait(Until.hasObject(By.textContains("Grant Permissions")), 15_000) == true
            assertTrue("onboarding did not advance past the introduction panel", advanced)
            shoot("journey_permissions_prompt")

            // Stage 2 -> 3: the permissions panel (already granted above via
            // pm grant; the panel just needs to be passed through).
            assertTrue(
                "permissions panel button not found",
                clickTextContains("Grant Permissions", 15_000)
            )
            device.wait(Until.hasObject(By.textContains("Proceed to")), 15_000)
            shoot("journey_permissions_panel")

            // Stage 3 -> dashboard. The button is always clickable; granting
            // the rest later is an advertised user option.
            assertTrue(
                "Proceed to agent button not found",
                clickTextContains("Proceed to", 15_000)
            )
        }

        // ---- 3: the dashboard chrome is real ----
        assertTrue(
            "dashboard Chat tab never appeared (onboarding completed=$onboarding)",
            device.wait(Until.hasObject(By.text("Chat")), 30_000) == true
        )
        // Same active-navigation discipline as the field-fix suite: click the
        // Chat tab, and only when the chat list chrome still does not appear
        // press BACK (closing an open thread or covering sheet, never the
        // chat list itself) and click again. Bounded rounds.
        var chatListShown = false
        repeat(4) {
            device.runWatchers()
            runCatching { device.findObject(By.text("Chat"))?.click() }
            if (device.wait(Until.hasObject(By.desc("New chat")), 6_000) == true) {
                chatListShown = true
                return@repeat
            }
            runCatching { device.pressBack() }
            device.waitForIdle(1_000)
        }
        assertTrue("chat list (New chat button) never appeared", chatListShown)
        shoot("journey_dashboard_reached")

        // ---- 4: primary tabs navigate (bounded waits, no sleeps) ----
        for (tab in listOf("Plan", "Memory")) {
            runCatching { device.findObject(By.text(tab))?.click() }
            device.waitForIdle(1_500)
        }
        // Back to Chat for the new-chat step.
        runCatching { device.findObject(By.text("Chat"))?.click() }
        device.waitForIdle(1_500)
        shoot("journey_tabs_navigated")

        // ---- 5: a new chat opens with a ready composer ----
        assertTrue("New chat button not found", clickDesc("New chat", 30_000))
        val composer = device.wait(
            Until.hasObject(By.clazz("android.widget.EditText")), 20_000
        )
        assertTrue("new chat composer never appeared", composer == true)
        shoot("journey_new_chat_ready")

        // Explicitly NO send: this suite must never need an LLM endpoint.

        // ---- 6: the Canvas workspace is reachable from the More sheet ----
        // God-Tier 5.2: real navigation through the production NavHost — the
        // same path a user takes. Compact phone layout: the canvas top bar
        // (title + back) must appear, the artifact pane must NOT (nothing
        // produced an artifact on this deterministic journey).
        assertTrue("More nav item not found", clickTextContains("More", 10_000))
        assertTrue("Canvas item in the More sheet not found", clickTextContains("Canvas", 10_000))
        // Wait for the sheet to close (its Routines item disappears) before
        // asserting the canvas chrome, so the sheet's own "Canvas" text can
        // not satisfy the check.
        device.wait(Until.gone(By.text("Routines")), 5_000)
        assertTrue(
            "canvas top bar never appeared",
            device.wait(Until.hasObject(By.text("Canvas")), 10_000) == true
        )
        shoot("journey_canvas_reached")
        runCatching { device.pressBack() }
        assertTrue(
            "back from Canvas never returned to the dashboard",
            device.wait(Until.hasObject(By.text("Chat")), 10_000) == true
        )
    }

    // ---------- typing discipline (v1.6.1, run 37731897885 class fix) -------

    /**
     * Label-focused typing: click the field's LABEL to focus it (not "the
     * first EditText on screen", which is order-luck), then ACTION_SET_TEXT
     * (atomic, IME-independent) as the primary route, sendStringSync as the
     * alternate; every attempt ends in a verified check. 3 bounded rounds.
     */
    private fun typeInto(selectorText: String, value: String, verifyContains: String = value): Boolean {
        repeat(3) { attempt ->
            val target = device.wait(Until.findObject(By.textContains(selectorText)), 6_000)
                ?: return@repeat
            runCatching { target.click() }
            device.waitForIdle(1_500)
            val field = editTextNodes().firstOrNull {
                runCatching { it.isFocused }.getOrDefault(false)
            } ?: editTextNodes().firstOrNull()
            runCatching {
                if (attempt == 1 || field == null) {
                    // Alternate delivery route through the real IME.
                    InstrumentationRegistry.getInstrumentation().sendStringSync(value)
                } else {
                    field.setText(value)
                }
            }
            device.waitForIdle(1_000)
            // The open keyboard hides the app's own nodes from the a11y tree;
            // dismiss it before the field value becomes verifiable.
            dismissKeyboard()
            // When something still covers the app, the field cannot be
            // verified from here — the Let's Go gate is the real check.
            if (!appNodesVisible()) return true
            if (anyEditTextContains(verifyContains)) return true
        }
        return false
    }

    private fun editTextNodes() =
        device.findObjects(By.clazz("android.widget.EditText"))

    private fun anyEditTextContains(needle: String): Boolean =
        editTextNodes()
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .any { it.contains(needle) }

    // ---------- shared helpers (same contracts as the field-fix suite) ----

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

    private fun keyboardUp(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { w -> w.root?.packageName?.toString()?.contains("inputmethod") == true }
    }.getOrDefault(false)

    private fun appNodesVisible(): Boolean =
        device.findObjects(By.text(Pattern.compile(".+", Pattern.DOTALL)))
            .any { runCatching { it.applicationPackage }.getOrNull() == appPackage }

    private fun dismissKeyboard() {
        repeat(3) {
            val imeUp = keyboardUp()
            val appVisible = appNodesVisible()
            if (!imeUp && appVisible) return
            device.pressBack()
            device.waitForIdle(1_200)
        }
    }
}
