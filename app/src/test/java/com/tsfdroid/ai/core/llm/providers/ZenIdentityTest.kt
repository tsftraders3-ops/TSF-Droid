package com.tsfdroid.ai.core.llm.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity OpenCode Zen sees on every request must be byte-for-byte the
 * shape the official client sends: anything else earns a 403 FreeTierError.
 * These tests pin the generator format and the header map.
 */
class ZenIdentityTest {

    private val bodyRegex = Regex("""[0-9a-f]{12}[0-9A-Za-z]{14}""")

    @Test
    fun `descending bodies are 26 chars - timestamp head plus base62 tail`() {
        repeat(50) {
            val id = ZenIdentity.descending()
            assertTrue("got: $id", bodyRegex.matches(id))
            assertEquals(26, id.length)
        }
    }

    @Test
    fun `descending bodies are unique under rapid minting`() {
        val ids = (1..200).map { ZenIdentity.descending() }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun `session and request ids carry the official prefixes`() {
        assertTrue(ZenIdentity.sessionId().startsWith("ses_"))
        assertTrue(ZenIdentity.sessionId().length == 30)
        assertTrue(ZenIdentity.requestId().startsWith("msg_"))
        assertTrue(ZenIdentity.requestId().length == 30)
    }

    @Test
    fun `user agent matches the official four-segment shape`() {
        assertEquals("opencode/latest/2.0.16/cli", ZenIdentity.UA)
    }

    @Test
    fun `identity headers are the exact provenance set`() {
        val headers = ZenIdentity.identityHeaders("p01", "ses_1", "msg_1")

        assertEquals(5, headers.size)
        assertEquals("p01", headers["x-opencode-project"])
        assertEquals("ses_1", headers["x-opencode-session"])
        assertEquals("msg_1", headers["x-opencode-request"])
        assertEquals("cli", headers["x-opencode-client"])
        assertEquals(ZenIdentity.UA, headers["User-Agent"])
    }

    @Test
    fun `anonymous key is the literal public string`() {
        assertEquals("public", ZenIdentity.ANONYMOUS_KEY)
    }

    @Test
    fun `project ids are stable per call site but distinct between instances`() {
        val a = ZenIdentity.projectId()
        val b = ZenIdentity.projectId()
        assertNotEquals(a, b)
        assertTrue(bodyRegex.matches(a))
    }
}
