package com.tsfdroid.ai.data.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0: the attachment payload rides inside every chat message — the JSON
 * shape must round-trip exactly, degrade safely on garbage, and the text-file
 * classifier must cover the formats users actually attach.
 */
class MessageAttachmentsTest {

    @Test
    fun `attachments round-trip through JSON`() {
        val original = MessageAttachments(
            images = listOf("QUJD", "RERE"),
            files = listOf(
                AttachmentFile("report.txt", "text/plain", 1234, "file body"),
                AttachmentFile("data.csv", "text/csv", 42, "a,b,c")
            ),
            notes = listOf("PDF pages rendered as images")
        )

        val json = serializeMessageAttachments(original)
        val parsed = parseMessageAttachments(json)

        assertEquals(original, parsed)
    }

    @Test
    fun `null or garbage json parses to null, never throws`() {
        assertNull(parseMessageAttachments(null))
        assertNull(parseMessageAttachments(""))
        assertNull(parseMessageAttachments("not json at all"))
        assertNull(parseMessageAttachments("[1,2,3]"))
    }

    @Test
    fun `unknown json keys are ignored for forward compatibility`() {
        val parsed = parseMessageAttachments(
            """{"images":[],"files":[],"notes":[],"someFutureField":123}"""
        )
        assertEquals(MessageAttachments(), parsed)
    }

    @Test
    fun `allImages combines the screenshot and uploaded images in order`() {
        val message = ChatMessage(
            id = "1",
            text = "look",
            sender = ChatMessage.Sender.USER,
            imageBase64 = "SCREENSHOT",
            attachmentsJson = serializeMessageAttachments(
                MessageAttachments(images = listOf("UPLOAD1", "UPLOAD2"))
            )
        )

        assertEquals(listOf("SCREENSHOT", "UPLOAD1", "UPLOAD2"), message.allImages())
    }

    @Test
    fun `a message without uploads has no images`() {
        val message = ChatMessage("1", "plain", ChatMessage.Sender.USER)
        assertTrue(message.allImages().isEmpty())
        assertNull(message.attachments())
    }

    @Test
    fun `emptyness flags behave`() {
        assertTrue(MessageAttachments().isEmpty)
        assertFalse(MessageAttachments(images = listOf("x")).isEmpty)
        assertTrue(MessageAttachments(images = listOf("x")).hasImages)
        assertFalse(MessageAttachments(files = listOf(AttachmentFile("a", "text/plain"))).isEmpty)
    }

    @Test
    fun `text-like detection covers code, docs and mime types`() {
        assertTrue(AttachmentProcessorShim.isTextLike("main.kt", ""))
        assertTrue(AttachmentProcessorShim.isTextLike("README.md", ""))
        assertTrue(AttachmentProcessorShim.isTextLike("data.csv", "application/octet-stream"))
        assertTrue(AttachmentProcessorShim.isTextLike("anything.bin", "text/plain"))
        assertTrue(AttachmentProcessorShim.isTextLike("cfg.json", "application/json"))
        assertFalse(AttachmentProcessorShim.isTextLike("photo.jpg", "image/jpeg"))
        assertFalse(AttachmentProcessorShim.isTextLike("song.mp3", "audio/mpeg"))
        assertFalse(AttachmentProcessorShim.isTextLike("archive.zip", "application/zip"))
        // extension heuristics are case-insensitive
        assertTrue(AttachmentProcessorShim.isTextLike("SCRIPT.SH", ""))
    }

    @Test
    fun `llm config serializes chat mode and effort level`() {
        val config = LLMConfig(chatMode = ChatMode.CHAT, reasoningEffort = "high")
        val json = Json.encodeToString(LLMConfig.serializer(), config)
        val parsed = Json.decodeFromString(LLMConfig.serializer(), json)
        assertEquals(ChatMode.CHAT, parsed.chatMode)
        assertEquals("high", parsed.reasoningEffort)
    }

    @Test
    fun `legacy configs without chat mode resolve to agent`() {
        val legacy = Json.decodeFromString(
            LLMConfig.serializer(),
            """{"activeProvider":"OpenCode Zen","activeModel":"mimo-v2.6-flash-free"}"""
        )
        assertEquals(ChatMode.AGENT, ChatMode.fromNullable(legacy.chatMode))
        assertNull(legacy.reasoningEffort)
    }
}

/** Indirection so the test does not need a Robolectric Android context. */
private object AttachmentProcessorShim {
    fun isTextLike(name: String, mime: String): Boolean =
        com.tsfdroid.ai.core.attachments.AttachmentProcessor.isTextLike(name, mime)
}
