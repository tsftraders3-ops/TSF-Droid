package com.tsfdroid.ai.core.export

import com.tsfdroid.ai.core.harness.ToolCallRecord
import com.tsfdroid.ai.core.harness.ToolCallRecords
import com.tsfdroid.ai.data.models.AttachmentFile
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.MessageAttachments
import com.tsfdroid.ai.data.models.serializeAskOptions
import com.tsfdroid.ai.data.models.serializeMessageAttachments
import com.tsfdroid.ai.data.repository.ChatSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 chat export: round-trip tests for the export document builder and
 * the persisted tool-call log codec. Pure JVM — no Android, no Robolectric —
 * so the standalone kotlinc rig runs the exact same file pre-push.
 *
 * The bar these tests enforce: everything the user asked the export to carry
 * ("how much time the model thought, what it thought, the tool calls, the
 * logs of everything") actually survives the trip into the file — no field
 * silently dropped, no cap silently lying, no null silently swallowed.
 */
class ChatExportTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val app = ChatExportFormat.AppInfo(versionName = "1.4.0-test", versionCode = 13)

    private fun session() = ChatSession(
        id = "sess-1",
        title = "Gold price & debug",
        createdAt = 1_759_500_000_000L,
        updatedAt = 1_759_500_600_000L,
        isCurrent = true
    )

    private fun agentMessage() = ChatMessage(
        id = "msg-agent-1",
        text = "Gold is at \$4,156/oz (kitco.com).",
        sender = ChatMessage.Sender.AGENT,
        timestamp = 1_759_500_300_000L,
        modelBadge = "OpenCode Zen",
        modelId = "mimo/mini-0425",
        thinkingText = "The user wants the current gold price. I should search for it rather than answering from memory…",
        thinkingDurationMs = 12_345L,
        tokensUsed = 2_134,
        turnLatencyMs = 8_910L,
        stepsJson = """[{"kind":"thinking","label":"Thought for 12s","detail":"reasoning phase","status":"done","timestamp":1759500020000},{"kind":"tool","label":"WEB_SEARCH","detail":"5 results","status":"done","timestamp":1759500024000}]""",
        attachmentJson = """{"name":"report.pdf","path":"/workspace/Documents/report.pdf","mime":"application/pdf","size":2048}""",
        askOptionsJson = serializeAskOptions(
            com.tsfdroid.ai.data.models.AskOptions("Per ounce or gram?", listOf("Per ounce", "Per gram"))
        ),
        toolCallsJson = ToolCallRecords.encode(
            listOf(
                ToolCallRecord(
                    tool = "web_search",
                    action = "WEB_SEARCH",
                    argumentsRaw = """{"query":"current gold price per ounce USD"}""",
                    params = mapOf("query" to "current gold price per ounce USD"),
                    success = true,
                    result = "1. Kitco — \$4,156/oz …",
                    startedAt = 1_759_500_020_000L,
                    durationMs = 4_100L
                ),
                ToolCallRecord(
                    tool = "shell",
                    action = "",
                    argumentsRaw = """{"command":"rm -rf /"}""",
                    params = emptyMap(),
                    success = false,
                    error = "shell is not available on this device",
                    startedAt = 1_759_500_024_500L,
                    durationMs = 3L
                )
            )
        )
    )

    private fun userMessage() = ChatMessage(
        id = "msg-user-1",
        text = "What's the gold price today?",
        sender = ChatMessage.Sender.USER,
        timestamp = 1_759_500_010_000L,
        attachmentsJson = serializeMessageAttachments(
            MessageAttachments(
                images = listOf("fakebase64a", "fakebase64b"),
                files = listOf(
                    AttachmentFile(name = "notes.txt", mime = "text/plain", size = 300L, inlineText = "hello")
                ),
                notes = listOf("2 images downscaled")
            )
        ),
        imageBase64 = null
    )

    @Test
    fun `v2 schema - mode and usage wallMs ride every message`() {
        val agent = ChatMessage(
            id = "m-agent",
            text = "Here is the deep research answer.",
            sender = ChatMessage.Sender.AGENT,
            timestamp = 1_759_500_300_000L,
            thinkingDurationMs = 9_000L,
            turnWallMs = 1_020_000L,
            mode = "CHAT"
        )
        val user = ChatMessage(
            id = "m-user",
            text = "deep research on on-device LLMs",
            sender = ChatMessage.Sender.USER,
            timestamp = 1_759_500_000_000L,
            mode = "CHAT"
        )
        val doc = json.parseToJsonElement(
            ChatExportFormat.buildSessionDocument(session(), listOf(user, agent), app)
        ).jsonObject
        val messages = doc["messages"]!!.jsonArray
        val u = messages[0].jsonObject
        val a = messages[1].jsonObject
        // The v1.5.0 field analysis could not tell CHAT from AGENT per turn
        // (its section 10 limitation #1) - now every message carries the mode.
        assertEquals("CHAT", u["mode"]!!.jsonPrimitive.content)
        assertEquals("CHAT", a["mode"]!!.jsonPrimitive.content)
        // The ~9-minute unaccounted expansion window (limitation: latencyMs
        // only covered harness model calls) - wallMs covers the whole turn.
        val usage = a["usage"]!!.jsonObject
        assertEquals(1_020_000L, usage["wallMs"]!!.jsonPrimitive.content.toLong())
        assertTrue(
            "wallMs accounts for more than the thinking phase alone",
            usage["wallMs"]!!.jsonPrimitive.content.toLong() > a["thinking"]!!.jsonObject["durationMs"]!!.jsonPrimitive.content.toLong()
        )
    }

    @Test
    fun `full fidelity round trip - every field survives into the document`() {
        val doc = json.parseToJsonElement(
            ChatExportFormat.buildSessionDocument(session(), listOf(userMessage(), agentMessage()), app)
        ).jsonObject

        // Header
        assertEquals("tsfdroid-chat-export", doc["format"]!!.jsonPrimitive.content)
        assertEquals(2, doc["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("1.4.0-test", doc["app"]!!.jsonObject["versionName"]!!.jsonPrimitive.content)
        assertEquals("Gold price & debug", doc["session"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(2, doc["session"]!!.jsonObject["messageCount"]!!.jsonPrimitive.content.toInt())
        assertTrue("iso8601 stamp present", "iso8601" in doc["exportedAt"]!!.jsonObject)

        val messages = doc["messages"]!!.jsonArray
        assertEquals(2, messages.size)

        // USER message: attachments metadata only, no base64 anywhere.
        val user = messages[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals("What's the gold price today?", user["text"]!!.jsonPrimitive.content)
        assertTrue("thinking is JSON null", user["thinking"] is JsonNull)
        assertEquals(0, user["toolCalls"]!!.jsonArray.size)
        val atts = user["attachments"]!!.jsonObject
        assertEquals(2, atts["imageCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "not embedded (base64 payloads excluded from exports)",
            atts["images"]!!.jsonPrimitive.content
        )
        val files = atts["files"]!!.jsonArray
        assertEquals(1, files.size)
        assertEquals("notes.txt", files[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(300L, files[0].jsonObject["size"]!!.jsonPrimitive.content.toLong())

        // AGENT message: thinking + duration, model identity, usage, tool calls.
        val agent = messages[1].jsonObject
        assertEquals("assistant", agent["role"]!!.jsonPrimitive.content)
        val model = agent["model"]!!.jsonObject
        assertEquals("OpenCode Zen", model["provider"]!!.jsonPrimitive.content)
        assertEquals("mimo/mini-0425", model["modelId"]!!.jsonPrimitive.content)

        val thinking = agent["thinking"]!!.jsonObject
        assertTrue(
            "full reasoning trace rides the export",
            thinking["text"]!!.jsonPrimitive.content.startsWith("The user wants the current gold price")
        )
        assertEquals(12_345L, thinking["durationMs"]!!.jsonPrimitive.content.toLong())

        val usage = agent["usage"]!!.jsonObject
        assertEquals(2_134, usage["tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(8_910L, usage["latencyMs"]!!.jsonPrimitive.content.toLong())

        val calls = agent["toolCalls"]!!.jsonArray
        assertEquals(2, calls.size)
        val search = calls[0].jsonObject
        assertEquals("web_search", search["tool"]!!.jsonPrimitive.content)
        assertEquals("WEB_SEARCH", search["action"]!!.jsonPrimitive.content)
        assertEquals(
            "current gold price per ounce USD",
            search["params"]!!.jsonObject["query"]!!.jsonPrimitive.content
        )
        assertTrue(search["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4_100L, search["durationMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            1_759_500_020_000L,
            search["startedAt"]!!.jsonObject["epochMs"]!!.jsonPrimitive.content.toLong()
        )

        val refused = calls[1].jsonObject
        assertFalse(refused["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("", refused["action"]!!.jsonPrimitive.content)
        assertNotNull(refused["error"])
        assertTrue(refused["result"] is JsonNull)

        // Activity steps + artifact card + ask options.
        assertEquals(2, agent["activitySteps"]!!.jsonArray.size)
        assertEquals("WEB_SEARCH", agent["activitySteps"]!!.jsonArray[1].jsonObject["label"]!!.jsonPrimitive.content)
        val artifact = agent["artifact"]!!.jsonObject
        assertEquals("report.pdf", artifact["name"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", artifact["mime"]!!.jsonPrimitive.content)
        assertEquals("Per ounce or gram?", agent["askOptions"]!!.jsonObject["header"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain chat has honest nulls and empty arrays - never missing keys`() {
        val plain = ChatMessage(id = "m", text = "hi", sender = ChatMessage.Sender.USER, timestamp = 5L)
        val doc = json.parseToJsonElement(
            ChatExportFormat.buildSessionDocument(session(), listOf(plain), app)
        ).jsonObject
        val msg = doc["messages"]!!.jsonArray[0].jsonObject
        assertTrue(msg["thinking"] is JsonNull)
        assertTrue(msg["usage"] is JsonNull)
        assertTrue(msg["artifact"] is JsonNull)
        assertTrue(msg["attachments"] is JsonNull)
        assertEquals(0, msg["toolCalls"]!!.jsonArray.size)
        assertEquals(0, msg["activitySteps"]!!.jsonArray.size)
        // Provider/modelId null honestly (JSON null), not "".
        assertTrue(msg["model"]!!.jsonObject["provider"] is JsonNull)
        assertTrue(msg["model"]!!.jsonObject["modelId"] is JsonNull)
    }

    @Test
    fun `tool result cap flags truncation honestly`() {
        val under = "x".repeat(ToolCallRecords.RESULT_CAP)
        val (textUnder, flagUnder) = ToolCallRecords.capResult(under)
        assertEquals(ToolCallRecords.RESULT_CAP, textUnder!!.length)
        assertFalse(flagUnder)

        val over = "x".repeat(ToolCallRecords.RESULT_CAP + 500)
        val (textOver, flagOver) = ToolCallRecords.capResult(over)
        assertEquals(ToolCallRecords.RESULT_CAP, textOver!!.length)
        assertTrue(flagOver)

        assertNull(ToolCallRecords.capResult(null).first)
    }

    @Test
    fun `tool call records codec round trips and fails safe`() {
        val records = listOf(
            ToolCallRecord(
                tool = "fetch_url", action = "FETCH_URL",
                argumentsRaw = """{"url":"https://example.com"}""",
                params = mapOf("url" to "https://example.com"),
                success = true, result = "Example Domain", truncated = false,
                startedAt = 42L, durationMs = 900L
            )
        )
        val encoded = ToolCallRecords.encode(records)
        assertNotNull(encoded)
        val decoded = ToolCallRecords.decode(encoded)
        assertEquals(records, decoded)

        // Empty encodes to null (Room column stays NULL), garbage decodes to empty.
        assertNull(ToolCallRecords.encode(emptyList()))
        assertTrue(ToolCallRecords.decode("not json at all").isEmpty())
        assertTrue(ToolCallRecords.decode(null).isEmpty())
    }

    @Test
    fun `filename is filesystem safe`() {
        // 1791095412000 ms = 2026-10-04 06:30:12 UTC
        assertEquals(
            "chat-gold-price-20261004-063012.json",
            ChatExportFormat.suggestFileName("Gold / price?!", 1_791_095_412_000L)
        )
        // Blank-after-sanitize title falls back to "chat".
        val blank = ChatExportFormat.suggestFileName("///", 1_791_095_412_000L)
        assertTrue(blank.startsWith("chat-chat-"))
        assertTrue(blank.endsWith(".json"))
    }

    @Test
    fun `activity step trace and thinking duration step both ride the export`() {
        // The "Thought for Xs" label (UI) and the numeric durationMs (export)
        // must never disagree: the number is the source of truth, the label
        // is its rendering. Both come from the same persisted message.
        val msg = agentMessage().copy(
            stepsJson = null,
            thinkingDurationMs = 75_000L
        )
        val doc = json.parseToJsonElement(
            ChatExportFormat.buildSessionDocument(session(), listOf(msg), app)
        ).jsonObject
        val agent = doc["messages"]!!.jsonArray[0].jsonObject
        assertEquals(75_000L, agent["thinking"]!!.jsonObject["durationMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(0, agent["activitySteps"]!!.jsonArray.size)
    }
}
