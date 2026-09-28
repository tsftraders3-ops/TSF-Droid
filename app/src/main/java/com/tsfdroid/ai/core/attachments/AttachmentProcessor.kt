package com.tsfdroid.ai.core.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.tsfdroid.ai.data.models.AttachmentFile
import com.tsfdroid.ai.data.models.MessageAttachments
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.2.0: turns user-picked files into model-ready [MessageAttachments] —
 * the same job OpenCode's image pipeline does before a request:
 *
 *  - Images: downscaled to [MAX_IMAGE_DIMENSION] px and re-encoded through a
 *    JPEG quality ladder until they fit [MAX_IMAGE_BASE64_CHARS] (base64 in
 *    every multimodal request inflates tokens; raw camera JPEGs would too).
 *  - Text-like files (txt/md/code/csv/json...): inlined as text the model can
 *    quote, capped at [MAX_INLINE_TEXT_CHARS].
 *  - PDFs: rendered page images via the platform [PdfRenderer] (up to
 *    [MAX_PDF_PAGES]) — the vision model reads them like photos.
 *  - Videos: sampled frames via [MediaMetadataRetriever] (up to
 *    [MAX_VIDEO_FRAMES]) — a text-only conversation cannot watch video, but a
 *    vision model can read representative frames.
 *
 * The processed bundle is stored ON the message (attachmentsJson), so the
 * model sees the same content the user sees, on this turn and in history.
 */
@Singleton
class AttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Display model for a pending attachment before send. */
    data class Pending(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long
    )

    suspend fun process(pending: List<Pending>): MessageAttachments {
        val images = mutableListOf<String>()
        val files = mutableListOf<AttachmentFile>()
        val notes = mutableListOf<String>()

        for (item in pending) {
            runCatching { processOne(item, images, files, notes) }
                .onFailure { e ->
                    android.util.Log.w("AttachmentProcessor", "Failed ${item.name}: ${e.localizedMessage}")
                    notes.add("Could not read '${item.name}' (${item.mime}).")
                }
        }
        return MessageAttachments(images = images, files = files, notes = notes)
    }

    private fun processOne(
        item: Pending,
        images: MutableList<String>,
        files: MutableList<AttachmentFile>,
        notes: MutableList<String>
    ) {
        val mime = item.mime.lowercase()
        when {
            mime.startsWith("image/") -> {
                val base64 = encodeImage(item.uri) ?: throw IllegalStateException("decode failed")
                images.add(base64)
            }
            mime == "application/pdf" -> {
                val rendered = renderPdfPages(item.uri)
                if (rendered.isEmpty()) {
                    notes.add("'${item.name}' is a PDF but its pages could not be rendered.")
                } else {
                    images.addAll(rendered)
                    notes.add(
                        "'${item.name}' (${pluralPages(rendered.size)}): the attached image parts " +
                            "are its pages, in order."
                    )
                }
            }
            mime.startsWith("video/") -> {
                val frames = sampleVideoFrames(item.uri)
                if (frames.isEmpty()) {
                    notes.add("'${item.name}' is a video but no frames could be sampled.")
                } else {
                    images.addAll(frames)
                    notes.add(
                        "'${item.name}': the attached image parts are ${frames.size} frames sampled " +
                            "through the video (start to end)."
                    )
                }
            }
            isTextLike(item.name, mime) -> {
                val text = readText(item.uri)
                if (text.isNullOrBlank()) {
                    notes.add("'${item.name}' appears to be a text file but is empty or unreadable.")
                } else {
                    val clipped = text.length > MAX_INLINE_TEXT_CHARS
                    files.add(
                        AttachmentFile(
                            name = item.name,
                            mime = mime,
                            size = item.size,
                            inlineText = if (clipped) {
                                text.take(MAX_INLINE_TEXT_CHARS) + "\n…[file truncated at " +
                                    "$MAX_INLINE_TEXT_CHARS characters]"
                            } else {
                                text
                            }
                        )
                    )
                }
            }
            else -> {
                notes.add(
                    "'${item.name}' (${mime.ifBlank { "unknown type" }}) was attached but its " +
                        "content cannot be displayed to this model. Ask the user to paste the " +
                        "relevant text or export it as PDF/PNG."
                )
            }
        }
    }

    private fun pluralPages(n: Int) = if (n == 1) "1 page" else "$n pages"

    /** Resolves display metadata for a picked document uri. */
    fun describe(uri: Uri, fallbackName: String): Pending {
        var name = fallbackName
        var size = 0L
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        return Pending(uri = uri, name = name, mime = mime, size = size)
    }

    // ── images ────────────────────────────────────────────────────────────

    private fun encodeImage(uri: Uri): String? {
        val bitmap = decodeBitmap(uri) ?: return null
        return encodeBitmap(bitmap)
    }

    internal fun decodeBitmap(uri: Uri): Bitmap? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_IMAGE_DIMENSION) {
                    val scale = MAX_IMAGE_DIMENSION.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val raw = android.provider.MediaStore.Images.Media.getBitmap(
                context.contentResolver, uri
            )
            downscale(raw)
        }
    }.getOrNull()

    internal fun encodeBitmap(bitmap: Bitmap): String? {
        var quality = 80
        while (quality >= 40) {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            val base64 = android.util.Base64.encodeToString(
                bytes, android.util.Base64.NO_WRAP
            )
            if (base64.length <= MAX_IMAGE_BASE64_CHARS || quality == 40) {
                return base64
            }
            quality = when (quality) {
                80 -> 60
                60 -> 50
                else -> 40
            }
        }
        return null
    }

    private fun downscale(raw: Bitmap): Bitmap {
        val maxSide = maxOf(raw.width, raw.height)
        if (maxSide <= MAX_IMAGE_DIMENSION) return raw
        val scale = MAX_IMAGE_DIMENSION.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Scales an already-decoded bitmap through the same max-dimension rule. */
    internal fun normalizeBitmap(bitmap: Bitmap): Bitmap = downscale(bitmap)

    // ── pdf ───────────────────────────────────────────────────────────────

    private fun renderPdfPages(uri: Uri): List<String> = runCatching {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val rendered = mutableListOf<String>()
        fd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (index in 0 until pageCount) {
                    renderer.openPage(index).use { page ->
                        // Render at ~1.5x for legible text, then cap dimensions.
                        val targetW = (page.width * PDF_RENDER_SCALE).toInt()
                        val targetH = (page.height * PDF_RENDER_SCALE).toInt()
                        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val normalized = normalizeBitmap(bitmap)
                        encodeBitmap(normalized)?.let(rendered::add)
                    }
                }
            }
        }
        rendered
    }.getOrDefault(emptyList())

    // ── video ─────────────────────────────────────────────────────────────

    private fun sampleVideoFrames(uri: Uri): List<String> = runCatching {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<String>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val stamps = if (durationMs <= 0) {
                listOf(0L)
            } else {
                (0 until MAX_VIDEO_FRAMES).map { position ->
                    durationMs * position / (MAX_VIDEO_FRAMES - 1).coerceAtLeast(1)
                }.distinct()
            }
            for (stamp in stamps) {
                val frame = retriever.getFrameAtTime(
                    stamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val normalized = normalizeBitmap(frame)
                encodeBitmap(normalized)?.let(frames::add)
            }
        } finally {
            runCatching { retriever.release() }
        }
        frames
    }.getOrDefault(emptyList())

    // ── text ──────────────────────────────────────────────────────────────

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    companion object {
        /** OpenCode-style input image ceiling. */
        const val MAX_IMAGE_DIMENSION = 1600

        /** Base64 chars budget per image (raw JPEG stays well under wire limits). */
        const val MAX_IMAGE_BASE64_CHARS = 3_500_000

        /** Inline text cap per text file (chars, ≈25k tokens at 4 chars/token). */
        const val MAX_INLINE_TEXT_CHARS = 100_000

        const val MAX_PDF_PAGES = 6
        const val PDF_RENDER_SCALE = 1.5f
        const val MAX_VIDEO_FRAMES = 4

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "java", "py",
            "rb", "go", "rs", "c", "cpp", "h", "hpp", "cs", "php", "swift", "sh",
            "sql", "log", "ini", "toml", "gradle", "properties", "dart"
        )

        fun isTextLike(name: String, mime: String): Boolean {
            val normalizedMime = mime.lowercase()
            if (normalizedMime.startsWith("text/")) return true
            if (normalizedMime in setOf(
                    "application/json", "application/xml", "application/javascript",
                    "application/x-yaml", "application/toml"
                )
            ) return true
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in TEXT_EXTENSIONS
        }
    }
}
