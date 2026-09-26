package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6: the harness-tool bridge turns the `read`/`shell` tool calls the
 * Zen free-tier models emit into real app actions. These tests pin the
 * mapping contract the chat tool loop and plan executor rely on.
 */
class ToolCallBridgeTest {

    @Test
    fun `read maps to READ_FILE`() {
        val mapping = ToolCallBridge.map(LLMToolCall(name = "read", arguments = """{"path": "Documents/site.html"}"""))
        assertEquals("READ_FILE", mapping.mapped?.action)
        assertEquals("Documents/site.html", mapping.mapped?.params?.get("filePath"))
    }

    @Test
    fun `read accepts filePath and file aliases`() {
        assertEquals(
            "Documents/a.txt",
            ToolCallBridge.map(LLMToolCall("read", """{"filePath": "Documents/a.txt"}""")).mapped?.params?.get("filePath")
        )
        assertEquals(
            "Documents/b.txt",
            ToolCallBridge.map(LLMToolCall("read", """{"file": "Documents/b.txt"}""")).mapped?.params?.get("filePath")
        )
    }

    @Test
    fun `read without a path is unsupported with a reason`() {
        val mapping = ToolCallBridge.map(LLMToolCall(name = "read", arguments = "{}"))
        assertNull(mapping.mapped)
        assertNotNull(mapping.unsupportedReason)
    }

    @Test
    fun `heredoc shell write maps to WRITE_FILE with full content`() {
        val cmd = "cat > Documents/website/index.html << 'EOF'\n<!DOCTYPE html>\n<html><body><h1>Hi</h1></body></html>\nEOF"
        val mapping = ToolCallBridge.map(LLMToolCall(name = "shell", arguments = """{"command": "${cmd.replace("\"", "\\\"")}"}"""))
        assertEquals("WRITE_FILE", mapping.mapped?.action)
        assertEquals("Documents/website/index.html", mapping.mapped?.params?.get("filePath"))
        assertTrue(mapping.mapped?.params?.get("content")?.contains("<h1>Hi</h1>") == true)
    }

    @Test
    fun `heredoc with marker before redirect also maps to WRITE_FILE`() {
        val cmd = "cat << EOF > Documents/report.txt\nGold price: 4284\nEOF"
        val mapping = ToolCallBridge.map(LLMToolCall(name = "shell", arguments = """{"command": "$cmd"}"""))
        assertEquals("WRITE_FILE", mapping.mapped?.action)
        assertEquals("Documents/report.txt", mapping.mapped?.params?.get("filePath"))
        assertEquals("Gold price: 4284", mapping.mapped?.params?.get("content"))
    }

    @Test
    fun `echo redirect maps to WRITE_FILE`() {
        val mapping = ToolCallBridge.map(
            LLMToolCall("shell", """{"command": "echo '<html><body>ok</body></html>' > Documents/x.html"}""")
        )
        assertEquals("WRITE_FILE", mapping.mapped?.action)
        assertEquals("Documents/x.html", mapping.mapped?.params?.get("filePath"))
        assertEquals("<html><body>ok</body></html>", mapping.mapped?.params?.get("content"))
    }

    @Test
    fun `append redirection is rejected with guidance`() {
        val mapping = ToolCallBridge.map(LLMToolCall("shell", """{"command": "echo more >> Documents/x.html"}"""))
        assertNull(mapping.mapped)
        assertTrue(mapping.unsupportedReason!!.contains("append"))
    }

    @Test
    fun `mkdir maps to CREATE_DIRECTORY`() {
        val mapping = ToolCallBridge.map(LLMToolCall("shell", """{"command": "mkdir -p Documents/website"}"""))
        assertEquals("CREATE_DIRECTORY", mapping.mapped?.action)
        assertEquals("Documents/website", mapping.mapped?.params?.get("path"))
    }

    @Test
    fun `cat single file maps to READ_FILE`() {
        val mapping = ToolCallBridge.map(LLMToolCall("shell", """{"command": "cat Documents/a.txt"}"""))
        assertEquals("READ_FILE", mapping.mapped?.action)
    }

    @Test
    fun `ls maps to LIST_FILES`() {
        val withDir = ToolCallBridge.map(LLMToolCall("shell", """{"command": "ls Documents"}"""))
        assertEquals("LIST_FILES", withDir.mapped?.action)
        val bare = ToolCallBridge.map(LLMToolCall("shell", """{"command": "ls"}"""))
        assertEquals("LIST_FILES", bare.mapped?.action)
    }

    @Test
    fun `curl maps to FETCH_URL`() {
        val mapping = ToolCallBridge.map(LLMToolCall("shell", """{"command": "curl -s https://example.com/page"}"""))
        assertEquals("FETCH_URL", mapping.mapped?.action)
        assertEquals("https://example.com/page", mapping.mapped?.params?.get("url"))
    }

    @Test
    fun `python and package managers are honestly unsupported`() {
        for (cmd in listOf("python3 -c 'print(1)'", "pip install reportlab", "apt install wkhtmltopdf")) {
            val mapping = ToolCallBridge.map(
                LLMToolCall("shell", """{"command": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), cmd)}}""")
            )
            assertNull("'$cmd' should not map", mapping.mapped)
            assertTrue(
                mapping.unsupportedReason!!.contains("write_file") &&
                    mapping.unsupportedReason!!.contains("web_search")
            )
        }
    }

    @Test
    fun `unknown tool names are unsupported`() {
        val mapping = ToolCallBridge.map(LLMToolCall(name = "browser", arguments = "{}"))
        assertNull(mapping.mapped)
    }

    @Test
    fun `write tool maps directly to WRITE_FILE`() {
        val mapping = ToolCallBridge.map(LLMToolCall("write", """{"path": "Documents/d.txt", "content": "hello"}"""))
        assertEquals("WRITE_FILE", mapping.mapped?.action)
        assertEquals("hello", mapping.mapped?.params?.get("content"))
    }

    @Test
    fun `parseArgs survives malformed json`() {
        assertTrue(ToolCallBridge.parseArgs("not json {").isEmpty())
        assertTrue(ToolCallBridge.parseArgs("").isEmpty())
        assertEquals("x", ToolCallBridge.parseArgs("""{"path": "x"}""")["path"])
    }

    @Test
    fun `tool result rendering truncates long output`() {
        val rendered = ToolCallBridge.renderToolResult(LLMToolCall("read", "{}"), true, "x".repeat(5000))
        assertTrue(rendered.startsWith("TOOL RESULT read"))
        assertTrue(rendered.contains("status: OK"))
        assertTrue(rendered.endsWith("…[truncated]"))
        val failed = ToolCallBridge.renderToolResult(LLMToolCall("shell", "{}"), false, "boom")
        assertTrue(failed.contains("status: ERROR"))
    }
}
