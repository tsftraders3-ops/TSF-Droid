package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMProvider
import com.tsfdroid.ai.core.llm.LLMProviderFactory
import com.tsfdroid.ai.core.llm.LLMRequest
import com.tsfdroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * God-Tier Phase 2, ticket 5.3 — the True Multi-Agent DAG Swarm.
 *
 * The [SubAgentGraph] upgrades the sequential [SubAgentRouter] into a
 * concurrent directed-acyclic graph of [SubAgentTask]s: independent tasks run
 * as concurrent coroutines, tasks with dependencies await their parents, and
 * every task dies at its own deadline while the graph degrades gracefully.
 *
 * The Bars (from the ticket, proven here under virtual time):
 *  1. CONCURRENCY — siblings B and C that depend only on A must start at the
 *     same virtual instant. A sequential implementation would start C only
 *     after B's runner (which sleeps 100ms) finished, i.e. 100ms later.
 *  2. DEPENDENCY BLOCKING — D (depends on B) cannot start before B's runner
 *     completes; D receives B's output.
 *  3. TIMEOUT CANCELLATION — a task exceeding its timeoutMs is killed (its
 *     runner never reaches completion) and reported as an outcome; the graph
 *     returns; dependents are Skipped, never run.
 *  4. SAFETY — a task whose toolScope contains a policy-critical action
 *     (SEND_SMS, PAY_UPI, CLICK_COORDINATES) is REFUSED before its runner is
 *     ever invoked: sub-agents cannot auto-approve critical actions, no
 *     matter what the swarm is asked to do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubAgentGraphTest {

    class MainDispatcherRule(
        @OptIn(ExperimentalCoroutinesApi::class)
        private val dispatcher: TestDispatcher = StandardTestDispatcher()
    ) : TestWatcher() {
        override fun starting(description: Description) = kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        override fun finished(description: Description) = kotlinx.coroutines.Dispatchers.resetMain()
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var graph: SubAgentGraph
    private val startTimesMs = ConcurrentHashMap<String, Long>()
    private val runnerCalls = ConcurrentHashMap<String, Int>()
    private val completedRunners = ConcurrentHashMap<String, Boolean>()
    private val receivedParentOutputs = ConcurrentHashMap<String, List<SubAgentParentOutput>>()

    @Before
    fun setUp() {
        graph = SubAgentGraph(SubAgentRouter(neverFactory()))
        graph.taskRunner = { task, parents ->
            runnerCalls.merge(task.id, 1, Int::plus)
            receivedParentOutputs[task.id] = parents
            startTimesMs[task.id] = currentVirtualMs()
            taskRunnerBehavior(task.id)
            completedRunners[task.id] = true
            SubAgentOutcome.Completed(output = "out-${task.id}", tokensUsed = 42, latencyMs = 5)
        }
    }

    /** Per-task fake behavior the individual tests override (suspendable: delays simulate work). */
    internal var taskRunnerBehavior: suspend (String) -> Unit = { }

    /** Virtual-time clock captured inside fake runners. */
    private var virtualClock: () -> Long = { 0L }

    private fun currentVirtualMs(): Long = virtualClock()

    private fun task(id: String, vararg deps: String, timeoutMs: Long = 60_000L, toolScope: Set<String> = emptySet()) =
        SubAgentTask(id = id, goal = "goal-$id", dependencies = deps.toSet(), timeoutMs = timeoutMs, toolScope = toolScope)

    // ------------------------------------------------------------------
    // BAR 1: concurrency
    // ------------------------------------------------------------------

    @Test
    fun `siblings B and C start concurrently after A completes`() = runTest {
        virtualClock = { testScheduler.currentTime }
        taskRunnerBehavior = { id ->
            when (id) {
                "A" -> delay(100)
                "B" -> delay(100) // B hogs the clock after starting
                else -> {}
            }
        }
        val result = SubAgentGraphRunnerHelper.runAndCollect(this, graph, listOf(task("A"), task("B", "A"), task("C", "A")))

        assertEquals(3, result.outcomes.size)
        // A starts immediately (t=0) and completes at t=100 after its runner delay.
        assertEquals(0L, startTimesMs["A"])
        // B and C are both resumed by A's completion: both start at t=100.
        assertEquals(100L, startTimesMs["B"])
        // THE concurrency proof: C started at the same instant B started — B's
        // own 100ms runner delay is still elapsing. A sequential executor
        // would only reach C at t=200.
        assertEquals("siblings must start at the same instant", 100L, startTimesMs["C"])
        val bStart = startTimesMs["B"]!!
        val cStart = startTimesMs["C"]!!
        assertTrue("C started at B's start instant (diff=${cStart - bStart}ms)", cStart - bStart < 50)
        assertTrue(result.outcomes.values.all { it is SubAgentOutcome.Completed })
    }

    // ------------------------------------------------------------------
    // BAR 2: dependency blocking
    // ------------------------------------------------------------------

    @Test
    fun `a dependent task cannot start before its dependency finishes`() = runTest {
        virtualClock = { testScheduler.currentTime }
        taskRunnerBehavior = { id -> if (id == "B") delay(150) }
        val result = SubAgentGraphRunnerHelper.runAndCollect(this, graph, listOf(task("A"), task("B", "A"), task("D", "B")))

        // A completes instantly; B starts at t=0 and its 150ms delay holds D back.
        assertEquals(0L, startTimesMs["B"])
        val dStart = startTimesMs["D"]!!
        assertTrue("D must start at/after B's completion (150ms), was $dStart", dStart >= 150L)
        // D received B's output as its dependency context.
        val parents = receivedParentOutputs["D"]
        assertEquals(listOf(SubAgentParentOutput("B", "out-B")), parents)
    }

    @Test
    fun `diamond DAG delivers both branch outputs to the join`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(task("A"), task("B", "A"), task("C", "A"), task("D", "B", "C"))
        )
        assertTrue(result.outcomes.values.all { it is SubAgentOutcome.Completed })
        val parents = receivedParentOutputs["D"]!!.map { it.taskId }.toSet()
        assertEquals(setOf("B", "C"), parents)
    }

    // ------------------------------------------------------------------
    // BAR 3: timeout cancellation
    // ------------------------------------------------------------------

    @Test
    fun `a task exceeding its timeout is killed and reported, the graph still returns`() = runTest {
        virtualClock = { testScheduler.currentTime }
        taskRunnerBehavior = { id -> if (id == "SLOW") delay(60_000) }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(task("A"), task("SLOW", "A", timeoutMs = 500), task("VICTIM", "SLOW"))
        )

        val slow = result.outcomes["SLOW"]
        assertTrue("expected TimedOut but was $slow", slow is SubAgentOutcome.TimedOut)
        assertEquals(500L, (slow as SubAgentOutcome.TimedOut).budgetMs)
        // The runner was launched but never completed: the deadline killed it
        // before its body finished (no completion marker was ever recorded).
        assertEquals("SLOW runner must have been launched once", 1, runnerCalls["SLOW"] ?: 0)
        assertNull("killed before completion", completedRunners["SLOW"])
        // Graceful degradation: the graph returned and the dependent was
        // skipped (never ran), not crashed.
        val victim = result.outcomes["VICTIM"]
        assertTrue("expected Skipped but was $victim", victim is SubAgentOutcome.Skipped)
        assertEquals(0, runnerCalls["VICTIM"] ?: 0)
    }

    @Test
    fun `one killed task does not starve independent branches`() = runTest {
        virtualClock = { testScheduler.currentTime }
        taskRunnerBehavior = { id -> if (id == "SLOW") delay(60_000) }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(task("SLOW", timeoutMs = 300), task("HEALTHY"))
        )
        assertTrue(result.outcomes["HEALTHY"] is SubAgentOutcome.Completed)
        assertTrue(result.outcomes["SLOW"] is SubAgentOutcome.TimedOut)
        assertFalse(result.allSucceeded)
    }

    // ------------------------------------------------------------------
    // BAR 4: safety — AutoApprovalPolicy inheritance
    // ------------------------------------------------------------------

    @Test
    fun `a task with a policy-critical action in its toolScope is refused before running`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(
                task("SAFE", toolScope = setOf("OPEN_APP", "SEARCH_WEB")),
                task("SMS", toolScope = setOf("OPEN_APP", "SEND_SMS")),
                task("PAY", toolScope = setOf("PAY_UPI")),
                task("TAP", toolScope = setOf("CLICK_COORDINATES")),
                task("FILE", toolScope = setOf("DELETE_FILE", "OPEN_APP"))
            )
        )

        assertTrue("non-critical scope runs", result.outcomes["SAFE"] is SubAgentOutcome.Completed)
        assertEquals("safe task ran once", 1, runnerCalls["SAFE"] ?: 0)

        for (critical in listOf("SMS", "PAY", "TAP", "FILE")) {
            val outcome = result.outcomes[critical]
            assertTrue("expected Refused for $critical but was $outcome", outcome is SubAgentOutcome.Refused)
            assertEquals("refused task's runner must never run", 0, runnerCalls[critical] ?: 0)
            val reason = (outcome as SubAgentOutcome.Refused).reason
            val refusedAction = when (critical) {
                "SMS" -> "SEND_SMS"; "PAY" -> "PAY_UPI"; "TAP" -> "CLICK_COORDINATES"; else -> "DELETE_FILE"
            }
            assertTrue("reason must name the critical action ($reason)", reason.contains(refusedAction))
        }
        assertFalse(result.allSucceeded)
    }

    @Test
    fun `a refused task skips its dependents`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(task("SMS", toolScope = setOf("SEND_SMS")), task("CHILD", "SMS"))
        )
        assertTrue(result.outcomes["CHILD"] is SubAgentOutcome.Skipped)
        assertEquals(0, runnerCalls["CHILD"] ?: 0)
    }

    // ------------------------------------------------------------------
    // Graph validation
    // ------------------------------------------------------------------

    @Test
    fun `a cyclic graph is rejected without running anything`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, graph,
            listOf(task("A", "B"), task("B", "A"))
        )
        assertNotNull("cycle must be reported", result.invalidReason)
        assertTrue(result.invalidReason!!.contains("cycle", ignoreCase = true))
        assertTrue(result.outcomes.isEmpty())
        assertEquals(0, runnerCalls.size)
    }

    @Test
    fun `unknown dependencies are rejected`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(this, graph, listOf(task("A", "GHOST")))
        assertNotNull(result.invalidReason)
        assertTrue(result.invalidReason!!.contains("GHOST"))
    }

    @Test
    fun `duplicate task ids are rejected`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val result = SubAgentGraphRunnerHelper.runAndCollect(this, graph, listOf(task("A"), task("A")))
        assertNotNull(result.invalidReason)
        assertTrue(result.invalidReason!!.contains("duplicate", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // Default runner: the graph composes with the REAL router
    // ------------------------------------------------------------------

    @Test
    fun `default task runner delegates through the real SubAgentRouter`() = runTest {
        virtualClock = { testScheduler.currentTime }
        val router = SubAgentRouter(neverFactory())
        val llmCalls = AtomicInteger(0)
        router.providerAccess = {
            llmCalls.incrementAndGet()
            object : LLMProvider {
                override val name = "FakeSwarmProvider"
                override val availableModels = listOf("fake")
                override suspend fun complete(request: LLMRequest): LLMResponse {
                    // The specialist prompt carries the task goal; downstream
                    // tasks carry their parents' ANSWERS (what the default
                    // runner routed through as upstream findings).
                    val text = request.messages.last().text
                    val content = when {
                        text.contains("goal-A") && !text.contains("Upstream") -> "answer-A"
                        text.contains("answer-A") -> "draft-from-A"
                        else -> "solo"
                    }
                    return LLMResponse(
                        content = content,
                        tokensUsed = 10,
                        model = "fake",
                        provider = name,
                        latencyMs = 1
                    )
                }
                override fun streamComplete(request: LLMRequest): Flow<String> = flowOf("solo")
                override suspend fun isAvailable(): Boolean = true
            }
        }
        val realGraph = SubAgentGraph(router)
        val result = SubAgentGraphRunnerHelper.runAndCollect(
            this, realGraph,
            listOf(task("A"), task("B", "A"))
        )

        assertEquals(2, llmCalls.get())
        assertTrue(result.outcomes["A"].toString(), result.outcomes["A"] is SubAgentOutcome.Completed)
        val b = result.outcomes["B"] as SubAgentOutcome.Completed
        // B's specialist ran with A's finding in context, so it drafted from it.
        assertEquals("draft-from-A", b.output)
        assertTrue(result.allSucceeded)
    }

    /** Helper bridging TestScope suspend calls inside JUnit assertions flow. */
    private object SubAgentGraphRunnerHelper {
        suspend fun runAndCollect(scope: kotlinx.coroutines.test.TestScope, graph: SubAgentGraph, tasks: List<SubAgentTask>): SubAgentGraphResult {
            return withTimeout(10_000) { graph.execute(tasks) }
        }
    }

    // ---------------------------------------------------------------
    // Test factory that never resolves a provider (router seam used instead)
    // ---------------------------------------------------------------

    private fun neverFactory(): LLMProviderFactory {
        fun <T : Any> unused(): javax.inject.Provider<T> =
            javax.inject.Provider { throw AssertionError("test provider must never be resolved") }
        fun <T : Any> unusedLazy(): dagger.Lazy<T> =
            dagger.Lazy { throw AssertionError("test lazy must never be resolved") }
        val unusedSettings = com.tsfdroid.ai.data.repository.SettingsRepository(
            dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = kotlinx.coroutines.flow.flowOf(
                    androidx.datastore.preferences.core.emptyPreferences()
                )
                override suspend fun updateData(
                    transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
                ) = throw UnsupportedOperationException()
            },
            providerCredentialStore = UnusedCredentialStore,
            runStartupMigration = false
        )
        return LLMProviderFactory(
            claudeProvider = unused(),
            openAIProvider = unused(),
            geminiProvider = unused(),
            mistralProvider = unused(),
            groqProvider = unused(),
            ollamaProvider = unused(),
            openRouterProvider = unused(),
            togetherAIProvider = unused(),
            cohereProvider = unused(),
            deepSeekProvider = unused(),
            openCodeZenProvider = unused(),
            copilotProvider = unused(),
            customOpenAIProvider = unused(),
            gemmaProvider = unused(),
            liteRTLMProvider = unused(),
            hybridOnDeviceProvider = unused(),
            settingsRepository = unusedSettings,
            onDeviceLatencyTracker = com.tsfdroid.ai.core.llm.OnDeviceLatencyTracker(unusedSettings),
            actionDispatcher = unusedLazy(),
            intentClassifier = unusedLazy(),
            deviceStateProvider = DeviceStateProvider(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            )
        )
    }
}
