package com.tsfdroid.ai.e2e

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tsfdroid.ai.core.attachments.AttachmentProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v1.2.1 BAR-UPLOAD: the attachment pipeline, deterministic on-device (no
 * system picker UI): a real PNG becomes one send-ready image, a real text file
 * inlines its content, an unknown binary degrades to an honest note, and the
 * whole result serializes into the persisted [MessageAttachments] JSON.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentPipelineInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = AttachmentProcessor(context)

    private fun makePng(name: String, w: Int = 2400, h: Int = 4000): AttachmentProcessor.Pending {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(120, 40, 200))
        val dir = File(context.cacheDir, "e2e-attach").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return AttachmentProcessor.Pending(
            uri = android.net.Uri.fromFile(file),
            name = name,
            mime = "image/png",
            size = file.length()
        )
    }

    private fun makeTextFile(name: String, content: String): AttachmentProcessor.Pending {
        val dir = File(context.cacheDir, "e2e-attach").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(content)
        return AttachmentProcessor.Pending(
            uri = android.net.Uri.fromFile(file),
            name = name,
            mime = "text/plain",
            size = file.length()
        )
    }

    @Test
    fun imageAttachment_producesSendReadyImage() = runBlocking {
        val processed = processor.process(listOf(makePng("big.png")))
        assertEquals(1, processed.images.size)
        val bytes = android.util.Base64.decode(processed.images[0], android.util.Base64.NO_WRAP)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("the encoded image does not decode back", decoded)
        // JPEG magic: FF D8 FF
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun textAttachment_inlinesContent() = runBlocking {
        val processed = processor.process(
            listOf(makeTextFile("notes.md", "The launch code is ORION-7."))
        )
        assertTrue(processed.images.isEmpty())
        val file = processed.files.firstOrNull { it.name == "notes.md" }
        assertNotNull("text file missing from processed output", file)
        assertTrue(
            "text file content was not inlined",
            file!!.inlineText?.contains("ORION-7") == true
        )
    }

    @Test
    fun unknownBinary_degradesToHonestNote() = runBlocking {
        val dir = File(context.cacheDir, "e2e-attach").apply { mkdirs() }
        val file = File(dir, "blob.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, -1, -2)) }
        val processed = processor.process(
            listOf(
                AttachmentProcessor.Pending(
                    uri = android.net.Uri.fromFile(file),
                    name = "blob.bin",
                    mime = "application/octet-stream",
                    size = file.length()
                )
            )
        )
        assertTrue(processed.images.isEmpty())
        assertTrue(
            "an unreadable binary must produce a note, not silent success",
            processed.notes.isNotEmpty()
        )
    }

    @Test
    fun attachmentsSerializeRoundTrip() {
        val attachments = com.tsfdroid.ai.data.models.MessageAttachments(
            images = listOf("AAA", "BBB"),
            files = listOf(
                com.tsfdroid.ai.data.models.AttachmentFile(
                    name = "a.txt", mime = "text/plain", size = 2, inlineText = "hi"
                )
            ),
            notes = listOf("PDF page images")
        )
        val json = com.tsfdroid.ai.data.models.serializeMessageAttachments(attachments)
        val decoded = com.tsfdroid.ai.data.models.parseMessageAttachments(json)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.images.size)
        assertEquals("a.txt", decoded.files.first().name)
        assertEquals("PDF page images", decoded.notes.first())
    }
}
