package com.tsfdroid.ai

import android.app.Application
import android.util.Log
import com.tsfdroid.ai.core.crash.CrashLogRecorder
import com.tsfdroid.ai.core.crash.DeviceMetadata
import com.tsfdroid.ai.core.crash.OpenDroidCrashHandler
import com.tsfdroid.ai.core.memory.MemoryManager
import com.tsfdroid.ai.core.security.LegacyPreferenceMigration
import com.tsfdroid.ai.data.crash.CrashLogRepository
import com.tsfdroid.ai.social.core.worker.SocialScheduleWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenDroidApp : Application() {

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var crashLogRepository: CrashLogRepository

    @Inject
    lateinit var legacyPreferenceMigration: LegacyPreferenceMigration

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Installed first so that a crash in any later startup step is captured.
        installCrashHandler()

        // Retire the legacy preference files into the direct-Keystore stores. This opens the
        // Keystore and the legacy keyset, so it runs off the main thread; splash routing and
        // onboarding await it before reading. It is a no-op once every value has been imported.
        legacyPreferenceMigration.start()

        // One-time startup cleanup: remove any poisoned memory entries
        // that may have been stored by previous versions of the app
        appScope.launch {
            try {
                memoryManager.cleanPoisonedMemories()
            } catch (e: Exception) {
                // Silently ignore cleanup errors to not block app startup
            }
        }

        // v1.3.0: the social schedule consumer is REAL now — the worker existed
        // since v1.0 but NOTHING ever enqueued it, so scheduled posts sat in
        // the DB forever and the scheduler UI was a promise the app never
        // kept. The periodic worker (15 min, network-required) publishes due
        // posts; the approval gate inside the worker still honors the user's
        // automation level (default APPROVAL — nothing publishes silently).
        try {
            SocialScheduleWorker.enqueuePeriodicWork(this)
        } catch (e: Exception) {
            // WorkManager init failures must never block app startup.
            Log.e(TAG, "Failed to schedule the social worker", e)
        }

        // v1.3.0: the macro cron consumer. SCHEDULE_MACRO has been writing
        // `cron:` triggers since v1.0 but nothing ever executed them — scheduled
        // macros were a promise the app never kept. The periodic worker
        // (15 min) evaluates each enabled macro's cron trigger and runs its
        // steps when due; at-most-once semantics (see MacroSchedulerWorker).
        try {
            com.tsfdroid.ai.core.agent.MacroSchedulerWorker.enqueuePeriodicWork(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule the macro worker", e)
        }
    }

    private fun installCrashHandler() {
        try {
            val recorder = CrashLogRecorder(
                sink = crashLogRepository,
                // Read here, at startup, rather than at crash time - a dying
                // process is the wrong place to be querying PackageManager.
                metadata = DeviceMetadata.fromContext(this),
                onRecordingFailed = { Log.e(TAG, "Failed to record crash", it) }
            )
            OpenDroidCrashHandler.install(recorder)
        } catch (t: Throwable) {
            // Crash logging is best-effort. Failing to install it must never be
            // the reason the app does not start.
            Log.e(TAG, "Failed to install crash handler", t)
        }
    }

    companion object {
        private const val TAG = "OpenDroidApp"
    }
}
