package com.tsfdroid.ai.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 (Phase 14, Wave B): unit tests for the markdown-lite parser.
 *
 * Pure-Kotlin parser, plain JUnit4 — no Robolectric, no Android classes.
 * The E2E contract that matters for the UI is covered here at the model level:
 * every character of a message must survive parsing (the chat's a11y tree and
 * UIAutomator text search depend on it).
 */
class MarkdownLiteTest {

    // ── Block-level: headings ────────────────────────────────────────────────

    @Test
    fun `empty string parses to empty list`() {
        assertTrue(parseMarkdownLite("").isEmpty())
    }

    @Test
    fun `plain text becomes a single paragraph`() {
        val blocks = parseMarkdownLite("Hello world")
        assertEquals(listOf(Block.Paragraph(listOf(Span("Hello world")))), blocks)
    }

    @Test
    fun `blank lines split paragraphs`() {
        val blocks = parseMarkdownLite("one\n\ntwo")
        assertEquals(2, blocks.size)
        assertEquals(Block.Paragraph(listOf(Span("one"))), blocks[0])
        assertEquals(Block.Paragraph(listOf(Span("two"))), blocks[1])
    }

    @Test
    fun `consecutive plain lines merge into one paragraph keeping newlines`() {
        val blocks = parseMarkdownLite("line one\nline two")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("line one\nline two")))),
            blocks
        )
    }

    @Test
    fun `headings level one to four`() {
        val blocks = parseMarkdownLite("# A\n## B\n### C\n#### D")
        assertEquals(4, blocks.size)
        assertEquals(Block.Heading(1, listOf(Span("A"))), blocks[0])
        assertEquals(Block.Heading(2, listOf(Span("B"))), blocks[1])
        assertEquals(Block.Heading(3, listOf(Span("C"))), blocks[2])
        assertEquals(Block.Heading(4, listOf(Span("D"))), blocks[3])
    }

    @Test
    fun `heading needs whitespace after hashes and five hashes are literal`() {
        // Neither line is a heading; consecutive plain lines merge into one
        // paragraph (newlines kept).
        val blocks = parseMarkdownLite("#NotHeading\n##### Five")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("#NotHeading\n##### Five")))),
            blocks
        )
    }

    @Test
    fun `bold inside heading`() {
        val blocks = parseMarkdownLite("## **Bold** title")
        assertEquals(
            listOf(
                Block.Heading(
                    level = 2,
                    spans = listOf(Span("Bold", bold = true), Span(" title"))
                )
            ),
            blocks
        )
    }

    // ── Inline: emphasis, code, links ───────────────────────────────────────

    @Test
    fun `bold inline`() {
        val blocks = parseMarkdownLite("a **b** c")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(Span("a "), Span("b", bold = true), Span(" c"))
                )
            ),
            blocks
        )
    }

    @Test
    fun `italic is single asterisk not double`() {
        val blocks = parseMarkdownLite("a *b* c")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(Span("a "), Span("b", italic = true), Span(" c"))
                )
            ),
            blocks
        )
    }

    @Test
    fun `nested italic inside bold`() {
        val blocks = parseMarkdownLite("**a *b* c**")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(
                        Span("a ", bold = true),
                        Span("b", bold = true, italic = true),
                        Span(" c", bold = true)
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `inline code is verbatim`() {
        val blocks = parseMarkdownLite("run `foo` now")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(Span("run "), Span("foo", code = true), Span(" now"))
                )
            ),
            blocks
        )
    }

    @Test
    fun `markers inside inline code are not parsed`() {
        val blocks = parseMarkdownLite("`**x**`")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("**x**", code = true)))),
            blocks
        )
    }

    @Test
    fun `unbalanced bold and backtick render literally`() {
        val blocks = parseMarkdownLite("**broken and `open")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("**broken and `open")))),
            blocks
        )
    }

    @Test
    fun `markdown link with label`() {
        val blocks = parseMarkdownLite("See [docs](https://a.com/x) here")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(
                        Span("See "),
                        Span("docs", linkUrl = "https://a.com/x"),
                        Span(" here")
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `non-http link target stays literal`() {
        val blocks = parseMarkdownLite("[x](ftp://a.com)")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("[x](ftp://a.com)")))),
            blocks
        )
    }

    @Test
    fun `bare url becomes link span`() {
        val blocks = parseMarkdownLite("visit https://example.com now")
        assertEquals(
            listOf(
                Block.Paragraph(
                    listOf(
                        Span("visit "),
                        Span("https://example.com", linkUrl = "https://example.com"),
                        Span(" now")
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `bare url trailing punctuation is trimmed but not swallowed`() {
        // The ")." is prose: trimmed off the URL, yet still rendered as text —
        // the a11y contract says no character may disappear.
        val blocks = parseMarkdownLite("see (https://example.com).")
        val spans = (blocks[0] as Block.Paragraph).spans
        assertEquals(
            listOf(
                Span("see ("),
                Span("https://example.com", linkUrl = "https://example.com"),
                Span(").")
            ),
            spans
        )
    }

    @Test
    fun `url glued to a word is not linkified`() {
        val blocks = parseMarkdownLite("nopehttps://example.com")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("nopehttps://example.com")))),
            blocks
        )
    }

    @Test
    fun `inline parsing preserves all visible text`() {
        // Syntax markers are consumed by design (that is the point of rich
        // rendering); every VISIBLE character — labels, URLs, prose — must
        // survive so the a11y tree keeps finding them.
        val text = "Mix **b**, *i*, `c`, [l](https://x.com) and https://y.org!"
        val spans = (parseMarkdownLite(text)[0] as Block.Paragraph).spans
        assertEquals("Mix b, i, c, l and https://y.org!", spans.joinToString("") { it.text })
        // The consumed URL text lives on as the link's target.
        assertEquals(
            listOf("https://x.com", "https://y.org"),
            spans.mapNotNull { it.linkUrl }
        )
    }

    // ── Block-level: lists, quotes, code fences ────────────────────────────

    @Test
    fun `bullet list with multiple items`() {
        val blocks = parseMarkdownLite("- one\n- two\n- three")
        assertEquals(
            listOf(
                Block.Bullet(
                    listOf(
                        listOf(Span("one")),
                        listOf(Span("two")),
                        listOf(Span("three"))
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `mixed bullet markers group into one list`() {
        val blocks = parseMarkdownLite("- a\n* b\n• c\n– d")
        assertEquals(
            listOf(
                Block.Bullet(
                    listOf(
                        listOf(Span("a")),
                        listOf(Span("b")),
                        listOf(Span("c")),
                        listOf(Span("d"))
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `lone dash without content degrades to paragraph`() {
        // "- a\n-" — the lone marker needs whitespace+content to be a bullet;
        // without it, it renders as plain text instead of an empty item.
        val blocks = parseMarkdownLite("- a\n-")
        assertEquals(2, blocks.size)
        assertEquals(Block.Bullet(listOf(listOf(Span("a")))), blocks[0])
        assertEquals(Block.Paragraph(listOf(Span("-"))), blocks[1])
    }

    @Test
    fun `numbered list multi item`() {
        val blocks = parseMarkdownLite("1. one\n2. two\n10. ten")
        assertEquals(
            listOf(
                Block.Numbered(
                    listOf(
                        listOf(Span("one")),
                        listOf(Span("two")),
                        listOf(Span("ten"))
                    )
                )
            ),
            blocks
        )
    }

    @Test
    fun `bullet and numbered runs are separate blocks`() {
        val blocks = parseMarkdownLite("- a\n1. b\n- c")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is Block.Bullet)
        assertTrue(blocks[1] is Block.Numbered)
        assertTrue(blocks[2] is Block.Bullet)
    }

    @Test
    fun `quote line parses`() {
        val blocks = parseMarkdownLite("> quoted text")
        assertEquals(listOf(Block.Quote(listOf(Span("quoted text")))), blocks)
    }

    @Test
    fun `consecutive quote lines merge into one quote`() {
        val blocks = parseMarkdownLite("> a\n> b")
        assertEquals(
            listOf(Block.Quote(listOf(Span("a\nb")))),
            blocks
        )
    }

    @Test
    fun `code fence with language`() {
        val blocks = parseMarkdownLite("```kotlin\nval x = 1\n```")
        assertEquals(
            listOf(Block.CodeBlock(language = "kotlin", content = "val x = 1")),
            blocks
        )
    }

    @Test
    fun `code fence without language`() {
        val blocks = parseMarkdownLite("```\nplain\nlines\n```")
        assertEquals(
            listOf(Block.CodeBlock(language = "", content = "plain\nlines")),
            blocks
        )
    }

    @Test
    fun `unterminated code fence consumes the rest of the text`() {
        val blocks = parseMarkdownLite("```js\nfoo\nbar")
        assertEquals(
            listOf(Block.CodeBlock(language = "js", content = "foo\nbar")),
            blocks
        )
    }

    @Test
    fun `fenced code content is never inline parsed`() {
        val blocks = parseMarkdownLite("```\n**bold** and https://a.com\n```")
        assertEquals(
            listOf(
                Block.CodeBlock(
                    language = "",
                    content = "**bold** and https://a.com"
                )
            ),
            blocks
        )
    }

    @Test
    fun `full document with every block kind`() {
        val text = """
            # Research

            Intro with **bold**.

            - point one
            - point two

            ```python
            print("hi")
            ```

            > a quote

            1. first
            2. second
        """.trimIndent()
        val blocks = parseMarkdownLite(text)
        assertEquals(
            listOf(
                Block.Heading(1, listOf(Span("Research"))),
                Block.Paragraph(listOf(Span("Intro with "), Span("bold", bold = true), Span("."))),
                Block.Bullet(listOf(listOf(Span("point one")), listOf(Span("point two")))),
                Block.CodeBlock("python", "print(\"hi\")"),
                Block.Quote(listOf(Span("a quote"))),
                Block.Numbered(listOf(listOf(Span("first")), listOf(Span("second"))))
            ),
            blocks
        )
    }

    // ── extractUrls ─────────────────────────────────────────────────────────

    @Test
    fun `extractUrls dedupes by exact string and keeps first-appearance order`() {
        val text = "See https://b.org first, then https://a.com, and https://b.org again"
        assertEquals(
            listOf("https://b.org", "https://a.com"),
            extractUrls(text)
        )
    }

    @Test
    fun `extractUrls finds labeled links and bare urls`() {
        val text = "Read [the docs](https://docs.example.com) or visit https://www.example.com."
        assertEquals(
            listOf("https://docs.example.com", "https://www.example.com"),
            extractUrls(text)
        )
    }

    @Test
    fun `extractUrls skips fenced code blocks`() {
        val text = "source https://real.com\n```\nhttps://in-code.com\n```"
        assertEquals(
            listOf("https://real.com"),
            extractUrls(text)
        )
    }

    @Test
    fun `quote followed by paragraph keeps source order`() {
        val blocks = parseMarkdownLite("> quoted\nafter")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Quote)
        assertTrue(blocks[1] is Block.Paragraph)
    }

    @Test
    fun `extractUrls collects urls from every block kind except code`() {
        val text = """
            ## Heading with https://h.com
            - bullet https://b.com
            1. numbered [l](https://n.com)
            > quote https://q.com
            paragraph https://p.com
        """.trimIndent()
        assertEquals(
            listOf("https://h.com", "https://b.com", "https://n.com", "https://q.com", "https://p.com"),
            extractUrls(text)
        )
    }

    @Test
    fun `extractUrls caps at eight by default and max is configurable`() {
        val urls = (1..10).joinToString(" ") { "https://example.com/$it" }
        assertEquals(8, extractUrls(urls).size)
        assertEquals(
            "https://example.com/8",
            extractUrls(urls).last()
        )
        assertEquals(10, extractUrls(urls, maxUrls = 10).size)
        assertEquals(0, extractUrls(urls, maxUrls = 0).size)
    }

    @Test
    fun `extractUrls returns empty for url-free text`() {
        assertTrue(extractUrls("no links here, just words").isEmpty())
        assertTrue(extractUrls("").isEmpty())
    }
    // ── v1.3.0: scheme-less citation domains (E2E cap22 evidence) ────────

    @Test
    fun `bare domain citation becomes a link with https target`() {
        val spans = parseInlineForTest("Source: CoinDesk (coindesk.com/price/bitcoin).")
        val link = spans.firstOrNull { it.linkUrl != null }
        assertNotNull("bare domain citation was not linkified", link)
        assertEquals("coindesk.com/price/bitcoin", link!!.text)
        assertEquals("https://coindesk.com/price/bitcoin", link.linkUrl)
    }

    @Test
    fun `bare domain without path still linkifies`() {
        val spans = parseInlineForTest("Read more at reuters.com today")
        val link = spans.firstOrNull { it.linkUrl != null }
        assertNotNull(link)
        assertEquals("reuters.com", link!!.text)
        assertEquals("https://reuters.com", link.linkUrl)
    }

    @Test
    fun `e g and i e are not domains`() {
        val spans = parseInlineForTest("Common abbreviations, e.g. this one, i.e. this too, are prose.")
        assertTrue(spans.none { it.linkUrl != null })
    }

    @Test
    fun `version numbers and dates are not domains`() {
        val spans = parseInlineForTest("Version 1.2 shipped Sept 30, 2026 with v2.0 fixes.")
        assertTrue(spans.none { it.linkUrl != null })
    }

    @Test
    fun `mid-word dots are not domain starts`() {
        val spans = parseInlineForTest("The foo.bar pattern is prose, not a citation.")
        assertTrue(spans.none { it.linkUrl != null })
    }

    @Test
    fun `extractUrls collects bare domains with the https prefix`() {
        val urls = extractUrls(
            "Bitcoin is around $84,100. Source: CoinDesk (coindesk.com/price/bitcoin) " +
                "and https://coingecko.com/en/coins/bitcoin."
        )
        assertTrue(urls.contains("https://coindesk.com/price/bitcoin"))
        assertTrue(urls.contains("https://coingecko.com/en/coins/bitcoin"))
    }

    @Test
    fun `trailing period after bare domain stays literal`() {
        val spans = parseInlineForTest("See example.org.")
        val link = spans.firstOrNull { it.linkUrl != null }
        assertNotNull(link)
        assertEquals("example.org", link!!.text)
        // The final period is NOT part of the link.
        assertEquals(".", spans.lastOrNull { it.linkUrl == null }?.text?.takeLast(1))
    }

    /** v1.3.0 test seam: inline spans of the first parsed block. */
    private fun parseInlineForTest(text: String): List<Span> {
        val blocks = parseMarkdownLite(text)
        return blocks.filterIsInstance<Block.Paragraph>().firstOrNull()?.spans
            ?: blocks.firstNotNullOfOrNull { b ->
                when (b) {
                    is Block.Heading -> b.spans
                    is Block.Quote -> b.spans
                    else -> null
                }
            }
            ?: emptyList()
    }

}
