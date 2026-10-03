package com.tsfdroid.ai.core.agent

/**
 * v1.3.1 — the xauusd lesson (the 2026-10-02 field report).
 *
 * The user's screenshot: "web fetch the price of xauusd" → the planner wrote
 * FETCH_URL with a PHRASE as its url ("the price of xauusd" or "xauusd") →
 * the app tried to fetch https://the price of xauusd → all three fetch
 * strategies failed → the step's fallback WEB_SEARCH was dispatched with the
 * SAME params, which carried `url` but not `query` → schema validation
 * demanded the missing `query` → NeedsInput dead end asking the user for a
 * query they had already given. Three defects, one turn:
 *
 *  1. FETCH_URL accepts non-URL text as its url (the action tolerates
 *     `query` as a url alias — so a phrase slides straight in).
 *  2. The fallback dispatch reuses the primary's params verbatim, so a
 *     WEB_SEARCH fallback inherits `url` but never `query`.
 *  3. Schema validation happens before the handler, so the handler's own
 *     url/topic tolerance for `query` never gets a chance to run.
 *
 * This object holds the deterministic repairs that make that failure class
 * impossible. Pure logic, no Android imports — the same standalone-JVM test
 * coverage the cron evaluator and the search-quality rules get.
 */
object StepRepair {

    /** Param slots that commonly smuggle a search phrase for WEB_SEARCH. */
    val SEARCH_QUERY_ALIASES = setOf("url", "topic", "q", "search", "keyword", "term")

    /**
     * True when [s] plausibly names a web address: a scheme + host, or a
     * dot-separated host with a plausible final label (domain.tld). Phrases
     * ("the price of xauusd") and bare words ("xauusd") are NOT url-shaped.
     */
    fun isUrlShaped(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        val host = Regex("^https?://", RegexOption.IGNORE_CASE)
            .replace(t, "")
            .substringBefore('/')
            .substringBefore(':')
            .substringBefore('?')
        if (host.isEmpty()) return false
        if (host.equals("localhost", ignoreCase = true)) return true
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isBlank() }) return false
        val tld = labels.last()
        // A plausible TLD-ish label: 2-24 alnum chars (covers punycode's
        // leading "xn--"). Anything with symbols/punctuation is not a host.
        return tld.length in 2..24 &&
            tld.all { it.isLetterOrDigit() || it == '-' } &&
            !tld.startsWith("-") && !tld.endsWith("-")
    }

    /**
     * FETCH_URL / SUMMARIZE_URL steps whose url is not url-shaped are searches
     * in disguise — the user said "web fetch the price of xauusd" and the
     * planner pasted the phrase into the url slot. Rewrites the step into a
     * WEB_SEARCH whose query is the phrase itself (minus request framing) or,
     * when the slot was empty, the goal-derived substance phrase.
     *
     * @return the repaired (action, params), or null when no repair applies.
     */
    fun fetchToSearch(
        action: String,
        params: Map<String, String>,
        goal: String
    ): Pair<String, Map<String, String>>? {
        if (action.uppercase() !in setOf("FETCH_URL", "SUMMARIZE_URL")) return null
        val urlCandidate = params["url"]?.trim().orEmpty()
            .ifEmpty { params["query"]?.trim().orEmpty() }
        if (urlCandidate.isNotEmpty() && isUrlShaped(urlCandidate)) return null

        // Derive the query: the phrase when it carries substance, else the goal.
        val derived = urlCandidate
            .takeIf { it.isNotBlank() && !SearchQueryQuality.isDegenerate(it) }
            ?.let { SearchQueryQuality.fromGoal(it) }
            .takeIf { !it.isNullOrBlank() }
            ?: SearchQueryQuality.fromGoal(goal)
        if (derived.isBlank()) return null

        // Preserve any other params the planner set (they are inert for
        // WEB_SEARCH but harmless), minus the phrase-in-url confusion.
        val rest = params.filterKeys { it.lowercase() !in setOf("url", "query") }
        return "WEB_SEARCH" to (rest + mapOf("query" to derived))
    }

    /**
     * v1.4.0 (run-37111962938, the second xauusd field lesson): a FETCH on a
     * JS-rendered finance quote page against a live-data goal. Google Finance,
     * TradingView and Investing.com paint their prices with scripts — the
     * fetched static HTML carries no digits, so the turn ends with the honest
     * "the numeric quote wasn't included in the retrieved content" while the
     * user asked for a number. Rewrite the step to CHECK_STOCK, whose Yahoo
     * JSON endpoints return real digits (with its own search fallback).
     *
     * Disjoint from [fetchToSearch]: that repairs PHRASE-in-url (not
     * url-shaped); this repairs REAL quote-page urls.
     *
     * @return the repaired (action, params), or null when no repair applies.
     */
    fun fetchToQuote(
        action: String,
        params: Map<String, String>,
        goal: String
    ): Pair<String, Map<String, String>>? {
        if (action.uppercase() !in setOf("FETCH_URL", "SUMMARIZE_URL")) return null
        val url = params["url"]?.trim().orEmpty()
        if (url.isEmpty() || !isUrlShaped(url)) return null
        val host = url
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .substringBefore('/')
            .lowercase()
        val isQuotePage =
            (host.endsWith("google.com") && url.contains("/finance", ignoreCase = true)) ||
                host.contains("tradingview.com") ||
                host.contains("investing.com")
        if (!isQuotePage) return null
        if (!PlanResponseSanitizer.goalWantsWebData(goal)) return null

        // The symbol: ONLY the URL's tail (XAU-USD, BTC-USD, AAPL). A
        // goal-derived guess ("price of gold" → GOLD = Barrick Gold Corp,
        // a REAL but WRONG ticker) mis-quotes; no tail means no repair —
        // the fetch runs as planned with its honest outcome.
        val tail = url.trimEnd('/').substringAfterLast('/')
            .substringBefore('?').substringBefore('#')
            .replace("-", "").replace("_", "")
        val symbol = tail
            .takeIf { it.length in 3..10 && Regex("^[A-Za-z^=.]{1,10}$").matches(it) }
            ?.takeIf { it.lowercase() !in NON_TICKER_TAIL_WORDS }
            ?: return null
        return "CHECK_STOCK" to mapOf("symbol" to symbol.uppercase())
    }

    /** URL path segments that are NOT tickers (google.com/finance/quote/...). */
    private val NON_TICKER_TAIL_WORDS = setOf(
        "finance", "quote", "quotes", "symbol", "symbols", "currencies",
        "markets", "chart", "charts", "equities", "indices", "commodities",
        "forex", "crypto", "news", "search", "watchlist", "portfolio"
    )

    /**
     * WEB_SEARCH with a missing or blank `query` — including the fallback
     * dispatch case where the query is hiding in an alias slot (`url`,
     * `topic`, ...) or nowhere at all. Returns repaired params with `query`
     * filled from the alias or the goal-derived phrase, or null when nothing
     * to do.
     */
    fun repairSearchQuery(
        params: Map<String, String>,
        goal: String
    ): Map<String, String>? {
        if (params["query"]?.trim().orEmpty().isNotEmpty()) return null
        val alias = SEARCH_QUERY_ALIASES
            .firstOrNull { a -> params[a]?.trim().orEmpty().isNotEmpty() }
            ?.let { params[it]!!.trim() }
        val derived = alias
            ?.takeIf { !SearchQueryQuality.isDegenerate(it) }
            ?: SearchQueryQuality.fromGoal(goal)
        if (derived.isBlank()) return null
        return params + mapOf("query" to derived)
    }

    /**
     * v1.3.1 round 4 (the third gold lesson): the free-tier planner sometimes
     * writes a SINGLE defeatist ASK_USER step — "I'm not able to pull live
     * market data in this session" — for a plain data goal, parking the turn
     * on a question the user cannot usefully answer (run 36987915019: zero
     * searches executed, the test watched a 54-char apology). A one-step
     * ask whose question declares inability is rewritten into a real search
     * derived from the goal. Legitimate asks (cap21's "which city do you
     * prefer?") never match the defeatism vocabulary and multi-step plans
     * are left alone — asking mid-plan is normal agentic behavior.
     */
    private val DEFEATISM_MARKERS = listOf(
        "not able to", "can't", "cannot", "unable to", "no access",
        "don't have access", "do not have access", "cannot pull", "can't pull",
        "isn't available", "not available to me", "no internet access",
        "cannot access the internet", "don't have the ability"
    )

    fun defeatistAskToSearch(
        action: String,
        params: Map<String, String>,
        goal: String,
        totalSteps: Int
    ): Pair<String, Map<String, String>>? {
        if (action.uppercase() != "ASK_USER" || totalSteps != 1) return null
        val question = params["question"]?.lowercase() ?: return null
        if (DEFEATISM_MARKERS.none { question.contains(it) }) return null
        val derived = SearchQueryQuality.fromGoal(goal)
        if (derived.isBlank() || SearchQueryQuality.isDegenerate(derived)) return null
        return "WEB_SEARCH" to mapOf("query" to derived)
    }
}
