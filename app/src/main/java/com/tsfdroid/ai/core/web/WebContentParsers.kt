package com.tsfdroid.ai.core.web

/**
 * Pure HTML/XML shaping helpers behind the v1.0.5 in-app web capability:
 * WEB_SEARCH, GET_NEWS and FETCH_URL fetch real data over HTTP without
 * opening a browser, then these parsers reduce the raw payloads to agent-
 * consumable text. Extracted as a pure object so every parser is unit
 * testable without Android or network.
 */
object WebContentParsers {

    /** One web-search hit: page title, its URL, and the result snippet. */
    data class WebSearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    /**
     * Parses DuckDuckGo Lite result rows (the JS-free HTML endpoint the app
     * fetches for in-app search). Result links carry class "result-link",
     * snippets sit in the "result-snippet" cell of the same row. Malformed or
     * empty HTML yields an empty list — callers move to the next backend.
     */
    fun parseDuckDuckGoLite(html: String): List<WebSearchResult> {
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<WebSearchResult>()
        val linkRegex = Regex(
            pattern = "<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            pattern = "class=\"result-snippet\"[^>]*>(.*?)</td>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        for ((index, match) in linkRegex.findAll(html).withIndex()) {
            val rawUrl = match.groupValues[1].trim()
            val title = stripTags(match.groupValues[2])
            if (title.isBlank()) continue
            results.add(
                WebSearchResult(
                    title = title,
                    url = decodeEntities(rawUrl),
                    snippet = snippets.getOrElse(index) { "" }
                )
            )
            if (results.size >= MAX_SEARCH_RESULTS) break
        }
        return results
    }

    /**
     * v1.0.6: parses the DuckDuckGo HTML (full) endpoint — the second search
     * backend. DDG Lite now serves a 202 bot-challenge to many clients (the
     * v1.0.5 field failure: zero results parsed → browser fallback), so the
     * search chain needs a second DDG shape before non-DDG backends.
     */
    fun parseDuckDuckGoHtml(html: String): List<WebSearchResult> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<WebSearchResult>()
        val linkRegex = Regex(
            pattern = "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            pattern = "class=\"result__snippet\"[^>]*>(.*?)</a>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        for ((index, match) in linkRegex.findAll(html).withIndex()) {
            val title = stripTags(match.groupValues[2])
            if (title.isBlank()) continue
            results.add(
                WebSearchResult(
                    title = title,
                    url = unwrapRedirectUrl(match.groupValues[1].trim()),
                    snippet = snippets.getOrElse(index) { "" }
                )
            )
            if (results.size >= MAX_SEARCH_RESULTS) break
        }
        return results
    }

    /**
     * Parses Bing result blocks (li.b_algo → h2 > a) — the v1.0.6 primary
     * backend after DDG Lite's 202 bot-challenge: Bing answered our probe
     * with real result blocks where DDG served a challenge page.
     */
    fun parseBingResults(html: String): List<WebSearchResult> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<WebSearchResult>()
        val blockRegex = Regex(
            pattern = "<li[^>]*class=\\\"b_algo\\\"[^>]*>(.*?)</li>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val linkRegex = Regex(
            pattern = "<a[^>]*href=\\\"(https?://[^\\\"]+)\\\"[^>]*>(.*?)</a>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (block in blockRegex.findAll(html).take(MAX_SEARCH_RESULTS)) {
            val body = block.groupValues[1]
            val link = linkRegex.find(body) ?: continue
            val title = stripTags(link.groupValues[2])
            if (title.isBlank()) continue
            results.add(
                WebSearchResult(
                    title = title,
                    url = unwrapRedirectUrl(link.groupValues[1].trim()),
                    snippet = stripTags(body.replace(linkRegex, " "))
                )
            )
        }
        return results
    }

    /**
     * Unwraps search-engine redirect wrappers (duckduckgo.com/l/?uddg=...,
     * bing.com/ck/a?...u=a1<base64>) to the real destination URL; anything
     * unrecognized passes through untouched.
     */
    fun unwrapRedirectUrl(url: String): String {
        val decoded = decodeEntities(url)
        val uddg = Regex("[?&]uddg=([^&]+)").find(decoded)?.groupValues?.get(1)
        if (uddg != null) {
            return runCatching { java.net.URLDecoder.decode(uddg, "UTF-8") }.getOrElse { uddg }
        }
        // Bing /ck/a wraps the destination in a u=a1<a1-base64-url> param.
        if (decoded.contains("/ck/a")) {
            val u = Regex("[?&]u=a1([^&]+)").find(decoded)?.groupValues?.get(1)
            if (u != null) {
                val b64 = u.replace('-', '+').replace('_', '/')
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                val decodedUrl = runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
                }.getOrNull()
                if (decodedUrl != null && decodedUrl.startsWith("http")) return decodedUrl
            }
        }
        return decoded
    }

    /**
     * Parses RSS/RDF headlines (Google News RSS: item/title). Entity-
     * decoded, bounded to [limit]; malformed XML yields an empty list.
     */
    fun parseRssHeadlines(xml: String, limit: Int = 5): List<String> {
        if (xml.isBlank()) return emptyList()
        val titles = mutableListOf<String>()
        val itemRegex = Regex(
            pattern = "<item>(.*?)</item>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val titleRegex = Regex(
            pattern = "<title>(.*?)</title>",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (item in itemRegex.findAll(xml)) {
            val title = titleRegex.find(item.groupValues[1])?.groupValues?.get(1) ?: continue
            val cleaned = decodeEntities(title).trim()
            if (cleaned.isNotBlank()) titles.add(cleaned)
            if (titles.size >= limit) break
        }
        return titles
    }

    /**
     * Reduces an HTML page to readable text for FETCH_URL: script/style
     * content dropped, block tags become newlines, remaining tags stripped,
     * entities decoded, whitespace collapsed. Bounded to [maxChars] so a
     * huge page cannot flood the model context or the step-result store.
     */
    fun htmlToText(html: String, maxChars: Int = 8_000): String {
        if (html.isBlank()) return ""
        var text = html
            .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        text = decodeEntities(text)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    /**
     * Decodes the HTML entities a fetched page commonly carries: the named
     * set used by search/news markup plus decimal and hex numeric refs.
     * Unknown entities pass through untouched (never throws).
     */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var out = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&middot;", "·")
        // Numeric refs — bounded rounds so a crafted page cannot loop the decoder.
        var rounds = 0
        while (rounds < 2) {
            val before = out
            out = Regex("&#(\\d+);").replace(out) { match ->
                val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                validCodePoint(code)?.toString() ?: match.value
            }
            out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
                val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
                validCodePoint(code)?.toString() ?: match.value
            }
            if (out == before) break
            rounds++
        }
        return out
    }

    private fun validCodePoint(code: Int): Char? {
        if (code <= 0 || code > 0xFFFF) return null
        return code.toChar()
    }

    private fun stripTags(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val MAX_SEARCH_RESULTS = 5
}
