package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus

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

    /**
     * Words that mark an artifact-producing goal.
     * v1.6.0 (field P1-8): "save it as" added - the ai_policy turn ("save it
     * as ai_policy_update.txt") deflected to a WEB_SEARCH-only plan because
     * the word list missed the phrase while the file ask was explicit.
     */
    private val ARTIFACT_WORDS = listOf(
        "file", "html", "website", "web page", "webpage", "pdf", "document",
        "report", "save", "write", "note", "csv", "json", "spreadsheet",
        "powerpoint", "slides", "save it as", "saved as", "name it", "called"
    )

    /**
     * Words naming a CONCRETE file format/type. A goal carrying one of
     * these AND an artifact-creation signal (see [ARTIFACT_CREATE_SIGNALS])
     * is an explicit file ask, so prose of any length defers it (see
     * [proseDeclinesAction]). Softer artifact words ("report",
     * "note", "save", "write") stay on the short-commitment rule only —
     * "make a report of your capabilities" legitimately answers in
     * conversational prose.
     */
    private val CONCRETE_ARTIFACT_WORDS = listOf(
        "file", "html", "website", "web page", "webpage", "pdf",
        "document", "csv", "json", "spreadsheet", "powerpoint", "slides"
    )

    /**
     * Creation verbs that turn a format word into an artifact ASK. Without
     * one, a goal naming a format is a KNOWLEDGE question ("explain what
     * json is", "how does html work") and must keep the conversational
     * path — the format word alone must never force a file write (critic
     * round-1 MUST-FIX: informational goals force-routed to the planner
     * were about to be converted into 8k-token document generations).
     */
    private val ARTIFACT_CREATE_SIGNALS = listOf(
        "create", "make", "build", "write", "generate", "design", "produce",
        "draft", "put together", "come up with", "export", "convert", "save",
        "compose", "prepare", "give me", "want a", "want the", "need a",
        "need the", "i want", "i need"
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
     * v1.3.0 round 21 (the 2026-10-03 field evidence, three screenshots):
     * phrases with which a reply REFUSES a data or artifact goal by claiming
     * missing tools or missing data — "I can't pull a live quote right now
     * (no tool access in this session)", "file-generation tools aren't
     * available in this session", "I don't have a live quote feed available
     * right now". The tools exist on this device; only the reply's author
     * (the planner, writing a CHAT step at plan time, or a tool-less call)
     * could not see them. Such a reply against a data/artifact goal is a
     * deferral at ANY length — the >600-char "substantive answer" exemption
     * must never rescue it.
     */
    private val REFUSAL_PHRASES = listOf(
        "no tool access", "tools aren't available", "tools are not available",
        "no tools available", "don't have tools", "do not have tools",
        "no tool access in this session", "no file-generation tools",
        "wasn't able to pull", "was not able to pull",
        "couldn't pull", "could not pull", "can't pull", "cannot pull",
        "unable to pull", "couldn't extract", "could not extract",
        "no live quote feed", "don't have a live quote",
        "do not have a live quote", "no figure to quote",
        "can't create the pdf", "cannot create the pdf",
        "can't generate the pdf", "cannot generate the pdf",
        "file-generation tools aren't available",
        "no file card will appear",
        "can't access the internet", "cannot access the internet",
        "no internet access in this session",
        "tools not available in this session",
        "not available in this session"
    )

    /**
     * True when [response] opens with a refusal-shaped claim (checked in the
     * first [REFUSAL_WINDOW_CHARS] characters — the field refusals all LEAD
     * with the disclaimer, while a grounded answer that mentions a partial
     failure mid-text is legitimate). See [REFUSAL_PHRASES].
     */
    fun replyRefusesGoal(response: String?): Boolean {
        val reply = response?.lowercase() ?: return false
        if (reply.isEmpty()) return false
        val window = reply.take(REFUSAL_WINDOW_CHARS)
        return REFUSAL_PHRASES.any { window.contains(it) }
    }

    /** Where [replyRefusesGoal] looks for refusal phrases (see its doc). */
    private const val REFUSAL_WINDOW_CHARS = 260

    /**
     * True when a prose reply is a SHORT commitment to do an artifact or
     * data task later instead of a plan that does it now — "I am creating
     * the HTML file for you." against a "create an HTML website" goal, or
     * "Let me check the current gold price for you." against "fetch the
     * price of gold". These replies are the v1.0.5/v1.0.6 field failures:
     * they executed as CHAT steps and nothing was ever done. The caller uses
     * this to trigger the corrective re-ask. For artifact goals, prose of ANY
     * length is a deferral (see the comment inside); for other goals, long
     * prose (over 600 chars) is a substantive answer, never a deferral.
     */
    fun proseDeclinesAction(response: String?, userGoal: String): Boolean {
        val reply = response?.lowercase()?.trim() ?: return false
        if (reply.isEmpty()) return false
        val goal = userGoal.lowercase()
        // Goals naming a CONCRETE file format ("website", "html", "pdf", ...):
        // prose NEVER satisfies them, at ANY length. The old 600-char guard
        // existed to protect "substantive answers", but when the user asked
        // for a file, 10k characters ABOUT the website is still a deferral —
        // nothing gets written (run-102 evidence: both vague-website passes
        // leaked exactly this way; the model returned a 10,062-char design
        // essay instead of the HTML file and the turn ended reply-only).
        // Clarifying questions never reach this branch either (they end in
        // "?" and classifyProseReply routes them to ASK_USER).
        if (CONCRETE_ARTIFACT_WORDS.any { goal.contains(it) } &&
            ARTIFACT_CREATE_SIGNALS.any { goal.contains(it) }) return true
        // An explicit lookup command ("search the web for X", "google X")
        // defers to NO prose answer, however confident and well-sourced the
        // model's memory of earlier turns is — the commanded search IS the
        // task (run-102 cap22 pass-2: "data I retrieved earlier in our
        // conversation", zero fresh search).
        if (goalDemandsFreshData(userGoal)) return true
        // v1.3.0 round 21: a refusal-shaped reply ("I can't pull a live
        // quote… no tool access in this session") against a data or
        // artifact goal is ALWAYS a deferral, at any length — the tools
        // exist; this reply's author just couldn't see them. Checked BEFORE
        // the length exemption so a 700-character "here's what I know from
        // memory" refusal cannot slip through as "substantive".
        if ((goalWantsWebData(userGoal) || goalWantsArtifact(userGoal)) &&
            replyRefusesGoal(response)
        ) return true
        if (reply.length > 600) return false
        // Softer artifact asks ("make a report of your capabilities") keep
        // the long-prose exemption — their answer is often legitimately
        // conversational (the v1.0.5 capability-audit evidence) — but a
        // SHORT commitment still defers the work.
        if (ARTIFACT_WORDS.any { goal.contains(it) }) return true
        if (DATA_WORDS.any { goal.contains(it) } && DECLINE_VERBS.any { reply.contains(it) }) return true
        // Artifact deferrals keep the original shape: commitment verb AND an
        // artifact word inside the reply itself (goal words may be absent).
        return DECLINE_VERBS.any { reply.contains(it) } &&
            ARTIFACT_WORDS.any { reply.contains(it) }
    }

    /**
     * True when the goal EXPLICITLY commands a live web lookup — "search
     * the web for X", "google X", "look up X", "fetch the page at X". The
     * user is not asking whether the model happens to know; the search is
     * the task. A prose answer to such a goal — however confident, however
     * well-sourced from the model's memory of earlier turns — dodges the
     * commanded action, so the planner fallback must synthesize the
     * WEB_SEARCH/FETCH_URL step instead (run-102 cap22 pass-2 evidence: the
     * model answered "data I retrieved earlier in our conversation" with
     * zero fresh search). Deliberately narrow: general data-goal words
     * ("current", "today") do NOT count — only an explicit lookup command
     * does, so "how are you today" can never be forced into a search.
     */
    fun goalDemandsFreshData(goal: String): Boolean {
        // Normalize every non-alphanumeric run to a single space so trailing
        // punctuation cannot defeat the phrase edges: "search the web, find
        // the price" and "please look it up!" must still match (critic
        // round-1: the plain space-pad missed both).
        val g = " ${goal.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()} "
        return FRESH_DATA_COMMANDS.any { g.contains(it) }
    }

    /** Explicit lookup command phrases (space-padded to match word edges). */
    private val FRESH_DATA_COMMANDS = listOf(
        " search the web ", " search online ", " search the internet ",
        " do a web search ", " do a quick search ", " run a search ",
        " google ", " look it up ", " look up the ", " look that up ",
        " fetch the ", " fetch and ", " browse the web ",
        " check the latest ", " check the current ", " find the current ",
        " find the latest ", " search for the ", " search for a ",
        " search for latest ", " search for current ", " search news "
    )

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

    /**
     * v1.3.0 round 24 (cap22 E2E evidence): CONCRETE artifact asks only — a
     * format word (pdf, html, file, website, csv...) that names a real
     * deliverable. The loose [goalWantsArtifact] matches the VERB "report"
     * ("search the price and REPORT the source URL", "fetch the page and
     * REPORT its heading") and misroutes data asks into report-PDF
     * synthesis. Soft artifact words stay out: "report" alone is a verb as
     * often as a noun, and only the CONCRETE formats are unambiguous.
     */
    fun goalWantsConcreteArtifact(goal: String): Boolean {
        val g = goal.lowercase()
        return CONCRETE_ARTIFACT_WORDS.any { g.contains(it) }
    }

    /** Actions that actually produce live web data. */
    private val DATA_ACTIONS = setOf(
        "WEB_SEARCH", "FETCH_URL", "GET_NEWS", "GET_WEATHER",
        "CURRENCY_CONVERT", "CHECK_STOCK", "SUMMARIZE_URL",
        // v1.3.0 round 21: the planning prompt's own dependency list (rule 4)
        // names these as data producers; the deferral gate must agree or a
        // perfectly good TRANSLATE/CALCULATE plan reads as "deferred".
        "TRANSLATE", "CALCULATE", "ANALYZE_SCREENSHOT", "GET_SYSTEM_INFO"
    )

    /** Actions that actually produce a file artifact. */
    private val ARTIFACT_ACTIONS = setOf("WRITE_FILE", "CREATE_PDF")

    /**
     * PRECISE live-data detection for the deferral gate (v1.3.0 round 21):
     * strong data nouns/noun-phrases, whole-word matched. The looser
     * [goalWantsWebData] (which matches bare "today"/"current"/"latest")
     * stays in use where RECALL matters (plan synthesis, summary routing) —
     * but the deferral gate must never flag "how are you today" or "tell me
     * about the current Roman empire" as a deferred data ask now that the
     * wrapper-form branch runs the same check (the pre-existing false
     * positive surfaced by round-21 testing).
     */
    private val STRONG_DATA_NOUNS = listOf(
        "price", "prices", "stock", "stocks", "quote", "quotes",
        "rate", "rates", "gold", "silver", "copper", "platinum",
        "bitcoin", "ethereum", "crypto", "cryptocurrency", "usd", "inr",
        "weather", "forecast", "temperature", "news", "headline",
        "headlines", "score", "scores", "standings", "dividend",
        "sensex", "nifty", "nasdaq", "xauusd", "xau", "usd/inr"
    )
    private val STRONG_DATA_PHRASES = listOf(
        "share price", "share market", "exchange rate", "market cap",
        "pe ratio", "oil price", "crude oil", "dow jones", "s&p"
    )

    /** Whole-word/phrase live-data detection — see [STRONG_DATA_NOUNS]. */
    fun goalNeedsLiveData(goal: String): Boolean {
        val tokens = goal.lowercase()
            .replace(Regex("[^a-z0-9&/]+"), " ").trim()
            .split(" ").filter { it.isNotBlank() }
        val tokenSet = tokens.toSet()
        val bigrams = tokens.zipWithNext { a, b -> "$a $b" }
        return STRONG_DATA_NOUNS.any { it in tokenSet } ||
            STRONG_DATA_PHRASES.any { it in bigrams }
    }

    /**
     * v1.6.1 (E2E run 37651287426, the b3 field regression): the planner's
     * plan JSON carries a PARAPHRASED goal. That run rewrote
     * "create a markdown file called fieldfix_marker.md containing the exact
     * line TSF FIELD MARKER 77 followed by one short paragraph about gold
     * prices" into "Create fieldfix_marker.md containing 'TSF FIELD MARKER
     * 77' followed by one short paragraph about gold prices" — dropping the
     * words that carry the artifact signal. Every goal-shape heuristic
     * downstream (the parse-time and execution-time deferral gates, the
     * concrete-artifact branch selector in the deterministic synthesizer,
     * the deliverable-name fallback) then read a FILE-CREATION goal as a
     * pure DATA goal: the synthesized plan was WEB_SEARCH + CHECK_STOCK
     * with no write step, the turn shipped a gold market note, and the
     * marker file was never written.
     *
     * The user's own words are the ground truth for goal classification —
     * a model paraphrase can never weaken them. Returns the user's message
     * verbatim when it carries any text, else the model's goal, else the
     * historical blank-goal default.
     */
    fun restoredGoal(userMessage: String, modelGoal: String): String {
        val user = userMessage.trim()
        if (user.isNotEmpty()) return user
        val model = modelGoal.trim()
        if (model.isNotEmpty()) return model
        return "User request"
    }

    /**
     * Plan-shaped deferral (v1.0.6 loop-17): a VALID plan can still refuse
     * the goal — the loop-16 field evidence is a one-step CHAT plan whose
     * response was "I don't have live market data access in this session".
     * The prose gate never sees it because the JSON parses cleanly. When a
     * data goal's plan contains NO data action, or an artifact goal's plan
     * contains NO file action, the plan defers the goal and must trigger the
     * corrective re-ask / deterministic synthesis.
     */
    fun planDefersGoal(actions: List<String>, userGoal: String): Boolean {
        if (actions.isEmpty()) return false
        val canonical = actions.map { it.trim().uppercase() }
        if (goalWantsArtifact(userGoal) && canonical.none { it in ARTIFACT_ACTIONS }) return true
        if ((goalNeedsLiveData(userGoal) || goalDemandsFreshData(userGoal)) &&
            !goalWantsArtifact(userGoal) &&
            canonical.none { it in DATA_ACTIONS }) return true
        return false
    }

    /**
     * v1.3.0 round-7: the deterministic success summary for a completed plan,
     * extracted from AgentLoop for unit testing. CHAT steps are excluded
     * (already delivered verbatim during execution); every other completed
     * step contributes its readable result. ASK_USER steps contribute the
     * user's ANSWER as a quoted phrase — the old `result.length > 5` filter
     * dropped "Pune" (4 chars) and the summary collapsed to "All done!"
     * (run-102 cap21 evidence). Returns null when nothing qualifies; the
     * caller then falls back to its goal-shape heuristics.
     */
    fun stepResultSummary(steps: List<PlanStep>): String? {
        val summaries = steps
            .filter { it.status == StepStatus.COMPLETED && !it.result.isNullOrBlank() }
            .filter { it.action.trim().uppercase() != "CHAT" }
            .mapNotNull { step ->
                val result = step.result ?: return@mapNotNull null
                when {
                    // Capitalized so it reads as a standalone sentence on
                    // ask-only plans ("You answered \"Pune\"." is the whole
                    // bubble when the LLM confirmation is unavailable).
                    step.action.trim().uppercase() == "ASK_USER" ->
                        "You answered \"${result.trim().take(120)}\""
                    result.length > 5 && !result.startsWith("{") -> result
                    else -> null
                }
            }
        return if (summaries.isEmpty()) null else summaries.joinToString(". ")
    }
}
