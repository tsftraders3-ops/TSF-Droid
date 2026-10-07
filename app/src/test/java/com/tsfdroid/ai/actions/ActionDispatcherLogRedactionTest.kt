package com.tsfdroid.ai.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-06 (audit fc9ea97): ActionDispatcher used to log dispatched parameter
 * VALUES (up to 60 chars each, 400 total) — which can carry message bodies,
 * file content, or URLs. The redaction keeps keys and value lengths only.
 */
class ActionDispatcherLogRedactionTest {

    @Test
    fun `parameter values never appear in the dispatched-params log`() {
        val params = mapOf(
            "message" to "hey, the bank transfer code is 554433 — send it now",
            "url" to "https://example.com/secret/path?token=abc123",
            "content" to "line one of the user's file\nline two"
        )
        val logged = ActionDispatcher.redactedParamsForLog(params)

        params.values.forEach { value ->
            // Any substring of a value that could survive in the log
            value.take(20).let { fragment ->
                assertFalse(
                    "log line '$logged' must not contain value fragment '$fragment'",
                    logged.contains(fragment)
                )
            }
        }
        assertFalse(logged.contains("554433"))
        assertFalse(logged.contains("token=abc123"))
    }

    @Test
    fun `parameter keys and value lengths are preserved for debugging`() {
        val logged = ActionDispatcher.redactedParamsForLog(mapOf("message" to "12345"))
        assertTrue(logged.contains("message"))
        assertTrue(logged.contains("5"))
        assertTrue(logged.matches(Regex("message=<5 chars>")))
    }

    @Test
    fun `empty params log empty`() {
        assertTrue(ActionDispatcher.redactedParamsForLog(emptyMap()).isEmpty())
    }
}
