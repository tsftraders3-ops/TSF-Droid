package com.tsfdroid.ai.core.agent

import android.content.Context
import android.content.ContextWrapper
import com.tsfdroid.ai.actions.base.ActionResult
import com.tsfdroid.ai.data.models.PlanStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSequenceExecutorTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `successful execution preserves order and runs every step`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action-result"))
        }

        val result = executor.execute(
            steps = listOf(
                step("second", order = 2, action = "SECOND"),
                step("first", order = 1, action = "FIRST")
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(listOf("FIRST", "SECOND"), calls)
        assertEquals("Macro completed successfully (2 steps).", result.data)
    }

    @Test
    fun `references use the completed result before dispatching the next step`() = runBlocking {
        val receivedParams = mutableListOf<Map<String, String>>()
        val executor = executor { action, params, _ ->
            receivedParams += params
            ActionResult.Success(mapOf("message" to if (action == "FIRST") "value" else "done"))
        }

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "FIRST"),
                step(
                    "second",
                    order = 2,
                    action = "SECOND",
                    params = mapOf("input" to ("$" + "first"), "legacy" to ("$" + "$" + "first"))
                )
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(mapOf("input" to "value", "legacy" to "value"), receivedParams[1])
    }

    @Test
    fun `failed primary action uses one fallback and continues only after fallback succeeds`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                calls += action
                if (action == "PRIMARY") ActionResult.Failure("primary failed")
                else ActionResult.Success(mapOf("message" to "fallback result"))
            },
            hasAction = { it == "FALLBACK" }
        )

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "PRIMARY", fallback = "FALLBACK"),
                step("second", order = 2, action = "SECOND")
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(listOf("PRIMARY", "FALLBACK", "SECOND"), calls)
    }

    @Test
    fun `failed fallback stops the macro and does not claim later steps ran`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                calls += action
                ActionResult.Failure("$action failed")
            },
            hasAction = { it == "FALLBACK" }
        )

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "PRIMARY", fallback = "FALLBACK"),
                step("later", order = 2, action = "LATER")
            ),
            context = context
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("step 1"))
        assertTrue(result.error!!.contains("FALLBACK"))
        assertFalse(result.error!!.contains("LATER"))
        assertEquals(listOf("PRIMARY", "FALLBACK"), calls)
    }

    // ── C-01 remediation: the background consent boundary ──
    // execute() is the batch seam used by MacroSchedulerWorker (cron), RUN_MACRO,
    // and HabitRoutineEngine.executeRoutine — none of which can show the
    // interactive approval modal. Policy-critical steps must therefore be
    // refused HERE, at the last point before dispatch, so no background caller
    // can route around the AgentLoop confirmation gate.

    @Test
    fun `background macro execution refuses a policy-critical SEND_SMS step`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action ok"))
        }

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "OPEN_APP"),
                step("second", order = 2, action = "SEND_SMS")
            ),
            context = context
        )

        // The SEND_SMS step must never reach the dispatcher: the step before it
        // ran, the critical one was refused, and the failure names the reason.
        assertFalse(result.success)
        assertEquals(listOf("OPEN_APP"), calls)
        assertTrue(result.error!!.contains("SEND_SMS"))
        assertTrue(result.error!!.contains("interactive confirmation"))
    }

    @Test
    fun `macro step with a policy-critical fallback is refused before any dispatch`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Failure("$action failed")
        }

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "WEB_SEARCH", fallback = "SEND_SMS")
            ),
            context = context
        )

        // AgentLoop semantics (AutoApprovalPolicy.isCritical) treat a critical
        // fallback as a critical step: neither the primary nor the fallback may
        // dispatch, because the sequence's approval was never obtained.
        assertFalse(result.success)
        assertEquals(emptyList<String>(), calls)
        assertTrue(result.error!!.contains("SEND_SMS"))
    }

    @Test
    fun `background gate stops the sequence at the first critical step and never runs later steps`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action ok"))
        }

        val result = executor.execute(
            steps = listOf(
                step("s1", order = 1, action = "WEB_SEARCH"),
                step("s2", order = 2, action = "PAY_UPI"),
                step("s3", order = 3, action = "GET_WEATHER")
            ),
            context = context
        )

        // Linear semantics + safety: the step after a refused critical step
        // cannot run (its inputs may depend on a step that never happened).
        assertFalse(result.success)
        assertEquals(listOf("WEB_SEARCH"), calls)
        assertTrue(result.error!!.contains("step 2"))
        assertTrue(result.error!!.contains("PAY_UPI"))
    }

    @Test
    fun `planner-flagged critical steps are refused by the background gate too`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action ok"))
        }

        val flagged = PlanStep(
            stepId = "s1",
            order = 1,
            description = "flagged by planner",
            action = "WEB_SEARCH",
            params = emptyMap(),
            critical = true
        )

        val result = executor.execute(steps = listOf(flagged), context = context)

        assertFalse(result.success)
        assertEquals(emptyList<String>(), calls)
        assertTrue(result.error!!.contains("interactive confirmation"))
    }

    @Test
    fun `non-critical macros still execute end to end under the background gate`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action ok"))
        }

        val result = executor.execute(
            steps = listOf(
                step("s1", order = 1, action = "OPEN_APP"),
                step("s2", order = 2, action = "WEB_SEARCH"),
                step("s3", order = 3, action = "GET_WEATHER")
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(listOf("OPEN_APP", "WEB_SEARCH", "GET_WEATHER"), calls)
    }

    private fun executor(
        executeAction: suspend (String, Map<String, String>, Context) -> ActionResult
    ) = ActionSequenceExecutor(executeAction, hasAction = { true })

    private fun step(
        id: String,
        order: Int,
        action: String,
        params: Map<String, String> = emptyMap(),
        fallback: String = ""
    ) = PlanStep(
        stepId = id,
        order = order,
        description = action,
        action = action,
        params = params,
        fallback = fallback
    )
}
