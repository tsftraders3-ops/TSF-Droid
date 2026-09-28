package com.tsfdroid.ai.core.llm.prompts

import com.tsfdroid.ai.data.models.ChatMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0: the harness prompts are behavioral contracts. These tests pin the
 * clauses that field failures proved essential — the tool-call discipline,
 * the continuation protocol, and the ABSOLUTE Chat-mode read-only boundary.
 */
class HarnessPromptsTest {

    private val chatPrompt = HarnessPrompts.harnessSystemPrompt(
        mode = ChatMode.CHAT,
        autoModeLabel = "OFF",
        relevantContext = "user: vibe coder",
        dateTimeLine = "Monday, 28 September 2026, 9:00 AM"
    )

    private val agentPrompt = HarnessPrompts.harnessSystemPrompt(
        mode = ChatMode.AGENT,
        autoModeLabel = "AUTO",
        relevantContext = "user: vibe coder",
        dateTimeLine = "Monday, 28 September 2026, 9:00 AM"
    )

    @Test
    fun `both prompts carry the tool-call discipline contract`() {
        for (prompt in listOf(chatPrompt, agentPrompt)) {
            assertTrue(prompt.contains("CALL THE"))
            assertTrue(prompt.contains("NEVER only promise", ignoreCase = true))
            assertTrue(prompt.contains("web_search"))
            assertTrue(prompt.contains("fetch_url"))
            assertTrue(prompt.contains("read_file"))
        }
    }

    @Test
    fun `both prompts carry the continuation protocol`() {
        for (prompt in listOf(chatPrompt, agentPrompt)) {
            assertTrue(prompt.contains("CONTINUATION PROTOCOL"))
            assertTrue(prompt.contains("resume", ignoreCase = true))
            assertTrue(prompt.contains("no repetition", ignoreCase = true))
        }
    }

    @Test
    fun `both prompts carry the attachment awareness clause`() {
        for (prompt in listOf(chatPrompt, agentPrompt)) {
            assertTrue(prompt.contains("ATTACHMENTS"))
            assertTrue(prompt.contains("image", ignoreCase = true))
        }
    }

    @Test
    fun `both prompts carry the adaptive length contract`() {
        for (prompt in listOf(chatPrompt, agentPrompt)) {
            assertTrue(prompt.contains("ANSWER LENGTH"))
            assertTrue(prompt.contains("finish the full answer"))
            assertTrue(prompt.contains("output budget", ignoreCase = true))
        }
    }

    @Test
    fun `chat prompt declares the read-only boundary and never mentions writes`() {
        assertTrue(chatPrompt.contains("CHAT MODE"))
        assertTrue(chatPrompt.contains("Agent mode"))
        assertFalse(
            "chat prompt must not advertise write_file",
            chatPrompt.contains("write_file")
        )
        assertFalse(
            "chat prompt must not advertise create_pdf",
            chatPrompt.contains("create_pdf")
        )
        assertTrue(chatPrompt.contains("MUST NOT"))
    }

    @Test
    fun `agent prompt advertises writes and the artifact quality bar`() {
        assertTrue(agentPrompt.contains("write_file"))
        assertTrue(agentPrompt.contains("create_pdf"))
        assertTrue(agentPrompt.contains("COMPLETE content in content"))
        assertTrue(agentPrompt.contains("QUALITY BAR"))
        assertTrue(agentPrompt.contains("lorem ipsum", ignoreCase = true))
        // Auto-mode label is agent-only context.
        assertTrue(agentPrompt.contains("AUTO"))
    }

    @Test
    fun `tool name lists match the prompts`() {
        assertTrue(HarnessPrompts.AGENT_TOOLS.containsAll(HarnessPrompts.CHAT_TOOLS))
        assertTrue("write_file" in HarnessPrompts.AGENT_TOOLS)
        assertTrue("create_pdf" in HarnessPrompts.AGENT_TOOLS)
        assertFalse("write_file" in HarnessPrompts.CHAT_TOOLS)
        assertFalse("create_pdf" in HarnessPrompts.CHAT_TOOLS)
    }

    @Test
    fun `context and date are injected`() {
        assertTrue(chatPrompt.contains("vibe coder"))
        assertTrue(agentPrompt.contains("vibe coder"))
        assertTrue(chatPrompt.contains("28 September 2026"))
    }
}
