package com.tsfdroid.ai.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the v1.0.5 in-app web capability parsers: WEB_SEARCH (DuckDuckGo
 * Lite), GET_NEWS (RSS headlines), FETCH_URL (HTML-to-text) and entity decoding.
 * Fixtures mirror the real markup shapes the actions fetch at runtime.
 */
class WebContentParsersTest {

    // --- parseDuckDuckGoLite ---

    @Test
    fun `ddg lite result rows are parsed with title url and snippet`() {
        val html = """
            <table>
              <tr><td><a rel="nofollow" class="result-link" href="https://example.com/gold">Gold price today</a></td></tr>
              <tr><td class="result-snippet">Spot gold climbed 2% after the latest <b>federal</b> news.</td></tr>
              <tr><td><a rel="nofollow" class="result-link" href="https://example.com/news">Market news</a></td></tr>
              <tr><td class="result-snippet">Indices rallied on fresh data.</td></tr>
            </table>
        """.trimIndent()
        val results = WebContentParsers.parseDuckDuckGoLite(html)
        assertEquals(2, results.size)
        assertEquals("Gold price today", results[0].title)
        assertEquals("https://example.com/gold", results[0].url)
        assertTrue(results[0].snippet.contains("climbed 2%"))
        assertEquals("Market news", results[1].title)
    }

    @Test
    fun `ddg lite html entities in titles and urls are decoded`() {
        val html = """
            <a class="result-link" href="https://example.com/a?x=1&amp;y=2">AT&amp;T quarterly &#39;report&#39;</a>
            <td class="result-snippet">Earnings &gt; expectations</td>
        """.trimIndent()
        val results = WebContentParsers.parseDuckDuckGoLite(html)
        assertEquals(1, results.size)
        assertEquals("https://example.com/a?x=1&y=2", results[0].url)
        assertEquals("AT&T quarterly 'report'", results[0].title)
        assertTrue(results[0].snippet.contains("> expectations"))
    }

    @Test
    fun `ddg lite results are capped at five`() {
        val html = (1..8).joinToString("") { i ->
            """<a class="result-link" href="https://e.com/$i">Result $i</a>"""
        }
        assertEquals(5, WebContentParsers.parseDuckDuckGoLite(html).size)
    }

    @Test
    fun `blank or unparseable ddg html yields empty list for browser fallback`() {
        assertEquals(0, WebContentParsers.parseDuckDuckGoLite("").size)
        assertTrue(WebContentParsers.parseDuckDuckGoLite("<html><body>captcha page</body></html>").isEmpty())
    }

    // --- parseRssHeadlines ---

    @Test
    fun `rss items yield decoded headlines in order`() {
        val xml = """
            <rss><channel>
              <item><title>Gold hits record &amp;amp; high</title><pubDate>Wed</pubDate></item>
              <item><title>Markets rally &#39;today&#39;</title></item>
              <item><title>Third headline</title></item>
            </channel></rss>
        """.trimIndent()
        val headlines = WebContentParsers.parseRssHeadlines(xml, limit = 3)
        // "&amp;amp;" decodes once here to "&amp;"; the second round happens in
        // real payloads at fetch time — the parser performs bounded rounds.
        assertEquals(3, headlines.size)
        assertEquals("Markets rally 'today'", headlines[1])
        assertEquals("Third headline", headlines[2])
        assertTrue(headlines[0].contains("high"))
    }

    @Test
    fun `rss limit is respected`() {
        val xml = (1..10).joinToString("") { i -> "<item><title>H$i</title></item>" }
        assertEquals(5, WebContentParsers.parseRssHeadlines(xml).size)
    }

    @Test
    fun `malformed rss yields empty list`() {
        assertTrue(WebContentParsers.parseRssHeadlines("<rss><channel>no items").isEmpty())
        assertTrue(WebContentParsers.parseRssHeadlines("").isEmpty())
    }

    // --- htmlToText ---

    @Test
    fun `script and style content is dropped from fetched pages`() {
        val html = """
            <html><head><style>body{color:red}</style><script>var x='ignore';</script></head>
            <body><h1>Heading</h1><p>Real   content here</p><div>Second line</div></body></html>
        """.trimIndent()
        val text = WebContentParsers.htmlToText(html)
        assertTrue(text.contains("Heading"))
        assertTrue(text.contains("Real content here"))
        assertTrue(text.contains("Second line"))
        assertTrue(!text.contains("color:red"))
        assertTrue(!text.contains("var x"))
    }

    @Test
    fun `long pages are bounded to the max chars budget`() {
        val html = "<p>" + "word ".repeat(5_000) + "</p>"
        val text = WebContentParsers.htmlToText(html, maxChars = 8_000)
        assertTrue(text.length <= 8_100) // budget + ellipsis marker
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `block tags become newlines so the text stays readable`() {
        val html = "<p>line one</p><p>line two</p><br><span>inline</span>"
        val text = WebContentParsers.htmlToText(html)
        assertTrue(text.contains("line one"))
        assertTrue(text.contains("line two"))
        assertTrue(text.contains("inline"))
    }

    // --- decodeEntities ---

    @Test
    fun `numeric entities decode within the safe range`() {
        assertEquals("→", WebContentParsers.decodeEntities("&#8594;"))
        assertEquals("→", WebContentParsers.decodeEntities("&#x2192;"))
        assertEquals("A", WebContentParsers.decodeEntities("&#65;"))
    }

    @Test
    fun `invalid numeric entities pass through untouched`() {
        assertEquals("&#999999;", WebContentParsers.decodeEntities("&#999999;"))
        assertEquals("&#xZZ;", WebContentParsers.decodeEntities("&#xZZ;"))
    }

    @Test
    fun `text without entities is returned as-is`() {
        assertEquals("plain text", WebContentParsers.decodeEntities("plain text"))
    }

    // --- v1.0.6 multi-backend search parsers ---

    @Test
    fun `bing result blocks yield title url and snippet`() {
        val html = """
            <html><body><ol>
            <li class="b_algo"><h2><a href="https://goldprice.org">Gold Price Today</a></h2>
            <div><p>Live gold price is 4,284 USD per ounce</p></div></li>
            <li class="b_algo"><h2><a class="anything" href="https://kitco.com/charts/gold">Kitco Gold Chart</a></h2>
            <div><p>Gold charts and quotes</p></div></li>
            </ol></body></html>
        """.trimIndent()
        val results = WebContentParsers.parseBingResults(html)
        assertEquals(2, results.size)
        assertEquals("Gold Price Today", results[0].title)
        assertEquals("https://goldprice.org", results[0].url)
        assertTrue(results[0].snippet.contains("4,284"))
        assertEquals("https://kitco.com/charts/gold", results[1].url)
    }

    @Test
    fun `bing challenge or empty pages yield no results`() {
        assertTrue(WebContentParsers.parseBingResults("<html>No b_algo here</html>").isEmpty())
        assertTrue(WebContentParsers.parseBingResults("").isEmpty())
    }

    @Test
    fun `ddg html result anchors are parsed`() {
        val html = """
            <html><body>
            <a rel="nofollow" class="result__a" href="https://example.com/one">First Result</a>
            <a class="result__snippet" href="#">The first snippet text</a>
            <a rel="nofollow" class="result__a" href="https://example.com/two">Second Result</a>
            <a class="result__snippet" href="#">Second snippet</a>
            </body></html>
        """.trimIndent()
        val results = WebContentParsers.parseDuckDuckGoHtml(html)
        assertEquals(2, results.size)
        assertEquals("First Result", results[0].title)
        assertEquals("https://example.com/one", results[0].url)
        assertEquals("The first snippet text", results[0].snippet)
    }

    @Test
    fun `uddg redirect wrappers unwrap to the destination`() {
        val wrapped = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftarget&amp;rut=abc"
        assertEquals("https://example.com/target", WebContentParsers.unwrapRedirectUrl(wrapped))
    }

    @Test
    fun `plain urls pass through unwrap untouched`() {
        assertEquals("https://example.com/a?b=1", WebContentParsers.unwrapRedirectUrl("https://example.com/a?b=1"))
    }
}
