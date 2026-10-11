package com.tsfdroid.ai.core.proactive

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tsfdroid.ai.data.db.OpenDroidDatabase
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * God-Tier Phase 2, ticket 5.4 — the Proactive Background Worker.
 *
 * A real WorkManager job that analyzes local patterns and drafts proactive
 * suggestions, strictly gated by battery and user consent:
 *
 *  - OPT-IN: ships OFF. Nothing enqueues this worker until the user flips
 *    the toggle (Settings); [setEnabled] is the only scheduling surface.
 *    Unlike the macro/social schedulers, there is NO automatic enqueue at
 *    app start — proactive behavior must be chosen, never defaulted.
 *  - CONSTRAINTS: requires battery-not-low AND device-idle — proactive
 *    analysis is never worth the user's last battery percent, and never
 *    worth competing with whatever they are actively doing.
 *  - SIDE-EFFECT FREE: the worker ONLY persists suggestions to Room. It
 *    never sends, writes, deletes, taps, or notifies. There is no dispatch
 *    path — the class does not touch ActionDispatcher at all — and the
 *    safety gate ([ProactiveSuggestion.isSideEffectFree]) discards any
 *    suggestion carrying a policy-critical action before it reaches
 *    storage, so even future surfaces that read suggestions back inherit
 *    the same boundary.
 */
class ProactiveSuggestionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    /**
     * The worker's dependency bundle. Production resolves the app's real
     * database via the Hilt entry point; tests substitute an in-memory
     * database and (optionally) a controllable engine through this seam —
     * the same established pattern as SubAgentRouter.providerAccess.
     */
    internal class Dependencies(
        val db: OpenDroidDatabase,
        val engineOverride: ProactiveSuggestionEngine? = null
    )

    /** Test seam. Never set by production code. */
    internal var deps: Dependencies? = null

    override suspend fun doWork(): Result {
        val dependencies = deps ?: defaultDependencies()
        return try {
            val (sessions, memoryCount) = withContext(Dispatchers.IO) {
                val sessions = dependencies.db.chatSessionDao().getAllSessions().first()
                val memories = dependencies.db.memoryDao().getValidMemories().size
                sessions to memories
            }
            val engine = dependencies.engineOverride ?: LocalPatternSuggestionEngine()
            val suggestions = engine.generate(
                sessionCount = sessions.size,
                recentSessionTitles = sessions.map { it.title },
                validMemoryCount = memoryCount
            )

            // SAFETY GATE: suggestions only. Anything carrying a
            // policy-critical action is discarded here, before persistence.
            val safeSuggestions = suggestions.filter { it.isSideEffectFree() }
            if (safeSuggestions.size < suggestions.size) {
                Log.w(
                    TAG,
                    "discarded ${suggestions.size - safeSuggestions.size} suggestion(s) with " +
                        "policy-critical actions — the worker never persists critical payloads"
                )
            }

            withContext(Dispatchers.IO) {
                safeSuggestions.forEach { dependencies.db.proactiveSuggestionDao().insert(it.toEntity()) }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "proactive worker failed", e)
            Result.failure()
        }
    }

    private fun defaultDependencies(): Dependencies {
        val db = EntryPointAccessors.fromApplication(
            applicationContext,
            ProactiveWorkerEntryPoint::class.java
        ).database()
        return Dependencies(db)
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface ProactiveWorkerEntryPoint {
        fun database(): OpenDroidDatabase
    }

    companion object {
        private const val TAG = "ProactiveWorker"

        const val WORK_NAME = "tsf_proactive_suggestion_worker"
        private const val PREFS = "proactive_suggestions"
        private const val KEY_ENABLED = "enabled"

        /** THE opt-in default: proactive suggestions ship disabled. */
        const val DEFAULT_ENABLED = false

        private const val REPEAT_INTERVAL_HOURS = 24L

        /**
         * The constrained periodic request: battery-not-low AND device-idle.
         * These constraints are load-bearing (tested) — do not relax them.
         */
        fun buildPeriodicRequest(): androidx.work.PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ProactiveSuggestionWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()

        /** Whether the user has opted in. Default [DEFAULT_ENABLED] (OFF). */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        /**
         * The opt-in surface: flips the setting AND schedules/cancels the
         * periodic work. Called from the Settings toggle only.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            if (enabled) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    buildPeriodicRequest()
                )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
