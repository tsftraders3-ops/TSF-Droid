package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.core.llm.LLMToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Maps OpenAI-style function tool calls (the Zen free tier forces `read` and
 * `shell` into every request body, so reasoning models answer the harness
 * contract) onto the app's real registered actions.
 *
 * v1.0.6: a tool-call answer used to be terminal MALFORMED_RESPONSE — the
 * user asked for a PDF report and got "unreadable response". The tool calls
 * are now mapped onto the same action pipeline plans use:
 *
 *  - read {path}                    → READ_FILE
 *  - shell {command} with a file
 *    redirect (echo/cat/printf)     → WRITE_FILE
 *  - shell mkdir -p <dir>           → CREATE_DIRECTORY
 *  - shell cat/ls                   → READ_FILE / LIST_FILES
 *  - shell curl/wget <url>          → FETCH_URL
 *  - anything else                  → honest "not available" result the model
 *                                     can read and adjust to.
 *
 * Pure mapping only — execution happens through [ActionDispatcher] so every
 * call passes the same permission/confirmation gates as planned steps.
 */
object ToolCallBridge {

    data class MappedToolCall(val action: String, val params: Map<String, String>)

    data class Mapping(val mapped: MappedToolCall?, val unsupportedReason: String? = null)

    /**
     * Maps one tool call. Returns a [Mapping] with either a real action or an
     * unsupported reason the caller feeds back to the model as the tool result.
     */
    fun map(call: LLMToolCall): Mapping {
        return when (call.name.lowercase().trim()) {
            "read" -> mapRead(parseArgs(call.arguments))
            "shell" -> mapShell(parseArgs(call.arguments))
            "write" -> {
                val args = parseArgs(call.arguments)
                Mapping(MappedToolCall("WRITE_FILE", mapOf("filePath" to args.path(), "content" to args.content())))
            }
            else -> Mapping(null, "Tool '${call.name}' is not available in this environment. Available: read, shell.")
        }
    }

    private fun mapRead(args: Map<String, String>): Mapping {
        val path = args.path()
        if (path.isBlank()) return Mapping(null, "read: missing path argument")
        return Mapping(MappedToolCall("READ_FILE", mapOf("filePath" to path)))
    }

    /**
     * Recognizes the shell commands a file-creating agent actually emits:
     * heredocs, echo/printf redirects, mkdir, cat, ls, curl/wget. Only the
     * redirected-content forms write files; plain shell execution is never
     * attempted (no shell on Android).
     */
    private fun mapShell(args: Map<String, String>): Mapping {
        val command = args["command"] ?: args["cmd"] ?: args.values.firstOrNull() ?: ""
        val trimmed = command.trim()

        // Heredoc: cat > file << 'EOF' ... / cat << EOF > file
        val heredoc = Regex("(?s)^(?:cat|tee)\\s*>+\\s*(\\S+)\\s*<<-?\\s*['\"]?(\\w+)['\"]?\\s*\\n(.*?)\\n?\\2\\s*$")
        heredoc.find(trimmed)?.let { m ->
            return Mapping(MappedToolCall("WRITE_FILE", mapOf("filePath" to m.groupValues[1], "content" to m.groupValues[3])))
        }
        val heredocAfter = Regex("(?s)^(?:cat|tee)\\s*<<-?\\s*['\"]?(\\w+)['\"]?\\s*>+\\s*(\\S+)\\s*\\n(.*?)\\n?\\1\\s*$")
        heredocAfter.find(trimmed)?.let { m ->
            return Mapping(MappedToolCall("WRITE_FILE", mapOf("filePath" to m.groupValues[2], "content" to m.groupValues[3])))
        }

        // Redirect: echo/printf 'text' > file  (append >> rejected: model
        // should not append blind; re-ask handles it)
        val redirect = Regex("^(?:echo|printf)\\s+(.+?)\\s*>{1,2}\\s*(\\S+)\\s*$")
        redirect.find(trimmed)?.let { m ->
            val text = unquote(m.groupValues[1].removePrefix("-e ").removePrefix("-n "))
            val op = m.groupValues[0].contains(">>")
            if (op) return Mapping(null, "append redirection is not supported; rewrite the full file instead")
            return Mapping(MappedToolCall("WRITE_FILE", mapOf("filePath" to m.groupValues[2], "content" to text)))
        }

        // mkdir -p dir
        val mkdir = Regex("^mkdir\\s+(?:-p\\s+)?(\\S+)\\s*$")
        mkdir.find(trimmed)?.let { m ->
            return Mapping(MappedToolCall("CREATE_DIRECTORY", mapOf("path" to m.groupValues[1])))
        }

        // cat file → READ_FILE (single file only)
        val cat = Regex("^cat\\s+(\\S+)\\s*$")
        cat.find(trimmed)?.let { m ->
            return Mapping(MappedToolCall("READ_FILE", mapOf("filePath" to m.groupValues[1])))
        }

        // ls [dir] → LIST_FILES
        val ls = Regex("^ls\\s*(?:-\\w+\\s*)?(\\S+)?\\s*$")
        ls.find(trimmed)?.let { m ->
            val dir = m.groupValues[1] ?: ""
            return Mapping(MappedToolCall("LIST_FILES", if (dir.isBlank()) emptyMap() else mapOf("path" to dir)))
        }

        // curl/wget URL → FETCH_URL
        val fetch = Regex("^(?:curl|wget)\\b.*?['\"]?(https?://\\S+?)['\"]?\\s*$")
        fetch.find(trimmed)?.let { m ->
            return Mapping(MappedToolCall("FETCH_URL", mapOf("url" to m.groupValues[1])))
        }

        // python/node/pip/apt/npm and friends: honestly unsupported.
        return Mapping(
            null,
            "shell is not available on this device and '${trimmed.take(60)}' cannot be run. " +
                "Use the app's actions instead: WRITE_FILE {filePath, content}, CREATE_PDF {filePath, title, content}, " +
                "FETCH_URL {url}, WEB_SEARCH {query}."
        )
    }

    /** Lenient JSON object parser for tool arguments (never throws). */
    fun parseArgs(arguments: String): Map<String, String> {
        val trimmed = arguments.trim()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(trimmed)
            if (obj is JsonObject) {
                val result = mutableMapOf<String, String>()
                for ((k, v) in obj) {
                    val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                    result[k] = value
                }
                result
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun Map<String, String>.path(): String =
        this["path"] ?: this["filePath"] ?: this["file_path"] ?: this["file"] ?: ""

    private fun Map<String, String>.content(): String =
        this["content"] ?: this["text"] ?: this["body"] ?: ""

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /** Renders one executed tool call + its result as a tool-role text block. */
    fun renderToolResult(call: LLMToolCall, success: Boolean, output: String): String {
        val status = if (success) "OK" else "ERROR"
        val body = if (output.length > 4000) output.take(4000) + "…[truncated]" else output
        return "TOOL RESULT ${call.name} (status: $status):\n$body"
    }
}
