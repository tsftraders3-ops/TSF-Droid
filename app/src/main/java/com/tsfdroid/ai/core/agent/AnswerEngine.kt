package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus

/**
 * v1.4.0: the Hermes answer engine — the FINAL-ANSWER contract.
 *
 * Field evidence (2026-10-03 user screenshots, the third field report):
 * the plan path executed the RIGHT tools (WEB_SEARCH on "XAUUSD current
 * price", CHECK_STOCK variants), gathered real data — the snippets carried
 * "$4,199.40/oz" — and then PASTED the raw search listing as the reply:
 * numbered titles, snippets, URLs, no answer sentence anywhere. The same
 * for a 5-year stock-analysis ask. The missing stage: after tools run, the
 * RESULTS must go back INTO the model to write the user-facing answer —
 * exactly how ChatGPT / Claude / Gemini browsing turns end. Raw tool
 * output is NEVER the deliverable; it is context.
 *
 * Three layers, each independently testable:
 *  1. [synthesize] — the bounded LLM call with the answer contract below.
 *  2. [extractiveAnswer] — deterministic fallback when the tier is down:
 *     pulls the answer-bearing sentence (price + unit + as-of) out of the
 *     results and cites the source domains compactly.
 *  3. [looksLikeRawDump] — the quality guard that rejects raw listings
 *     wherever they try to surface as a final reply.
 */
internal object AnswerEngine {

    /** Listing prefixes that identify verbatim tool output. */
    private val RAW_LISTING_MARKERS = listOf(
        "top web results for", "search results for", "top results for",
        "latest news for", "results for '", "web results for"
    )

    /** Currency/number patterns that mark an answer-bearing sentence. */
    private val PRICE_PATTERN = Regex(
        """([$€£₹¥]|\busd\b|\binr\b|\bdollars?\b|\brupees?\b)\s?\d[\d,]*(?:\.\d+)?""" +
            """|\d[\d,]*(?:\.\d+)?\s?(?:usd|inr|dollars?|rupees?|%|per\s?oz|ounce|points?)""",
        RegexOption.IGNORE_CASE
    )

    /** Domain extraction for the compact source line. */
    private val URL_PATTERN = Regex("""https?://([a-z0-9.-]+\.[a-z]{2,})""", RegexOption.IGNORE_CASE)

    private const val MAX_STEP_DIGEST_CHARS = 1_600

    /**
     * The answer contract every synthesized reply must satisfy. This is
     * the prompt-side half of the engine — the deterministic half is
     * [looksLikeRawDump] + [extractiveAnswer].
     */
    const val ANSWER_CONTRACT =
        "You are TSF Droid finishing a task on the user's Android phone. " +
            "The tool steps already ran; their raw results are below. Your job is the FINAL ANSWER " +
            "the user reads — the tools are done, you are the voice that explains them.\n" +
            "RULES:\n" +
            "1. ANSWER FIRST: the very first sentence must directly answer the user's question with " +
            "the concrete fact (the number, the price, the name, the verdict). Never open with " +
            "\"I searched\" or \"Here are the results\".\n" +
            "2. NUMBERS WITH UNITS: prices/rates/percentages carry their unit and a compact source " +
            "tag, e.g. \"XAU/USD is at \$4,199/oz right now (NowPrice)\".\n" +
            "3. NO RAW DUMPS: never paste the listing. No numbered result lists, no bare URLs in " +
            "the body, no \"Top web results for...\". When the answer used web results, cite the " +
            "sites inline as (SiteName) AND end with one final line exactly like " +
            "'Sources: https://site1.com, https://site2.com' — full https:// URLs, comma-separated " +
            "(they render as tappable source chips in the chat).\n" +
            "4. ANALYSIS ASKS GET ANALYSIS: when the user asked for history/trends/analysis, write " +
            "actual analytical prose from the data (trajectory, ranges, volatility, notable moves) " +
            "— 2-4 short paragraphs or tight bullets, not a data dump.\n" +
            "5. FILES: if a file was created, say what it is in one line by NAME only (\"I've made " +
            "solar_report.pdf — open it from the card below.\") — never paste file system paths; " +
            "the chat already shows a file card.\n" +
            "6. Keep it tight: as long as the question deserves, never longer. Plain text with " +
            "simple markdown. If the results could not answer the question, say so plainly in the " +
            "first sentence and state the best fact available.\n" +
            "Write the final answer now — nothing else."

    /** Harder retry nudge when the first synthesis echoed the listing back. */
    const val SYNTHESIS_RETRY_NUDGE =
        "Your previous draft still looked like a raw result listing. Write PROSE for a human: " +
            "first sentence = the direct answer with the number and unit. No link lists."

    /**
     * True when a completed plan MUST run the synthesis stage (the plan
     * produced data/analytics a human should read as an answer, not as a
     * dump). Device-state turns (alarm/flashlight/wifi) keep the instant
     * canned path — synthesis would only add latency there.
     *
     * v1.4.0 SPEED GATE: a plan whose ONLY completed step is a data action
     * that already returned an ANSWER-SHAPED result (short, not a dump,
     * carries the figure — CHECK_STOCK's "GC=F is at 4209.3 USD (latest
     * session close).") delivers that result instantly; no LLM round-trip
     * on top of a finished answer. Multi-step or listing-shaped results
     * (WEB_SEARCH dumps, fetches, analyses) still synthesize.
     */
    fun needsSynthesis(steps: List<PlanStep>): Boolean {
        val completed = steps.filter { it.status == StepStatus.COMPLETED }
        if (completed.isEmpty()) return false
        val hasDataAction = completed.any {
            it.action.trim().uppercase() in DATA_ACTIONS
        }
        if (hasDataAction) {
            // Single data step, answer already shaped: instant delivery.
            if (completed.size == 1) {
                val r = completed[0].result
                if (!r.isNullOrBlank() && r.length < 300 && !looksLikeRawDump(r)) {
                    return false
                }
            }
            return true
        }
        // Even non-data turns whose joined results would read as a dump
        // (long, linky, listing-shaped) get the synthesis treatment.
        val joined = PlanResponseSanitizer.stepResultSummary(steps) ?: return false
        return looksLikeRawDump(joined) || joined.length > 400
    }

    /** Actions whose results are research/data a synthesis must humanize. */
    private val DATA_ACTIONS = setOf(
        "WEB_SEARCH", "FETCH_URL", "GET_NEWS", "GET_WEATHER",
        "CURRENCY_CONVERT", "CHECK_STOCK", "SUMMARIZE_URL"
    )

    /** Trailing citation footer — excluded from dump-density (chips, not slop). */
    private val SOURCES_FOOTER = Regex("""\s*Sources:\s.*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The quality guard: would a user reading [text] see raw tool output
     * instead of an answer? Matches the listing prefixes the actions emit,
     * bare-URL density, and numbered-entry dumps. The closing "Sources:
     * https://..." footer is CITATION (it renders as tappable chips) — the
     * density and numbered-entry checks measure the BODY without it.
     */
    fun looksLikeRawDump(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        if (RAW_LISTING_MARKERS.any { lower.startsWith(it) }) return true
        val body = SOURCES_FOOTER.replace(t, "")
        val urls = URL_PATTERN.findAll(body).toList()
        val urlChars = urls.sumOf { it.value.length }
        // Bare-URL density: a reply whose body is mostly links is a dump.
        if (urls.size >= 3 && urlChars > body.length * 0.35) return true
        // Numbered listing: >= 3 entries that each contain a URL.
        val numberedLines = body.lines().filter { it.matches(Regex("""\s*\d+[.)]\s.*""")) }
        if (numberedLines.size >= 3 && numberedLines.count { URL_PATTERN.containsMatchIn(it) } >= 3) return true
        return false
    }

    /**
     * The deterministic fallback when the synthesis tier is unreachable:
     * assemble the best answer from the raw results WITHOUT another LLM
     * call. Price asks get the answer-bearing sentence; everything else
     * gets the most informative non-URL lines; both get a compact Sources
     * line. Returns null when the results carry nothing usable.
     */
    fun extractiveAnswer(goal: String, steps: List<PlanStep>): String? {
        val results = steps
            .filter { it.status == StepStatus.COMPLETED && !it.result.isNullOrBlank() }
            .filter { it.action.trim().uppercase() != "CHAT" }
            .mapNotNull { it.result }
        if (results.isEmpty()) return null

        val domains = results.flatMap { r -> URL_PATTERN.findAll(r).mapNotNull { m ->
            m.groupValues.getOrNull(1)?.lowercase()?.removePrefix("www.")
        } }.distinct().take(3)

        // 1) Answer-bearing sentence: contains a price/number figure AND
        //    is not a URL line. Search listings append their URL to the
        //    snippet sentence (no separating punctuation), so URLs are
        //    STRIPPED first — "…— $4,199.40/oz … https://nowprice.io/gold"
        //    is still the price sentence once the link is gone. Prefer
        //    sentences with "is at"/"trading".
        val sentences = results
            .flatMap { r -> r.replace(Regex("""\s+"""), " ").split(Regex("""(?<=[.!?])\s+""")) }
            .map { it.replace(URL_PATTERN, " ").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length in 15..220 }
        val best = sentences
            .filter { PRICE_PATTERN.containsMatchIn(it) }
            .sortedByDescending { s ->
                (if (Regex("""\b(is at|trading|currently|now)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) 2 else 0) +
                    (if (s.length in 25..160) 1 else 0)
            }
            .firstOrNull()

        if (best != null) {
            val source = if (domains.isEmpty()) "" else " Sources: ${domains.joinToString(", ") { "https://$it" }}."
            return best + source
        }

        // 2) No price figure: best informative lines (not listing headers,
        //    not URLs), joined into a short digest.
        val informative = results.flatMap { r ->
            r.lines().map { it.trim() }
        }.filter { line ->
            line.length in 25..200 &&
                !URL_PATTERN.containsMatchIn(line) &&
                !line.matches(Regex("""\s*\d+[.)]\s.*""")) &&
                RAW_LISTING_MARKERS.none { line.lowercase().contains(it) }
        }
        if (informative.isEmpty()) return null
        val digest = informative.take(3).joinToString(" ")
        val source = if (domains.isEmpty()) "" else " Sources: ${domains.joinToString(", ") { "https://$it" }}."
        return digest + source
    }

    /**
     * Builds the per-step digest the synthesis prompt consumes — every
     * completed step contributes action + trimmed result, CHAT excluded
     * (already delivered verbatim), paths shortened to file names.
     */
    fun stepDigest(steps: List<PlanStep>): String {
        return steps
            .filter { it.status == StepStatus.COMPLETED && !it.result.isNullOrBlank() }
            // CHAT steps were already delivered verbatim mid-turn — they
            // are not context the synthesis needs to re-explain.
            .filter { it.action.trim().uppercase() != "CHAT" }
            .joinToString("\n") { step ->
                val result = shortenPaths(step.result!!)
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .take(MAX_STEP_DIGEST_CHARS)
                "- [${step.action}] ${step.description.take(120)}: $result"
            }
    }

    /** File-system paths never belong in a user answer — name only. */
    private fun shortenPaths(text: String): String =
        text.replace(Regex("""/storage/[^\s"']+""")) { m ->
            m.value.substringAfterLast('/')
        }
}
