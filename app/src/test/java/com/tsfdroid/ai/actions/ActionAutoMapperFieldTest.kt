package com.tsfdroid.ai.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 round 3 (critic-21): the B5 gate tests that were promised and never
 * written — the invented-action family the field's flipkart turn died on
 * ("Action 'TAP' is not registered", 19 minutes into execution).
 */
class ActionAutoMapperFieldTest {

    private val mapper = ActionAutoMapper()
    private val registered = emptySet<String>()

    private fun resolve(action: String) =
        mapper.mapAction(action = action, params = emptyMap(), registeredActions = registered)

    // ── The field's invented TAP now resolves ─────────────────────────────

    @Test
    fun `field invented action TAP resolves to CLICK_TEXT`() {
        val m = resolve("TAP")
        assertTrue(m.wasMapped)
        assertEquals("CLICK_TEXT", m.mappedAction)
    }

    @Test
    fun `the whole invented tap family resolves`() {
        for (action in listOf(
            "TAP_ELEMENT", "CLICK", "CLICK_ELEMENT", "PRESS", "PRESS_BUTTON",
            "TOUCH", "TAP_BUTTON", "TAP_TEXT", "CLICK_LINK",
            "TAP_COORDINATES", "TAP_POSITION", "CLICK_AT", "CLICK_POINT",
            "TYPE", "INPUT_TEXT", "ENTER_TEXT", "WRITE_TEXT",
            "SWIPE_UP", "SWIPE_DOWN", "SCROLL_DOWN", "SCROLL_UP"
        )) {
            val m = resolve(action)
            assertTrue(
                "$action did not resolve (mapped=${m.mappedAction})",
                m.wasMapped && m.mappedAction != null
            )
        }
    }

    @Test
    fun `resolved targets are real registered action names`() {
        // The alias must land on an action the SCHEMA actually defines —
        // otherwise the dispatch dies exactly like the field's TAP.
        for (action in listOf("TAP", "CLICK", "PRESS", "TYPE", "SWIPE_DOWN")) {
            val m = resolve(action)
            val target = m.mappedAction
            assertNotNull("$action resolved to null", target)
            assertTrue(
                "$action resolved to '$target' which is not in the schema",
                com.tsfdroid.ai.core.agent.ActionSchema.getAllActionNames().contains(target)
            )
        }
    }

    @Test
    fun `genuinely unknown actions do NOT resolve`() {
        val m = resolve("BOOK_CAB")
        // Either unmapped, or mapped to nothing — never a hallucinated target.
        assertTrue(!m.wasMapped || m.mappedAction == null)
        assertNull(resolve("TELEPORT_HOME").mappedAction)
    }
}
