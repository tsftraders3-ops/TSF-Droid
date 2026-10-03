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
 * v1.3.0: scheme-less citation domains — "Source: CoinDesk (coindesk.com/"
 * "price/bitcoin)" is how live models actually cite on the free tier (E2E
 * cap22 evidence: zero http(s):// in the whole reply). A bare domain is
 * label(.label)+COMMON-TLD, optionally followed by a /path. Restricting the
 * TLD to this curated list is what keeps prose out: "foo.bar", "e.g.",
 * "i.e.", "v1.2" and "node.js" never match; unusual real TLDs still work
 * when cited with their full https:// URL (the first branch).
 */
private const val COMMON_TLDS =
    "com|org|net|io|ai|co|gov|edu|dev|app|info|news|xyz|me|tv|us|uk|in|de|fr|jp|cn|ru|br|ca|au" +
        "|site|online|shop|store|blog|tech|cloud|live|life|world|today|space|link|page|wiki|media" +
        "|video|zone|social|tools|systems|network|digital|finance|market|software|solutions" +
        "|company|studio|design|science|expert|guide|city|events|health|fitness|club|team|group" +
        "|work|jobs|careers|law|legal|tax|insurance|estate|house|home|garden|pet|education|school" +
        "|academy|college|university"

private val bareDomainRegex = Regex(
    "((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+(?:" + COMMON_TLDS + "))(/[^\\s)\\]}>\"'.,;!?]*)?"
)

/**
 * A bare-domain citation may only START after a boundary: start of text,
 * whitespace, or an opening bracket/quote. Starting mid-word ("foo.bar") is
 * prose, not a link.
 */
private fun bareDomainBoundaryOk(text: String, index: Int): Boolean {
    if (index == 0) return true
    val prev = text[index - 1]
    return prev.isWhitespace() || prev in "([{<'\"\u2014" || prev == '\u2018' || prev == '\u201C'
}

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

                // v1.3.0: bare-domain citation — "coindesk.com/price" with no
                // scheme (E2E cap22 evidence: live models cite this way). The
                // span text stays exactly as typed; the link target gets the
                // https:// prefix ACTION_VIEW needs.
                c.isLetter() && bareDomainBoundaryOk(text, i) -> {
                    val m = bareDomainRegex.find(text, i)
                    if (m != null && m.range.first == i && m.value.length >= 5) {
                        flushLiteral()
                        val target = "https://${m.value}"
                        spans += Span(m.value, bold = bold, italic = italic, linkUrl = target)
                        i += m.value.length
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
     * Collects every tappable URL in order of first appearance, capped at
     * [maxUrls]. v1.3.0 round 21 (the 2026-10-03 field evidence: the SOURCES
     * chips showed BOTH "nseindia.com" and "www.nseindia.com", "investing.com"
     * and "www.investing.com"): deduplication is by NORMALIZED key — scheme
     * and leading www. stripped, trailing slash dropped — so www-variants of
     * the same page collapse to ONE chip (the first-seen URL stays the
     * target). See [extractUrls].
     */
    fun extractUrls(text: String, maxUrls: Int): List<String> {
        val seen = LinkedHashSet<String>()
        val seenKeys = mutableSetOf<String>()
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
                        if (seenKeys.add(urlKey(url)) && seen.size < maxUrls) seen += url
                    }
                }
            }
        }
        return seen.toList()
    }

    /**
     * The dedup key for a URL: lowercase, scheme and leading "www." stripped,
     * trailing slash dropped — "https://www.nasdaq.com/" and
     * "http://nasdaq.com" are the same source.
     */
    private fun urlKey(url: String): String {
        var u = url.lowercase().trim()
        u = u.substringAfter("://", u)
        if (u.startsWith("www.")) u = u.removePrefix("www.")
        return u.trimEnd('/')
    }
}
