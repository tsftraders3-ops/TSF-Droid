package com.tsfdroid.ai.core.agent

/**
 * v1.6.0 (field report 2026-10-05, P0-2/P0-3/P0-7/P0-8/P1-1/P1-6): the
 * goal→deliverable contract, extracted as PURE Kotlin so the JVM rig replays
 * the exact field goals against it.
 *
 * What this object owns:
 *  - [parseRequestedFilenames]: the names the user literally asked for
 *    ("a report called ondevice_llm_benchmark_2026.md", "save it as
 *    llm_perf_metrics.csv", "a python script call scrap_titles.py") — the
 *    field failures saved every one of those as Documents/document.txt or
 *    Documents/report.pdf instead.
 *  - [contentGate]: reject narration-as-content ("I'll first check the
 *    environment…") and unfilled `[placeholder]` templates before any write
 *    ships (the 1-sentence 201KB PDF and the bracket-template gold PDF).
 *  - [collisionFreeName]: never silently overwrite an existing file (the
 *    02:31/03:14 double report.pdf — the user had to rescue one by hand).
 *  - [isStopCommand]: the cancel lexicon for needs-input answers (four
 *    explicit "stop" replies were consumed as parameter text).
 *  - [humanizeParamPrompt]: "I need the searchText…" → "Which text should I
 *    tap?".
 *  - [isInterrogativeGoal]: questions must not become file-writes (the
 *    export-location question that overwrote scrap_titles.py).
 *  - [sanitizeCalculateExpression]: arithmetic only — the gold turn's
 *    CALCULATE died on prose inside the expression.
 *  - [extractPhoneNumber]: "sms hi to the number 123" must send to 123,
 *    not look up a contact named "sms hi to the number 123".
 */
internal object GoalContract {

    // ── Requested filenames ─────────────────────────────────────────────

    /** "called X", "named X", "titled X", "save it as X", "name it X". */
    private val NAMED_AS = Regex(
        "(?:called|named|titled|name it|call it|save (?:it )?as|saved as|exported as|with the name)\\s+[\"']?([A-Za-z0-9_][A-Za-z0-9_ .\\-/]*?)[\"']?(?=\\s*(?:,|and|that|which|with|containing|to|so|before|\\.|;|$))",
        RegexOption.IGNORE_CASE
    )

    /** A bare filename mentioned in the goal (with a known extension). */
    private val BARE_FILENAME = Regex(
        "\\b([A-Za-z0-9_][A-Za-z0-9_\\-]{0,60}\\.(?:pdf|md|txt|csv|json|html?|py|js|ts|yaml|yml|xml|css))\\b",
        RegexOption.IGNORE_CASE
    )

    private val KNOWN_EXTENSIONS = setOf(
        "pdf", "md", "txt", "csv", "json", "html", "htm", "py", "js", "ts",
        "yaml", "yml", "xml", "css"
    )

    /**
     * Every filename the goal explicitly requests, in order of appearance.
     * Verbatim field check: "compile a comprehensive report called
     * ondevice_llm_benchmark_2026.md … extract all the raw performance
     * numbers into a table and save it as llm_perf_metrics.csv" must yield
     * BOTH names. Quoted names keep their exact form; unquoted names are
     * trimmed of trailing sentence fragments.
     */
    fun parseRequestedFilenames(goal: String): List<String> {
        val found = mutableListOf<String>()
        for (m in NAMED_AS.findAll(goal)) {
            val name = m.groupValues[1].trim()
                .removeSuffix(" file").removeSuffix(" document").trim()
            if (name.isNotEmpty() && !found.contains(name)) found.add(name)
        }
        for (m in BARE_FILENAME.findAll(goal)) {
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && !found.contains(name)) found.add(name)
        }
        return found.filter { name ->
            val ext = name.substringAfterLast('.', "")
            ext.lowercase() in KNOWN_EXTENSIONS
        }.map { sanitizeFilename(it) }
    }

    /** Strips path-illegal characters, keeps the extension verbatim. */
    fun sanitizeFilename(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return "document.txt"
        val ext = cleaned.substringAfterLast('.', "").lowercase()
        val base = cleaned.substringBeforeLast('.').ifBlank { "file" }
        return if (ext in KNOWN_EXTENSIONS) "$base.$ext" else cleaned
    }

    /** The extension the CONTENT kind implies, when the goal names none. */
    fun extensionForGoal(goal: String): String {
        val g = goal.lowercase()
        return when {
            g.contains("pdf") -> "pdf"
            g.contains("csv") || g.contains("table") || g.contains("metrics") -> "csv"
            g.contains("json") || g.contains("yaml") -> g.substringAfterLast("json").let { if (g.contains("yaml")) "yaml" else "json" }
            g.contains("html") || g.contains("website") || g.contains("web page") || g.contains("webpage") -> "html"
            g.contains("python") || g.contains("script") || g.contains(".py") -> "py"
            g.contains("markdown") || g.contains("readme") || g.contains("report") || g.contains("notes") || g.contains("research") || g.contains("document") -> "md"
            g.contains("text") -> "txt"
            else -> "md"
        }
    }

    /** A filesystem-safe slug from the goal for unnamed deliverables. */
    fun slugFromGoal(goal: String): String {
        val slug = goal.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("_")
        return (slug.ifBlank { "document" }).take(48)
    }

    /**
     * v1.6.0 round 6 (run 37373954932 forensics, the b3 failure — field
     * P0-8a's missing half): the goal says "a markdown file called
     * fieldfix_marker.md" but the model's plan carried an EMPTY filePath —
     * the step parked on a needs-input prompt asking the user for a name
     * they had already given. Returns the deliverable name for a write step
     * whose filePath is blank: the goal's requested filename (first one not
     * already used by a SIBLING step), else the goal slug with the
     * extension the goal implies. Null when the goal names no file and no
     * sensible slug can be derived (the honest ask stays an ask).
     */
    fun deliverableNameForWriteStep(
        goal: String,
        params: Map<String, String>,
        siblingFilePaths: Set<String>
    ): String? {
        val current = params["filePath"]?.trim().orEmpty()
        if (current.isNotBlank()) return null // already named — nothing to fill
        val requested = parseRequestedFilenames(goal)
            .map { it.substringAfterLast('/') } // a requested "MarketReports/x.pdf" still names x.pdf
        val unusedRequested = requested.firstOrNull { name ->
            siblingFilePaths.none { it.trim().equals(name, ignoreCase = true) }
        }
        if (unusedRequested != null) return unusedRequested
        // No requested (or all already taken by sibling steps): a slug only
        // when the goal is clearly an artifact ASK — never for a question
        // (the P0-7 lesson cuts the other way here: "where are exports
        // saved?" must be answered, not named document.md).
        if (isInterrogativeAboutStorage(goal) || goal.trim().endsWith("?")) return null
        val ext = extensionForGoal(goal)
        val slug = slugFromGoal(goal)
        if (slug.isBlank() || slug == "document") return null
        return "$slug.$ext"
    }

    /**
     * Auto-rename on collision: "report.pdf" → "report-2.pdf",
     * "report-2.pdf" → "report-3.pdf". NEVER overwrite — the user lost
     * scrap_titles.py to a silent `writeText` in the field.
     */
    fun collisionFreeName(existing: Set<String>, desired: String): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while ("$base-$n$ext" in existing) n++
        return "$base-$n$ext"
    }

    /**
     * v1.6.0 round 6 (run 37373954932, visible in the gold answer): the
     * filenames a summary CLAIMS to have produced, for the claim audit.
     * URLs are stripped FIRST — a cited source ending in .html/.md/.js is
     * a LINK, not a promised deliverable. The old inline regex matched
     * "live-gold-price.html" inside https://goldprice.org/live-gold-price.html
     * and the audit appended a false honesty note ("I mentioned it but
     * didn't create it") to answers that merely cited their sources.
     */
    fun claimedFilenamesIn(summary: String): Set<String> {
        val textWithoutLinks = summary.replace(Regex("https?://\\S+"), " ")
        // v1.6.0 round 6: kt/ts joined the extension set — the field's P1-6
        // case promised "AgentKeepAliveManager.kt" that was never written.
        return Regex("\\b[A-Za-z0-9_][A-Za-z0-9_\\-]*\\.(?:md|csv|txt|json|pdf|py|html?|yaml|yml|js|ts|kt|sh)\\b")
            .findAll(textWithoutLinks).map { it.value.lowercase() }.toSet()
    }

    // ── Content gates ────────────────────────────────────────────────────

    /** Narration shapes that must never ship as document content. */
    private val NARRATION_OPENERS = listOf(
        "i'll first", "i will first", "i'll start", "i will start",
        "let me start", "let me first", "i'm going to", "i am going to",
        "i'll check", "i will check", "i'll gather", "i will gather",
        "i'll begin", "i will begin", "first, let me", "i'll see",
        "i will see", "i'll try", "i will try", "i need to check",
        "i'll search", "i will search", "let me search", "let me check",
        "i'll analyze", "i will analyze", "i'll look", "i will look"
    )

    /**
     * True when the content is model NARRATION, not a deliverable. Field
     * evidence (P0-2): the dispatched PDF content was exactly
     * "I'll first check the environment and whether I can gather any real
     * data." — a 70-char mid-work status line that became a 201KB PDF.
     *
     * E2E evidence (run 37315400374, cap4): the first version of this gate
     * rejected a SHORT DECLARATIVE report as "narration" (no digits, few
     * lines) and broke the capability suite. The gate is now CONSERVATIVE:
     * narration requires a COMMITMENT OPENER (or narration vocabulary in a
     * structureless short text) — never mere brevity.
     */
    fun isNarrationShaped(content: String): Boolean {
        val trimmedContent = content.trim()
        val lower = trimmedContent.lowercase()
        if (lower.isEmpty()) return true
        val hasStructure = trimmedContent.contains("#") ||
            trimmedContent.contains("|") ||
            trimmedContent.lines().count { it.isNotBlank() } > 4 ||
            Regex("\\d").containsMatchIn(trimmedContent)
        // A commitment opener ("I'll first check...") is narration when no
        // real document body follows it.
        if (NARRATION_OPENERS.any { lower.startsWith(it) } && !hasStructure) return true
        if (lower.length < 240) {
            // Short text with narration vocabulary anywhere and no structure.
            return !hasStructure && NARRATION_OPENERS.any { lower.contains(it) }
        }
        return false
    }

    /**
     * The full pre-write gate. Returns null when content may ship, or the
     * rejection reason (for logs + the honest fallback message).
     */
    fun contentGate(content: String, requestedExtension: String? = null): String? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return "content is empty"
        if (isNarrationShaped(trimmed)) return "content is model narration, not a deliverable"
        if (AnswerHygiene.hasUnfilledPlaceholders(trimmed)) return "content still contains unfilled [placeholder] brackets"
        // Never write markdown into a .json/.csv name (field P1-1b: the
        // 4-file architecture ask shipped a markdown spec as data.json).
        if (requestedExtension == "json") {
            val looksJson = trimmed.startsWith("{") || trimmed.startsWith("[")
            if (!looksJson) return "content is not valid JSON but the filename says .json"
        }
        if (requestedExtension == "csv") {
            val firstLine = trimmed.lines().firstOrNull { it.isNotBlank() } ?: ""
            if (!firstLine.contains(",") && !firstLine.contains(";")) {
                return "content is not tabular but the filename says .csv"
            }
        }
        return null
    }

    // ── The user's stop words ────────────────────────────────────────────

    /**
     * Field evidence (chat-111835): "stop don't need to do anything" was fed
     * into a TYPE_TEXT parameter while the plan kept running. The lexicon
     * catches stop-intent while leaving real parameter answers ("forward",
     * "ooo", "kjn", "kolkata") untouched — the match is anchored and
     * requires a cancel VERB, not any word.
     */
    private val STOP_COMMANDS = listOf(
        "stop", "stop it", "stop this", "stop now", "please stop", "stop the",
        "cancel", "cancel it", "cancel this", "never mind", "nevermind",
        "forget it", "abort", "quit", "no stop", "no, stop", "don't",
        "do not", "don't do", "don't need", "do not need", "no need",
        "stop don't need to do anything", "dont", "leave it", "drop it",
        "skip it", "forget that", "abort it", "call it off"
    )

    fun isStopCommand(answer: String): Boolean {
        val a = answer.trim().lowercase()
            .replace(Regex("[.!]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (a.isEmpty()) return false
        if (a in STOP_COMMANDS) return true
        // "stop don't need to do anything" and friends: a leading cancel verb
        // followed by anything that does not introduce a NEW parameter value.
        return STOP_COMMANDS.any { cmd ->
            a == cmd || a.startsWith("$cmd ") ||
                (cmd.length > 3 && a.endsWith(" $cmd"))
        }
    }

    /**
     * Needs-input prompt copy: internal param names speak user language.
     * "I need the searchText to complete this. Text to find the field" →
     * "Which text should I tap / find on screen?". The map is by param key;
     * unknown keys get a generic readable fallback.
     */
    fun humanizeParamPrompt(actionName: String, paramKey: String, originalQuestion: String): String {
        val key = paramKey.lowercase()
        val ask = when (key) {
            "searchtext", "text", "totap", "elementtext" -> "Which text should I tap on the screen?"
            "content", "message", "texttotype", "typethis" -> "What should I type there?"
            "direction" -> "Which way should I scroll — down or up?"
            "url", "link", "website" -> "Which web address should I open?"
            "query", "search", "searchterm" -> "What should I search for?"
            "appname", "app", "package" -> "Which app should I use?"
            "contact", "recipient", "to" -> "Who should I send this to (name or number)?"
            "time", "whentime", "at" -> "What time should I use?"
            "filename", "filepath", "path", "name" -> "What should I name the file?"
            "title" -> "What should the title say?"
            "city", "location", "place" -> "Which city or place?"
            "count", "times", "repeat" -> "How many times?"
            "duration", "seconds", "minutes" -> "How long should it wait or run?"
            "email", "address" -> "Which email address?"
            "symbol", "ticker" -> "Which stock symbol (e.g. AAPL)?"
            else -> "What should I use for '${paramKey.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()}'?"
        }
        // Keep options the action offered (e.g. forward/backward) — they are
        // real choices, just appended to the human question.
        val options = originalQuestion.lines().drop(1)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return if (options.isBlank()) ask else "$ask\n$options"
    }

    // ── Routing ──────────────────────────────────────────────────────────

    /**
     * Field evidence (P0-7): "can you tell me the location in device where
     * the export chats are saved" became a WRITE_FILE plan that overwrote
     * the user's script. A goal whose sentence is an INTERROGATIVE about
     * app behavior — and carries no artifact-CREATE signal — must never be
     * routed to file steps.
     */
    private val INTERROGATIVE_OPENERS = listOf(
        "where ", "can you tell me", "could you tell me", "what is the",
        "what's the", "which folder", "where can i", "where do ", "where does ",
        "how do i find", "how can i find", "is there a way to find",
        "do you know where", "what path", "which location"
    )

    private val APP_FACT_TOPIC = Regex(
        "(export|saved|stored|located|location|folder|path|directory).{0,40}(chat|file|note|report|pdf|data)|((chat|file)s?) .{0,30}(export|saved|stored)",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when the goal is a QUESTION (not a command) about where/how the
     * app itself stores something. These must answer in chat, never write.
     */
    fun isInterrogativeAboutStorage(goal: String): Boolean {
        val lower = goal.trim().lowercase()
        if (lower.endsWith("?") || INTERROGATIVE_OPENERS.any { lower.startsWith(it) }) {
            // An interrogative that ALSO commands a create is still a task.
            val createSignal = listOf(
                "create", "make", "write", "generate", "save it as", "build",
                "export this", "copy it", "move it", "delete"
            )
            if (createSignal.none { lower.contains(it) }) {
                return APP_FACT_TOPIC.containsMatchIn(lower)
            }
        }
        return false
    }

    /** The app's real storage facts — grounded context for such questions. */
    val APP_STORAGE_FACTS: String =
        "App storage facts (TSF Droid): exported chat JSON files are saved to the app's " +
            "own workspace folder at Android/data/com.tsfdroid.ai/files/workspace/Exports/ " +
            "(shareable via the chat menu's Export chat action). Files the agent writes go to " +
            "the user's chosen folder when one is picked in Settings, otherwise to the same " +
            "app workspace. Never answer storage-location questions from guesswork about other " +
            "apps — use these facts."

    // ── Capability honesty (B1) ──────────────────────────────────────────

    /**
     * v1.6.0 (field P0-1 — the fabricated cab booking): when the goal demands
     * an outcome NO registered action can produce, the app says so instead of
     * letting an ask-only plan "confirm" it. Returns the honest note for the
     * goal's verb class, or null when the goal is within the app's powers.
     */
    fun unfulfillableGoalNote(goal: String): String? {
        val g = goal.lowercase()
        val isBooking = Regex("\\b(book|booking|reserve|reservation|cab|uber|ola|lyft|ride|ticket|hotel|flight)\\b").containsMatchIn(g)
        val isOrder = Regex("\\b(order|ordering)\\b").containsMatchIn(g) &&
            Regex("\\b(food|pizza|grocery|groceries|meal|delivery|swiggy|zomato|amazon)\\b").containsMatchIn(g)
        val isPayment = Regex("\\b(pay|payment|checkout|recharge|bill)\\b").containsMatchIn(g) &&
            Regex("\\b(pay|payment|checkout|recharge|bill)\\b").containsMatchIn(g)
        return when {
            isBooking -> "nothing has been booked — I don't have a booking " +
                "action on this device. I can open the app (Uber, Ola, and so on) " +
                "for you to finish the booking yourself."
            isOrder -> "nothing has been ordered — I can't complete purchases. " +
                "I can open the app so you can place the order."
            isPayment -> "no payment was made — I can't complete payments on your " +
                "behalf. I can open the app for you to finish it."
            else -> null
        }
    }

    // ── Yahoo symbol variants (round 9) ──────────────────────────────────

    /** COMEX futures aliases of the major metals — Yahoo serves these when
     *  the forex pair 404s by region. */
    val METALS_FUTURES = mapOf(
        "XAU" to "GC=F", "XAG" to "SI=F", "XPT" to "PL=F", "XPD" to "PA=F"
    )

    /** The metal roots' English names, for search fallback phrases. */
    val METALS_NAMES = mapOf(
        "XAU" to "gold", "XAG" to "silver", "XPT" to "platinum", "XPD" to "palladium"
    )

    /**
     * v1.6.0 round 9 (run 37424676213, the goldPriceAsk forensics): Yahoo's
     * chart endpoint needs the instrument's exchange suffix — and the BARE
     * three-letter metal root (exactly what the quote-assist injects for
     * metals goals: "XAU" for gold) 404'd unsuffixed
     * (query1.finance.yahoo.com/v8/finance/chart/XAU -> 404), leaving the
     * turn with no digit-bearing backend when the search chain was poisoned.
     * The chain: already-suffixed symbols pass through; six pure letters
     * (XAUUSD, EURUSD) get the "=X" pair plus the COMEX futures alias for
     * the major metals; the BARE three-letter metal root gets the pair AND
     * the alias; everything else (stocks, dashed tickers) keeps its
     * planner-given form.
     */
    fun yahooSymbolVariants(raw: String): List<String> {
        val upper = raw.uppercase().trim()
        if (upper.isEmpty()) return emptyList()
        val metalRoot = upper.take(3)
        return when {
            upper.contains('=') -> listOf(upper)
            Regex("^[A-Z]{6}$").matches(upper) -> {
                val v = mutableListOf(upper + "=X")
                METALS_FUTURES[metalRoot]?.let { v.add(it) }
                v
            }
            METALS_FUTURES.containsKey(upper) ->
                listOf(upper + "USD=X", METALS_FUTURES.getValue(upper))
            else -> listOf(upper)
        }
    }

    // ── Public search query sanitization (B6) ──────────────────────────

    /**
     * v1.6.0 (field P1-7 — the privacy leak): a WEB_SEARCH query built from a
     * raw first-person request never ships verbatim - the 403-char
     * morning-briefing ask ("my calendar events for today... read my last 5
     * unread emails...") went to a public engine as one query. Returns the
     * goal's first PUBLIC data clause (weather/headlines/prices - the things
     * a search engine is actually for), de-personalized; null when the query
     * is not a private dump or no public clause exists.
     */
    fun publicSearchClause(query: String): String? {
        val isPrivateDump = (
            Regex("(?i)\\bmy\\b|\\bmine\\b|\\bfor me\\b|\\bunread\\b|\\bi want\\b|\\bi need\\b")
                .containsMatchIn(query) && query.length > 60
            ) || query.length > 200
        if (!isPrivateDump) return null
        val clause = query.split(Regex(",|\\band\\b|\\bthen\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .firstOrNull { c ->
                Regex("(?i)weather|forecast|headline|news|price|stock|score|gold|rate|market|temperature|quote")
                    .containsMatchIn(c)
            }
            ?.let(::dePersonalizeClause)
        return clause?.takeIf { it.length in 4..140 }
    }

    private fun dePersonalizeClause(clause: String): String =
        clause.replace(Regex("(?i)\\bmy\\s+"), " ")
            .replace(Regex("(?i)\\bmy\\b"), " ")
            .replace(Regex("(?i)\\bfor me\\b"), " ")
            .replace(Regex("(?i)\\bplease\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── CALCULATE / phone extraction ────────────────────────────────────

    /**
     * Field evidence (P0-3): the model passed `14780 - 14200 * 100 / 14200,
     * using prices found in steps s1 and s2` — prose inside the arithmetic.
     * Extract the leading arithmetic expression only.
     */
    fun sanitizeCalculateExpression(raw: String): String {
        val expression = raw.trim()
            .substringBefore(", using")
            .substringBefore(", where")
            .substringBefore(", with")
            .substringBefore(" using ")
            .substringBefore(" based on ")
            .substringBefore(" from ")
            .substringBefore(" (prices")
            .trim()
        // Keep only arithmetic-safe characters; drop trailing prose glued
        // without a separator.
        val pattern = Regex("^[0-9+\\-*/().\\s%]+$")
        val safe = if (pattern.matches(expression)) {
            expression
        } else {
            expression.takeWhile { it.isDigit() || it in "+-*/(). %" }.trim()
        }
        return safe.trimEnd(',', ' ').trimEnd('(')
    }

    /**
     * Field evidence (P1-5): "sms hi to the number 123" was resolved as a
     * contact LOOKUP of the whole phrase. Extract a phone number embedded in
     * free text: 3+ digits with optional +/spaces/dashes.
     */
    private val PHONE_IN_TEXT = Regex("\\+?\\d[\\d\\s-]{1,14}\\d")

    fun extractPhoneNumber(text: String): String? {
        val m = PHONE_IN_TEXT.find(text) ?: return null
        val digits = m.value.replace(Regex("[\\s-]"), "")
        // A bare number like "123" counts (the user literally typed it), but
        // things like "2026" in dates inside a sentence do not — require the
        // match to read as a number reference or be long enough to be phone-like.
        val surrounding = text.replace(m.value, " ")
        val saysNumber = listOf("number", "phone", "mobile", "no.", "call", "text", "sms")
            .any { surrounding.lowercase().contains(it) }
        return when {
            digits.length >= 7 -> digits
            digits.length in 3..6 && saysNumber -> digits
            else -> null
        }
    }
}
