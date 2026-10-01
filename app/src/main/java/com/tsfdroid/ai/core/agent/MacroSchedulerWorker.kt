package com.tsfdroid.ai.core.agent

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tsfdroid.ai.data.db.dao.MacroDao
import com.tsfdroid.ai.data.models.PlanStep
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * v1.3.0: the cron consumer for scheduled macros.
 *
 * SCHEDULE_MACRO has been writing `cron:<expression>` triggers into the macro
 * table since v1.0, but nothing ever read them back — a user who scheduled a
 * macro bought a promise the app never kept. This worker closes that loop:
 * every 15 minutes it evaluates each enabled macro's cron trigger against the
 * window since the previous run and executes the macro's steps when due.
 *
 * Semantics (deliberate, documented):
 *  - At-most-once firing: the window watermark is persisted BEFORE the macros
 *    run, so a crash mid-execution never re-fires a macro that already ran
 *    (macro steps can post to social media — replaying them silently is the
 *    worse failure).
 *  - Catch-up is bounded: after the device was off, each due macro fires once,
 *    not once per missed cron occurrence.
 *  - Malformed cron expressions are logged and skipped, never crash the run.
 *  - Disabled macros are ignored entirely (the toggle in the Macros screen
 *    stays the source of truth for "is this automation live").
 */
class MacroSchedulerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MacroSchedulerEntryPoint {
        fun macroDao(): MacroDao
        fun actionSequenceExecutor(): ActionSequenceExecutor
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        MacroSchedulerEntryPoint::class.java
    )

    companion object {
        private const val TAG = "MacroSchedulerWorker"
        const val WORK_NAME = "tsf_macro_scheduler_worker"
        private const val PREFS = "macro_scheduler"
        private const val KEY_WATERMARK = "window_watermark"
        private const val FIRST_RUN_LOOKBACK_MS = 15 * 60 * 1000L

        fun enqueuePeriodicWork(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<MacroSchedulerWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val windowStart = prefs.getLong(KEY_WATERMARK, now - FIRST_RUN_LOOKBACK_MS)

            // At-most-once: advance the watermark before executing anything.
            prefs.edit().putLong(KEY_WATERMARK, now).apply()

            val macros = entryPoint.macroDao().getAllMacros()
            var executed = 0
            var skippedMalformed = 0

            for (macro in macros) {
                if (!macro.isEnabled) continue
                val expression = MacroCron.expressionFromTrigger(macro.trigger) ?: continue
                val schedule = MacroCron.parse(expression)
                if (schedule == null) {
                    skippedMalformed++
                    Log.w(
                        TAG,
                        "Macro '${macro.name}' has a malformed cron trigger '${macro.trigger}' — skipping"
                    )
                    continue
                }

                if (MacroCron.firesWithin(schedule, windowStart, now)) {
                    val steps = try {
                        json.decodeFromString<List<PlanStep>>(macro.stepsJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Macro '${macro.name}' has unreadable steps JSON", e)
                        continue
                    }

                    Log.i(TAG, "Cron due — executing macro '${macro.name}' (${macro.trigger})")
                    val result = entryPoint.actionSequenceExecutor().execute(
                        steps,
                        applicationContext
                    )
                    if (result.success) {
                        executed++
                        Log.i(TAG, "Macro '${macro.name}' executed successfully")
                    } else {
                        Log.w(
                            TAG,
                            "Macro '${macro.name}' failed: ${result.error ?: "unknown error"}"
                        )
                    }
                }
            }

            if (executed > 0 || skippedMalformed > 0) {
                Log.i(
                    TAG,
                    "Window ${iso(windowStart)}..${iso(now)}: executed=$executed " +
                        "malformed=$skippedMalformed total=${macros.size}"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Macro scheduler run failed", e)
            Result.retry()
        }
    }

    private fun iso(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(epochMs))
}
