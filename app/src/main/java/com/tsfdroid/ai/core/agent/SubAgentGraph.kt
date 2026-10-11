package com.tsfdroid.ai.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * God-Tier Phase 2, ticket 5.3 — one node of the swarm's task graph.
 *
 * A task is a bounded unit of delegation: a goal, the ids of the tasks whose
 * outputs it consumes, its own hard deadline, its token budget, and the
 * tool scope it is allowed to operate in. Dependencies make the graph; the
 * [SubAgentGraph] executes it.
 *
 * SAFETY: [toolScope] names the ACTIONS this task may draft/use. It is
 * checked against [AutoApprovalPolicy.POLICY_CRITICAL_ACTIONS] BEFORE the
 * task runs — a sub-agent whose scope includes a critical action (SMS,
 * calls, payments, file writes, coordinate taps, ...) is REFUSED outright.
 * Sub-agents inherit the CEO's consent boundary; they can never
 * auto-approve critical actions regardless of the swarm's instructions.
 * Like the router beneath it, a task only DRAFTS output — every drafted
 * step still flows back through the CEO's plan pipeline and the
 * AutoApprovalPolicy confirmation gate.
 */
data class SubAgentTask(
    val id: String,
    val goal: String,
    val role: SubAgentRole = SubAgentRole.RESEARCH,
    val dependencies: Set<String> = emptySet(),
    val timeoutMs: Long = 60_000L,
    val tokenBudget: Int = 4_000,
    val toolScope: Set<String> = emptySet()
)

/** A completed parent's contribution to a dependent task's context. */
data class SubAgentParentOutput(
    val taskId: String,
    val output: String
)

/**
 * The outcome of one task in the graph. Failures are VALUES, never
 * exceptions — the CEO receives the whole graph result and degrades
 * gracefully (see [SubAgentResult] for the same philosophy at router level).
 */
sealed class SubAgentOutcome {
    data class Completed(
        val output: String,
        val tokensUsed: Int,
        val latencyMs: Long
    ) : SubAgentOutcome()

    /** The task's own deadline killed it. */
    data class TimedOut(val taskId: String, val budgetMs: Long) : SubAgentOutcome()

    /** The specialist's response exceeded the task's token budget and was discarded. */
    data class OverBudget(val taskId: String, val tokensUsed: Int, val tokenBudget: Int) : SubAgentOutcome()

    /** The specialist failed (provider error, empty output, ...). */
    data class Failed(val taskId: String, val error: String) : SubAgentOutcome()

    /** The task never ran: one of its dependencies did not complete. */
    data class Skipped(val taskId: String, val reason: String) : SubAgentOutcome()

    /**
     * The task was refused for SAFETY: its toolScope names a policy-critical
     * action. Sub-agents cannot auto-approve critical actions.
     */
    data class Refused(val taskId: String, val reason: String) : SubAgentOutcome()
}

/** The result of executing a whole graph. */
data class SubAgentGraphResult(
    val outcomes: Map<String, SubAgentOutcome>,
    val invalidReason: String? = null
) {
    val allSucceeded: Boolean get() = invalidReason == null && outcomes.values.all { it is SubAgentOutcome.Completed }
}

/**
 * God-Tier Phase 2, ticket 5.3 — the True Multi-Agent DAG Swarm.
 *
 * Upgrades the sequential [SubAgentRouter] delegation (research -> draft,
 * one call after the other) into a concurrent directed acyclic graph of
 * [SubAgentTask]s executed with Kotlin coroutines:
 *
 *  - INDEPENDENT tasks run as concurrent `async` coroutines on the caller's
 *    dispatcher — siblings resumed by the same parent's completion start at
 *    the same instant, not serialized behind each other;
 *  - tasks WITH dependencies `await()` their parents' outcomes before
 *    starting, and receive every completed parent's output as context;
 *  - every task runs under its own `withTimeoutOrNull(task.timeoutMs)`
 *    circuit breaker — a starved or wedged specialist is killed and the
 *    graph still returns (graceful degradation, never an exception);
 *  - a task whose parent did not complete is Skipped (never run), so one
 *    failure prunes exactly its downstream subgraph, nothing else;
 *  - the SAFETY GATE runs before anything else: a policy-critical tool in
 *    the task's scope refuses the task outright (no provider call, no
 *    drafting) — the consent boundary is inherited from the CEO.
 *
 * Structural validation (duplicate ids, unknown dependencies, cycles)
 * rejects the whole graph without running anything — a malformed swarm is a
 * programmer error, not a partial run.
 *
 * The graph only PRODUCES outputs. It never dispatches actions: drafts flow
 * back through the CEO's plan pipeline and the AutoApprovalPolicy
 * confirmation gate, exactly as the single-shot router's do.
 */
@Singleton
class SubAgentGraph @Inject constructor(
    private val subAgentRouter: SubAgentRouter
) {
    // Test seam: the default runs each task through the REAL router (which
    // carries its own provider seam). Tests substitute a controllable runner
    // to pin concurrency, dependency, and timeout behavior deterministically.
    // Never set by production code.
    internal var taskRunner: suspend (SubAgentTask, List<SubAgentParentOutput>) -> SubAgentOutcome =
        { task, parents -> runThroughRouter(task, parents) }

    /**
     * Executes the graph. Returns when every task has an outcome (Completed,
     * TimedOut, OverBudget, Failed, Skipped, Refused) or the graph was
     * structurally invalid. NEVER throws for task-level failures.
     */
    suspend fun execute(tasks: List<SubAgentTask>): SubAgentGraphResult {
        validate(tasks)?.let { reason ->
            return SubAgentGraphResult(outcomes = emptyMap(), invalidReason = reason)
        }

        val topoOrder = topologicalOrder(tasks)
            ?: return SubAgentGraphResult(
                outcomes = emptyMap(),
                invalidReason = "task graph contains a cycle — refusing to execute an ambiguous swarm"
            )

        return coroutineScope {
            // Deferreds are created in TOPOLOGICAL order, so every parent's
            // deferred already exists before any child body can reference it:
            // no map mutation races even on a multi-threaded dispatcher.
            val deferreds = LinkedHashMap<String, Deferred<SubAgentOutcome>>()
            for (task in topoOrder) {
                deferreds[task.id] = async { runTask(task, deferreds) }
            }
            val outcomes = LinkedHashMap<String, SubAgentOutcome>()
            for (task in topoOrder) {
                outcomes[task.id] = deferreds.getValue(task.id).await()
            }
            SubAgentGraphResult(outcomes = outcomes)
        }
    }

    /**
     * One task's lifecycle: dependency gate -> safety gate -> deadline-bounded
     * execution -> recorded outcome. Pure function of the parent outcomes.
     */
    private suspend fun runTask(
        task: SubAgentTask,
        deferreds: Map<String, Deferred<SubAgentOutcome>>
    ): SubAgentOutcome {
        // 1. Dependency gate: await every parent, then check what it produced.
        val parentOutcomes = LinkedHashMap<String, SubAgentOutcome>()
        for (dep in task.dependencies) {
            parentOutcomes[dep] = deferreds.getValue(dep).await()
        }
        val failedParent = parentOutcomes.entries.firstOrNull { it.value !is SubAgentOutcome.Completed }
        if (failedParent != null) {
            return SubAgentOutcome.Skipped(
                taskId = task.id,
                reason = "dependency ${failedParent.key} did not complete " +
                    "(${failedParent.value::class.simpleName})"
            )
        }

        // 2. Safety gate: the CEO's consent boundary is inherited by every
        // sub-agent. A policy-critical tool in the scope refuses the task
        // before the specialist is ever consulted.
        val criticalTool = task.toolScope.firstOrNull { AutoApprovalPolicy.isPolicyCriticalAction(it) }
        if (criticalTool != null) {
            return SubAgentOutcome.Refused(
                taskId = task.id,
                reason = "toolScope contains policy-critical action $criticalTool — " +
                    "sub-agents cannot auto-approve critical actions"
            )
        }

        val parents = parentOutcomes.entries.map { (id, outcome) ->
            SubAgentParentOutput(taskId = id, output = (outcome as SubAgentOutcome.Completed).output)
        }

        // 3. Deadline-bounded execution. A task that outlives its timeoutMs
        // is killed (its runner coroutine is cancelled) and reported — the
        // graph never hangs on one wedged specialist.
        return try {
            withTimeoutOrNull(task.timeoutMs) { taskRunner(task, parents) }
                ?: SubAgentOutcome.TimedOut(task.id, task.timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SubAgentOutcome.Failed(task.id, e.message ?: "sub-agent task failure")
        }
    }

    /** The default runner: delegates to the REAL router specialist for the task's role. */
    private suspend fun runThroughRouter(
        task: SubAgentTask,
        parents: List<SubAgentParentOutput>
    ): SubAgentOutcome {
        val parentContext = if (parents.isEmpty()) {
            ""
        } else {
            "\nUpstream findings from the swarm:\n" +
                parents.joinToString("\n") { "[${it.taskId}] ${it.output}" }
        }
        val result = when (task.role) {
            SubAgentRole.RESEARCH -> subAgentRouter.research(task.goal + parentContext)
            SubAgentRole.EXECUTOR -> subAgentRouter.draftExecution(task.goal, parentContext.trim())
        }
        return when (result) {
            is SubAgentResult.Success -> SubAgentOutcome.Completed(
                output = result.output,
                tokensUsed = result.tokensUsed,
                latencyMs = result.latencyMs
            )
            is SubAgentResult.TimedOut -> SubAgentOutcome.TimedOut(task.id, result.budgetMs)
            is SubAgentResult.OverBudget -> SubAgentOutcome.OverBudget(task.id, result.tokensUsed, result.tokenBudget)
            is SubAgentResult.Failed -> SubAgentOutcome.Failed(task.id, result.error)
        }
    }

    /** Structural validation: duplicate ids and unknown dependencies. */
    private fun validate(tasks: List<SubAgentTask>): String? {
        val duplicates = tasks.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return "duplicate task ids: ${duplicates.sorted().joinToString(", ")}"
        }
        val ids = tasks.map { it.id }.toSet()
        val unknown = tasks.flatMap { it.dependencies }.filter { it !in ids }.distinct()
        if (unknown.isNotEmpty()) {
            return "unknown dependencies: ${unknown.sorted().joinToString(", ")}"
        }
        return null
    }

    /**
     * Kahn's algorithm. Returns null when a cycle exists (not every node can
     * reach in-degree zero).
     */
    private fun topologicalOrder(tasks: List<SubAgentTask>): List<SubAgentTask>? {
        val byId = tasks.associateBy { it.id }
        val inDegree = tasks.associate { it.id to it.dependencies.size }.toMutableMap()
        val dependents = HashMap<String, MutableList<String>>()
        tasks.forEach { task ->
            task.dependencies.forEach { dep ->
                dependents.getOrPut(dep) { mutableListOf() }.add(task.id)
            }
        }
        val queue = ArrayDeque(tasks.filter { it.dependencies.isEmpty() }.map { it.id })
        val order = mutableListOf<SubAgentTask>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order.add(byId.getValue(id))
            dependents[id]?.forEach { child ->
                val remaining = inDegree.merge(child, -1, Int::plus)!!
                if (remaining == 0) queue.add(child)
            }
        }
        return if (order.size == tasks.size) order else null
    }
}
