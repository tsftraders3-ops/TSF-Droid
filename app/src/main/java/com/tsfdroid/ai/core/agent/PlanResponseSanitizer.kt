package com.tsfdroid.ai.core.agent

/**
 * Pure text-shaping helpers for LLM plan answers, extracted from
 * [AgentLoop] for direct unit testing (v1.0.4).
 *
 * Reasoning models on the OpenCode Zen free tier (mimo, nemotron, …)
 * frequently wrap the plan in thinking output or narrate around the JSON;
 * these helpers reduce such answers to the parseable core and classify the
 * prose that never becomes JSON.
 */
internal object PlanResponseSanitizer {

    private val CLOSED_THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val CLOSED_REASONING = Regex("<reasoning>.*?</reasoning>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Removes reasoning-model thinking blocks from a raw completion:
     * closed `<think>…</think>` spans anywhere in the text, and an
     * unterminated `<think>` (still streaming, or the model forgot to close
     * it) — everything from that tag on is thinking, so it is dropped.
     */
    fun stripReasoningBlocks(raw: String): String {
        var content = raw
            .replace(CLOSED_THINK, "")
            .replace(CLOSED_REASONING, "")
        val unterminated = content.indexOf("<think>")
        if (unterminated >= 0 && content.indexOf("</think>", unterminated) < 0) {
            content = content.substring(0, unterminated)
        }
        return content.trim()
    }

    /**
     * Returns the first balanced JSON object in [text] — brace-depth scan
     * that respects string literals and escapes — or null when none is
     * present. A plain whole-string parse chokes when the model narrates a
     * sentence before the plan; the scan recovers the object itself.
     */
    fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Classifies a prose (non-JSON) plan answer as an executable action.
     *
     * A genuinely ambiguous goal legitimately comes back as a clarifying
     * question; a chatty goal as a conversational answer. Both are valid
     * agent outcomes routed through the action protocol instead of failing
     * the turn with "Could not parse a valid plan". Returns null when the
     * text is JSON-shaped (caller should treat it as a real parse failure)
     * or blank.
     */
    fun classifyProseReply(raw: String): Pair<String, Map<String, String>>? {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("{") || text.startsWith("[")) return null
        // v1.0.5: 400 chars cut real answers mid-word (the capability-audit
        // failure: "…which ca" — the reply reached the dispatcher truncated).
        // Prose replies ARE the deliverable for conversational turns, so the
        // cap protects only against runaway output, never mangles a real
        // answer. 16k chars ≈ 4k tokens of intact prose.
        val collapsed = text.replace(Regex("\\s+"), " ").take(PROSE_MAX_CHARS)
        return if (collapsed.endsWith("?")) {
            "ASK_USER" to mapOf("question" to collapsed)
        } else {
            "CHAT" to mapOf("response" to collapsed)
        }
    }

    /** Upper bound for a classified prose reply (see [classifyProseReply]). */
    const val PROSE_MAX_CHARS = 16_000

    /** Short-commitment verbs that announce work instead of planning it. */
    private val DECLINE_VERBS = listOf(
        "i am creating", "i'm creating", "i will create", "i'll create",
        "i am going to create", "i will write", "i'll write", "i am writing",
        "let me create", "let me write", "let's build", "lets build",
        "i can create", "i will now", "creating the", "writing the",
        "i will generate", "i'll generate",
        // v1.0.6: data-goal commitments — the gold-price field failure
        // ("Let me check the current gold price for you." executed as an
        // empty CHAT step and the turn ended with nothing fetched).
        "let me check", "let me fetch", "let me search", "let me look",
        "let me get", "let me pull", "let me find", "let me grab",
        "i am checking", "i'm checking", "i will check", "i'll check",
        "i am fetching", "i'm fetching", "i will fetch", "i'll fetch",
        "i am searching", "i'm searching", "i will search", "i'll search",
        "i am going to check", "i am going to fetch", "i am going to search",
        "checking the", "fetching the", "searching the", "searching for",
        "i will look", "i'll look", "let me build", "let me put together",
        "let me do", "i am building", "i'm building", "let me run"
    )

    /** Words that mark an artifact-producing goal. */
    private val ARTIFACT_WORDS = listOf(
        "file", "html", "website", "web page", "pdf", "document",
        "report", "save", "write", "note", "csv", "json"
    )

    /**
     * Words that mark a data-producing goal (fetch/search/lookup class).
     * v1.0.6: a prose commitment against one of these goals defers a
     * WEB_SEARCH/FETCH_URL the same way prose deferrals used to defer a
     * WRITE_FILE — both must trigger the corrective re-ask.
     */
    private val DATA_WORDS = listOf(
        "price", "fetch", "search", "weather", "news", "stock",
        "exchange rate", "current", "latest", "today", "lookup", "look up",
        "find out", "gold", "rate", "score", "headline", "quote",
        "definition of", "translate", "conversion"
    )

    /**
     * True when a prose reply is a SHORT commitment to do an artifact or
     * data task later instead of a plan that does it now — "I am creating
     * the HTML file for you." against a "create an HTML website" goal, or
     * "Let me check the current gold price for you." against "fetch the
     * price of gold". These replies are the v1.0.5/v1.0.6 field failures:
     * they executed as CHAT steps and nothing was ever done. The caller uses
     * this to trigger the corrective re-ask. Long prose (over 600 chars) is
     * a substantive answer, never a deferral.
     */
    fun proseDeclinesAction(response: String?, userGoal: String): Boolean {
        val reply = response?.lowercase()?.trim() ?: return false
        if (reply.isEmpty() || reply.length > 600) return false
        val goal = userGoal.lowercase()
        if (ARTIFACT_WORDS.any { goal.contains(it) }) return true
        if (DATA_WORDS.any { goal.contains(it) } && DECLINE_VERBS.any { reply.contains(it) }) return true
        // Artifact deferrals keep the original shape: commitment verb AND an
        // artifact word inside the reply itself (goal words may be absent).
        return DECLINE_VERBS.any { reply.contains(it) } &&
            ARTIFACT_WORDS.any { reply.contains(it) }
    }

    /**
     * True when the goal itself asks for live internet data (search/fetch
     * class) — used by the planner fallback to synthesize an executable
     * WEB_SEARCH/FETCH_URL plan when the model keeps answering prose.
     */
    fun goalWantsWebData(goal: String): Boolean {
        val g = goal.lowercase()
        return DATA_WORDS.any { g.contains(it) }
    }

    /**
     * True when the goal asks for a file artifact (HTML/PDF/document...).
     */
    fun goalWantsArtifact(goal: String): Boolean {
        val g = goal.lowercase()
        return ARTIFACT_WORDS.any { g.contains(it) }
    }
}
