package com.tsfdroid.ai.core.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.tsfdroid.ai.core.agent.AutoApprovalPolicy
import com.tsfdroid.ai.data.db.OpenDroidDatabase
import com.tsfdroid.ai.data.db.dao.ProactiveSuggestionDao
import com.tsfdroid.ai.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * God-Tier Phase 2, ticket 5.4 — the Proactive Background Worker.
 *
 * The Bars (from the ticket, proven with WorkManagerTestInitHelper):
 *  1. CONSTRAINTS — the periodic request is built with
 *     requiresBatteryNotLow(true) AND requiresDeviceIdle(true).
 *  2. GATED EXECUTION — with the battery constraint unmet, the worker does
 *     NOT run: it stays ENQUEUED and no suggestion is persisted. Once the
 *     constraints are met and the period fires, it runs and persists.
 *  3. NO SIDE EFFECTS — the worker ONLY persists suggestions. A suggestion
 *     carrying a policy-critical action (DELETE_FILE, SEND_SMS, ...) is
 *     DISCARDED by the safety gate before it touches storage. There is no
 *     dispatch path at all: the worker never touches ActionDispatcher.
 *  4. OPT-IN — the default state is OFF; nothing is enqueued while disabled,
 *     and opting out cancels any scheduled work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver
    private lateinit var db: OpenDroidDatabase
    private lateinit var dao: ProactiveSuggestionDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.proactiveSuggestionDao()

        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? =
                    if (workerClassName == ProactiveSuggestionWorker::class.java.name) {
                        ProactiveSuggestionWorker(appContext, workerParameters)
                            .apply { deps = ProactiveSuggestionWorker.Dependencies(db) }
                    } else {
                        null
                    }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork()
        db.close()
    }

    private suspend fun seedSessions(count: Int) {
        repeat(count) { i ->
            db.chatSessionDao().insert(
                ChatSessionEntity(
                    id = "seed-$i",
                    title = "Chat about topic $i",
                    createdAt = 1_000L + i,
                    updatedAt = 2_000L + i
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // BAR 1: the constraints are applied to the work request
    // ------------------------------------------------------------------

    @Test
    fun `periodic request requires battery-not-low and device-idle`() {
        val request = ProactiveSuggestionWorker.buildPeriodicRequest()

        assertTrue("must be periodic work", request is PeriodicWorkRequest)
        val spec = request.workSpec
        assertTrue(
            "batteryNotLow constraint must be set",
            spec.constraints.requiresBatteryNotLow()
        )
        assertTrue(
            "deviceIdle constraint must be set",
            spec.constraints.requiresDeviceIdle()
        )
    }

    // ------------------------------------------------------------------
    // BAR 2: unmet constraints keep the worker from running
    // ------------------------------------------------------------------

    @Test
    fun `worker does not run while the battery constraint is unmet`() = runBlocking {
        seedSessions(4)
        ProactiveSuggestionWorker.setEnabled(context, true)
        val workId = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get().first().id

        // The period fires, but the battery/idle constraints were never met.
        testDriver.setPeriodDelayMet(workId)

        val info = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get().first()
        assertTrue(
            "with constraints unmet the work must stay ENQUEUED, was ${info.state}",
            info.state == WorkInfo.State.ENQUEUED
        )
        assertTrue(
            "no suggestion may be persisted while gated",
            dao.getAllOnce().isEmpty()
        )
    }

    @Test
    fun `worker runs and persists suggestions once constraints are met`() = runBlocking {
        seedSessions(4)
        ProactiveSuggestionWorker.setEnabled(context, true)
        val workId = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get().first().id

        testDriver.setAllConstraintsMet(workId)
        testDriver.setPeriodDelayMet(workId)

        // The worker's coroutine hops to Dispatchers.IO, which the
        // SynchronousExecutor does not block on: poll (bounded) until the
        // suggestion lands or the poll times out and lets the assertion fail.
        awaitCondition {
            runBlocking { dao.getAllOnce().isNotEmpty() }
        }

        val info = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get().first()
        assertTrue(
            "the run must not fail, was ${info.state}",
            info.state != WorkInfo.State.FAILED
        )
        assertTrue(
            "the engine's macro suggestion must be persisted (4 seeded sessions)",
            dao.getAllOnce().any { it.title.contains("macro", ignoreCase = true) }
        )
    }

    // ------------------------------------------------------------------
    // BAR 3: no side effects — suggestions only, critical actions discarded
    // ------------------------------------------------------------------

    @Test
    fun `a suggestion carrying a policy-critical action is discarded, never persisted`() = runBlocking {
        seedSessions(4)
        val worker = TestListenableWorkerBuilder<ProactiveSuggestionWorker>(context).build()
        worker.deps = ProactiveSuggestionWorker.Dependencies(
            db = db,
            engineOverride = FakeEngine(
                listOf(
                    // One benign suggestion...
                    suggestion(title = "Tidy up", action = "CREATE_MACRO"),
                    // ...and policy-critical ones that must never land.
                    suggestion(title = "Wipe workspace", action = "DELETE_FILE"),
                    suggestion(title = "Send the message", action = "SEND_SMS"),
                    suggestion(title = "Tap it", action = "CLICK_COORDINATES"),
                    suggestion(title = "Pay it", action = "PAY_UPI")
                )
            )
        )

        val result = worker.doWork()

        assertTrue(
            "worker succeeds while discarding unsafe suggestions",
            result is ListenableWorker.Result.Success
        )
        val persisted = dao.getAllOnce()
        assertEquals(1, persisted.size)
        assertEquals("Tidy up", persisted.first().title)
        assertTrue(
            "no critical action may survive into storage",
            persisted.none { it.suggestedAction?.let { a -> AutoApprovalPolicy.isPolicyCriticalAction(a) } == true }
        )
    }

    // ------------------------------------------------------------------
    // BAR 4: opt-in — default OFF, nothing enqueued while disabled
    // ------------------------------------------------------------------

    @Test
    fun `proactive suggestions are disabled by default`() {
        val freshContext = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            "the feature must ship OFF",
            ProactiveSuggestionWorker.isEnabled(freshContext)
        )
    }

    @Test
    fun `enqueue while disabled schedules nothing`() = runBlocking {
        ProactiveSuggestionWorker.setEnabled(context, false)

        val infos = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get()
        assertTrue(
            "no live work with the unique name may exist while disabled",
            infos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        )
    }

    @Test
    fun `disabling after enabling cancels the periodic work`() = runBlocking {
        ProactiveSuggestionWorker.setEnabled(context, true)
        val afterEnable = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get()
        assertTrue(
            "enabling must schedule the periodic work",
            afterEnable.any { it.state == WorkInfo.State.ENQUEUED }
        )

        ProactiveSuggestionWorker.setEnabled(context, false)

        val afterDisable = workManager
            .getWorkInfosForUniqueWork(ProactiveSuggestionWorker.WORK_NAME).get()
        assertTrue(
            "the periodic work must be cancelled after opt-out",
            afterDisable.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Bounded real-time poll for the worker's asynchronous IO writes. */
    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) return
            Thread.sleep(50)
        }
    }

    private fun suggestion(title: String, action: String?) = ProactiveSuggestion(
        title = title,
        body = "Suggestion body for $title",
        suggestedAction = action
    )

    /** Deterministic fake engine: returns exactly what it is given. */
    private class FakeEngine(private val suggestions: List<ProactiveSuggestion>) : ProactiveSuggestionEngine {
        override fun generate(
            sessionCount: Int,
            recentSessionTitles: List<String>,
            validMemoryCount: Int
        ): List<ProactiveSuggestion> = suggestions
    }
}
