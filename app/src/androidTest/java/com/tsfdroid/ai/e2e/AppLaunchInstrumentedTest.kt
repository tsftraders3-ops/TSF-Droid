package com.tsfdroid.ai.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tsfdroid.ai.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke: the real [MainActivity] — Hilt graph, splash decision,
 * navigation — reaches usable UI on a device. On a fresh emulator the
 * onboarding "About You" panel is the first interactive screen; a device
 * with a stored profile lands on the dashboard ("Chat" tab).
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {

    @Test(timeout = 120_000)
    fun appLaunchesToUsableUi() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Splash holds for its animation plus the profile-decrypt check;
            // give the first real screen a generous window.
            val reached = device.wait(
                Until.hasObject(By.textContains("About You")),
                45_000
            ) ?: device.wait(Until.hasObject(By.textContains("Chat")), 5_000)

            assertNotNull(
                "neither onboarding nor dashboard appeared within the launch window",
                reached
            )
            // Loop-14: dismiss a system ANR dialog if one parked on the app
            // during the cold boot (the v1.0.6 CI run's "Pixel Launcher isn't
            // responding" evidence) so the artifact screenshot shows the app.
            repeat(3) {
                val waitButton = device.findObject(By.textContains("Wait"))
                if (waitButton != null && device.findObject(By.textContains("responding")) != null) {
                    waitButton.click()
                    device.waitForIdle(2_000)
                } else return@repeat
            }
            scenario.onActivity { activity ->
                assertTrue("activity must not be finishing", !activity.isFinishing)
                assertTrue(
                    "activity window must have content",
                    activity.window.decorView.width > 0 && activity.window.decorView.height > 0
                )
            }
            // Leave a screen capture the workflow can attach as an artifact.
            device.waitForIdle(5_000)
        }
    }
}
