package com.tsfdroid.ai.core.agent

/**
 * v1.3.0 round 19 — the gold-query lesson. Run-123 evidence: the planner
 * once wrote `WEB_SEARCH {query: "current"}` for the goal "Fetch the current
 * gold price" — a lone adjective torn out of its phrase — and the honest turn
 * then delivered a fintech company's marketing page as its price data
 * (DuckDuckGo for the single word "current" returns current.com, dictionary
 * entries and electric-current articles — verified against the live backend).
 *
 * Pure logic, no Android imports, so the degenerate-detection and the
 * goal-derivation rules get the same standalone-JVM test coverage the cron
 * evaluator got.
 */
object SearchQueryQuality {

    /**
     * Words that are never a useful search query on their own: temporal
     * adjectives, interrogatives, pleasantries and the request verbs planners
     * mistakenly put INTO the query slot. A query of exactly one of these
     * words is degenerate.
     */
    private val DEGENERATE_QUERY_WORDS = setOf(
        "current", "latest", "today", "now", "recent", "new", "best", "top",
        "price", "prices", "value", "worth", "what", "when", "how", "who",
        "where", "which", "why", "search", "find", "look", "lookup", "fetch",
        "get", "me", "my", "us", "it", "this", "that", "the", "a", "an",
        "info", "information", "data", "detail", "details", "app", "android",
        "phone", "device", "web", "internet", "online"
    )

    fun isDegenerate(query: String): Boolean {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size != 1) return false
        return words.first() in DEGENERATE_QUERY_WORDS
    }

    /**
     * v1.3.1 round 3 (the second gold lesson): a datacenter egress IP can
     * poison ANY search backend — DDG serves its anomaly page (zero results,
     * fine, the chain just continues) but Bing still "answers" with an
     * off-market result set (current.com and a Chinese dictionary for
     * "current gold price today per gram"). A result set is only relevant
     * when at least one result carries one of the query's NON-GENERIC
     * tokens — degenerate words ("current", "latest", "today") are ignored
     * by design, so a domain that merely echoes the torn-out adjective
     * (current.com) never passes the gate.
     */
    fun resultsAreRelevant(query: String, resultTexts: List<String>): Boolean {
        val tokens = query.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in DEGENERATE_QUERY_WORDS }
        if (tokens.isEmpty()) return true // nothing decisive to gate on
        return resultTexts.any { text ->
            val t = text.lowercase()
            tokens.any { t.contains(it) }
        }
    }

    /**
     * Derives a search phrase from a goal by stripping the request framing
     * users (and planners) write around the substance:
     * "Fetch the current gold price" -> "current gold price"
     * "write a deep research report about solar energy growth in india as a pdf"
     *   -> "deep research report about solar energy growth in india"
     */
    fun fromGoal(goal: String): String {
        var q = goal.trim()

        // Leading request framing: "please", "can you", "cna u", then a
        // request verb, then optional articles — everything before the
        // substance of the ask.
        q = Regex(
            "^(please\\s+)?(kindly\\s+)?(can\\s+you\\s+|could\\s+you\\s+|cna\\s+u\\s+|cn\\s+u\\s+)?" +
                "(web\\s+fetch|webfetch|web\\s+search|websearch|web\\s+look\\s?up|google\\s+for|" +
                "fetch|get|find|search(\\s+for)?|look\\s?up|show\\s+me|tell\\s+me|give\\s+me|write|make|create|build|draft|compose|prepare|research|check|look|what('s|\\s+is|\\s+are)|how\\s+much\\s+is|how\\s+many\\s+is)\\s+" +
                "(the\\s+|me\\s+|a\\s+|an\\s+|up\\s+|out\\s+|for\\s+|about\\s+)*",
            RegexOption.IGNORE_CASE
        ).replace(q, "")

        // v1.3.1: a leading article with no verb in front of it ("the price
        // of xauusd" as a url-slot phrase) — a search query never needs its
        // leading article.
        q = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE).replace(q, "")

        // Trailing format ask-ons ("... as a pdf", "... in html").
        q = Regex(
            "\\s+(as|in)\\s+(a\\s+|an\\s+)?(pdf|html|txt|json|csv|md|markdown|file|document)\\s*$",
            RegexOption.IGNORE_CASE
        ).replace(q, "")

        // Trailing politeness/temporals that add nothing to a search.
        q = Regex("\\s+(now|please|today|right now)\\s*[.!]?$", RegexOption.IGNORE_CASE).replace(q, "")

        q = q.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?', ',')
        return q.ifBlank { goal.trim() }
    }
}
