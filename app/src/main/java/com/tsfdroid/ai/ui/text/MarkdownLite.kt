package com.tsfdroid.ai.ui.text

/**
 * v1.3.0 (Phase 14, Wave B): dependency-free markdown-lite parser.
 *
 * Agent replies (especially web-research answers) arrive as loosely formatted
 * markdown — headings, bullets, fenced code, inline emphasis and raw URLs.
 * Rendering them as one plain string turns a researched answer into "text
 * slop". This module parses a message into typed [Block]s so the chat can lay
 * it out like the Claude / Gemini apps do: clean headings, paragraphs, tappable
 * links and a numbered SOURCES row (see RichMessageText.kt).
 *
 * Scope is deliberately LITE — no tables, no images, no nested lists. The
 * grammar is exactly what conversational models actually emit:
 *  - `#`..`####` headings
 *  - `-` / `*` / `•` / `–` bullet groups (consecutive marker lines merge)
 *  - `1.` `2.` ... numbered groups
 *  - ``` fenced code blocks (opening fence may carry a language tag)
 *  - `>` quote lines (consecutive lines merge into one quote block)
 *  - inline `**bold**`, `*italic*`, `` `code` ``, `[label](url)` and bare
 *    `http(s)://…` URLs (auto-linkified)
 *
 * Robustness contract: unbalanced `**`/`` ` ``/`[` render literally, an
 * unterminated code fence consumes the rest of the text, and [parseMarkdownLite]
 * on an empty string returns an empty list. No visible character of input is
 * ever dropped — only wrapped in different spans (trailing whitespace on a
 * line is discarded, matching markdown's own rules).
 */
sealed class Block {
    /** ATX heading, level 1-4, e.g. `## Title`. */
    data class Heading(val level: Int, val spans: List<Span>) : Block()

    /** Plain paragraph text (consecutive plain lines, newlines preserved). */
    data class Paragraph(val spans: List<Span>) : Block()

    /** Consecutive `- ` / `* ` / `• ` / `– ` lines. */
    data class Bullet(val items: List<List<Span>>) : Block()

    /** Consecutive `N. ` lines, renumbered 1..n by the renderer. */
    data class Numbered(val items: List<List<Span>>) : Block()

    /** Fenced code block; [content] is verbatim (never inline-parsed). */
    data class CodeBlock(val language: String, val content: String) : Block()

    /** `>` quoted line(s), consecutive lines merged into one quote. */
    data class Quote(val spans: List<Span>) : Block()
}

/**
 * One inline-styled run of text. Styles can combine (bold italic, a bold link,
 * code inside bold, …). [linkUrl] being non-null makes the run tappable.
 */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null
)

// Block-level markers. Up to three leading spaces are tolerated (matching
// CommonMark) so softly indented model output still groups into lists.
private val headingRegex = Regex("^ {0,3}(#{1,4})[ \\t]+(\\S.*)$")
private val bulletRegex = Regex("^ {0,3}[-*•–][ \\t]+(.*)$")
private val numberedRegex = Regex("^ {0,3}(\\d{1,3})\\.[ \\t]+(.*)$")
private val quoteRegex = Regex("^ {0,3}> ?(.*)$")
// Inline [label](url) link. NOT ^-anchored: parseInline runs it with
// find(text, i) where ^ would only ever match at index 0 — anchoring is done
// manually by checking the match starts exactly at the scanned position.
private val linkRegex = Regex("\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)")

// Trailing characters stripped from a bare URL before it becomes a link span
// (e.g. "see https://example.com." — the final period is prose, not the URL).
private const val TRAILING_URL_CHARS = ").,;:!?>'\"}]"

/**
 * Parses [text] into markdown-lite [Block]s. Pure Kotlin, no Android or
 * Compose dependencies — unit-testable on the JVM.
 */
fun parseMarkdownLite(text: String): List<Block> = MarkdownLiteParser.parse(text)

/**
 * Returns every URL the renderer would make tappable — inline `[label](url)`
 * links and bare auto-detected URLs — deduplicated by exact string, in order
 * of first appearance. URLs inside fenced code blocks are NOT sources (code
 * is verbatim content, not citation prose), so they are skipped.
 *
 * @param maxUrls caps the result (default 8, matching the SOURCES chip row).
 */
fun extractUrls(text: String, maxUrls: Int = 8): List<String> =
    MarkdownLiteParser.extractUrls(text, maxUrls)

private object MarkdownLiteParser {

    fun parse(text: String): List<Block> {
        if (text.isEmpty()) return emptyList()

        // Tolerate CRLF input; trailing whitespace on a line is never
        // significant in this grammar.
        val lines = text.split("\n").map { it.trimEnd() }
        val blocks = mutableListOf<Block>()

        // Accumulators for the two multi-line plain blocks. Consecutive plain
        // lines merge into one paragraph (newlines kept inside the span text);
        // consecutive `>` lines merge into one quote.
        val paragraphLines = mutableListOf<String>()
        val quoteLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraphLines.joinToString("\n")))
                paragraphLines.clear()
            }
        }

        fun flushQuote() {
            if (quoteLines.isNotEmpty()) {
                blocks += Block.Quote(parseInline(quoteLines.joinToString("\n")))
                quoteLines.clear()
            }
        }

        fun flushBoth() {
            flushParagraph()
            flushQuote()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 1. Fenced code block. The opening fence may carry a language tag
            //    (```kotlin); the closing fence is any line starting with ```.
            //    An unterminated fence consumes the rest of the text rather
            //    than swallowing the marker silently.
            if (line.trimStart().startsWith("```")) {
                flushBoth()
                val language = line.trimStart().removePrefix("```").trim()
                val content = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    content.append(lines[i]).append('\n')
                    i++
                }
                // Skip the closing fence if present (else we hit end of input).
                if (i < lines.size) i++
                blocks += Block.CodeBlock(language, content.toString().removeSuffix("\n"))
                continue
            }

            // 2. Blank line separates every kind of block.
            if (line.isBlank()) {
                flushBoth()
                i++
                continue
            }

            // 3. Heading (#{1,4} + whitespace).
            val headingMatch = headingRegex.find(line)
            if (headingMatch != null) {
                flushBoth()
                blocks += Block.Heading(
                    level = headingMatch.groupValues[1].length,
                    spans = parseInline(headingMatch.groupValues[2])
                )
                i++
                continue
            }

            // 4. Bullet group: consecutive marker lines.
            val bulletMatch = bulletRegex.find(line)
            if (bulletMatch != null) {
                flushBoth()
                val items = mutableListOf<List<Span>>()
                items += parseInline(bulletMatch.groupValues[1])
                i++
                while (i < lines.size) {
                    val itemMatch = bulletRegex.find(lines[i]) ?: break
                    items += parseInline(itemMatch.groupValues[1])
                    i++
                }
                blocks += Block.Bullet(items)
                continue
            }

            // 5. Numbered group: consecutive `N. ` lines.
            val numberedMatch = numberedRegex.find(line)
            if (numberedMatch != null) {
                flushBoth()
                val items = mutableListOf<List<Span>>()
                items += parseInline(numberedMatch.groupValues[2])
                i++
                while (i < lines.size) {
                    val itemMatch = numberedRegex.find(lines[i]) ?: break
                    items += parseInline(itemMatch.groupValues[2])
                    i++
                }
                blocks += Block.Numbered(items)
                continue
            }

            // 6. Quote line: accumulates into the current quote block.
            val quoteMatch = quoteRegex.find(line)
            if (quoteMatch != null) {
                flushParagraph()
                quoteLines += quoteMatch.groupValues[1]
                i++
                continue
            }

            // 7. Plain text: accumulates into the current paragraph. A quote
            //    ends here so paragraph/quote blocks always emit in source
            //    order.
            flushQuote()
            paragraphLines += line
            i++
        }

        flushBoth()
        return blocks
    }

    /**
     * Inline scanner. Walks [text] once and emits styled [Span]s. Emphasis can
     * nest one level deep (e.g. bold text containing an italic word, or a bold
     * link) via recursion; code spans are opaque (their content is verbatim).
     * Unbalanced markers are emitted literally, never dropped.
     */
    fun parseInline(text: String, bold: Boolean = false, italic: Boolean = false): List<Span> {
        if (text.isEmpty()) return emptyList()

        val spans = mutableListOf<Span>()
        val literal = StringBuilder()
        var i = 0

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                spans += Span(literal.toString(), bold = bold, italic = italic)
                literal.clear()
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                // **bold** — checked before single * so `**` is never half-read
                // as italic.
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val close = text.indexOf("**", i + 2)
                    if (close > i + 2) {
                        flushLiteral()
                        spans += parseInline(
                            text.substring(i + 2, close),
                            bold = true,
                            italic = italic
                        )
                        i = close + 2
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                // `code` — content is verbatim (no nested parsing).
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1) {
                        flushLiteral()
                        spans += Span(text.substring(i + 1, close), code = true)
                        i = close + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                // *italic* — single asterisk only (`**` was handled above).
                c == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close > i + 1) {
                        flushLiteral()
                        spans += parseInline(
                            text.substring(i + 1, close),
                            bold = bold,
                            italic = true
                        )
                        i = close + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                // [label](url) — only http(s) targets become links; anything
                // else (relative paths, malformed pairs) stays literal.
                c == '[' -> {
                    val m = linkRegex.find(text, i)
                    if (m != null && m.range.first == i) {
                        flushLiteral()
                        spans += Span(
                            text = m.groupValues[1],
                            bold = bold,
                            italic = italic,
                            linkUrl = m.groupValues[2]
                        )
                        i = m.range.last + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                // Bare URL auto-detection: http(s):// at a word boundary. The
                // run ends at whitespace, then trailing punctuation is trimmed
                // back into the literal stream so no character disappears.
                (c == 'h' || c == 'H') && isUrlStart(text, i) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace()) end++
                    var url = text.substring(i, end)
                    while (url.isNotEmpty() && url.last() in TRAILING_URL_CHARS) {
                        url = url.dropLast(1)
                    }
                    val schemeEnd = url.indexOf("://")
                    if (schemeEnd >= 0 && url.length > schemeEnd + 3) {
                        flushLiteral()
                        spans += Span(url, bold = bold, italic = italic, linkUrl = url)
                        // Advance only past the URL — trimmed punctuation stays
                        // in the stream and renders literally after the link.
                        i += url.length
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }

        flushLiteral()
        return spans
    }

    /** True when a bare http(s) URL begins at [i] on a word boundary. */
    private fun isUrlStart(text: String, i: Int): Boolean {
        val starts = text.startsWith("http://", i, ignoreCase = true) ||
            text.startsWith("https://", i, ignoreCase = true)
        if (!starts) return false
        if (i == 0) return true
        val prev = text[i - 1]
        return prev.isWhitespace() || prev in "([{\"'<"
    }

    /**
     * Collects every tappable URL in order of first appearance, deduplicated
     * by exact string and capped at [maxUrls]. See [extractUrls].
     */
    fun extractUrls(text: String, maxUrls: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (block in parse(text)) {
            val spanLists: List<List<Span>> = when (block) {
                is Block.Heading -> listOf(block.spans)
                is Block.Paragraph -> listOf(block.spans)
                is Block.Quote -> listOf(block.spans)
                is Block.Bullet -> block.items
                is Block.Numbered -> block.items
                is Block.CodeBlock -> continue // verbatim content, not a source
            }
            for (spans in spanLists) {
                for (span in spans) {
                    span.linkUrl?.let { url ->
                        // LinkedHashSet.add on an existing element is a no-op
                        // (position preserved), so the size check caps only
                        // NEW urls.
                        if (seen.size < maxUrls) seen += url
                    }
                }
            }
        }
        return seen.toList()
    }
}
