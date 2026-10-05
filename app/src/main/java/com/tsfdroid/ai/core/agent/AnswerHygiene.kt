package com.tsfdroid.ai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v1.6.0 (field report 2026-10-05, P0-4/P0-5/P0-6): the ONE answer-shape gate.
 *
 * Three field failures shipped app internals to the user as the final answer:
 *  - chat-033518 02:38: the delivered 12,274-char answer OPENED with six raw
 *    `web_search {"query": …}` lines — the model's tool intentions emitted as
 *    text alongside the real tool_calls; nothing filtered them.
 *  - chat-021145 01:57: the ENTIRE answer was
 *    `I used web_search({"query": …}) to work on this.` — the harness's own
 *    context-stub sentence, delivered to the user after 56 seconds of work.
 *  - chat-021218 16:28: the delivered message was a fenced ```json
 *    `{"speech": …, "type": "SIMPLE", "action": null}` wrapper — the model
 *    answering in its plan-JSON dialect in CHAT mode, verbatim, unreadable.
 *
 * The pre-existing gates each matched a DIFFERENT shape (echoed-listing prefixes,
 * monologue openers) — this object is the unified gate every final-content path
 * runs before saving. It is deliberately PURE (no Android imports) so the JVM
 * test rig replays the exact field transcripts against it.
 *
 * Design rule, both directions:
 *  - every field poison shape is neutralized (syntax runs stripped, JSON
 *    envelopes unwrapped, stub sentences replaced, placeholder brackets
 *    detected);
 *  - CLEAN answers from the same corpus (the gold reply, the workout file
 *    confirmation, the jetpack answer) must pass through UNCHANGED — a
 *    hygiene pass that rewrites good answers is a regression, not a fix.
 */
internal object AnswerHygiene {

    /** The tool names the app advertises — used to spot tool-syntax runs. */
    private val TOOL_NAMES = listOf(
        "web_search", "fetch_url", "write_file", "create_pdf", "read_file",
        "list_files", "shell", "ask_user", "search", "fetch"
    )

    /**
     * All fragments of a `tool_name {...}` or `tool_name(...)` invocation. The
     * field dump glued six `web_search {"query":…}` fragments on shared lines,
     * so fragments must be REMOVED one-by-one, not line-matched whole.
     */
    private val TOOL_FRAGMENT = Regex(
        "(" + TOOL_NAMES.joinToString("|") { Regex.escape(it) } + ")\\s*[({].*?[)}]",
        RegexOption.IGNORE_CASE
    )

    /** The harness's own round-stub shape, delivered as the whole answer. */
    private val STUB_WHOLE_ANSWER = Regex(
        "^\\s*I (used|ran|called|executed)\\s+\\S+\\s*\\(.*\\)\\s+to work on this\\.?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** A leading `I used X(…)` stub sentence before real content. */
    private val STUB_LEADING_SENTENCE = Regex(
        "^\\s*I (used|ran|called|executed)\\s+\\S+\\s*\\([^)]*\\)\\s+to work on this\\.?\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * Unfilled template placeholders — the gold-report shape:
     * `[today 24k price]`, `[calc %]`, `[Financial news site 1]`. The word
     * list anchors on template vocabulary so real prose with brackets
     * (arrays, [sic], markdown links) is not flagged.
     */
    private val PLACEHOLDER_BRACKET = Regex(
        "\\[\\s*(?:today|last|calc|price|financial|source|your|the|name|date|value|number|insert|fill|placeholder|site)\\b[^\\]]*\\]",
        RegexOption.IGNORE_CASE
    )

    /** JSON keys whose presence means "this is an answer envelope, not prose". */
    private val ENVELOPE_SPEECH_KEYS = listOf("speech", "response", "answer", "text", "content")

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fenced or bare JSON that spans (almost) the whole message: starts with
     * ```json (or {) and ends with ``` (or }). "Almost" because the field
     * wrapper carried escapes and trailing whitespace — but a sentence that
     * merely CONTAINS braces (code answers) is not an envelope: the extracted
     * object must cover >80% of the message.
     */
    private fun wholeMessageJsonCandidate(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val unfenced = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!unfenced.startsWith("{")) return null
        val candidate = PlanResponseSanitizer.extractFirstJsonObject(unfenced) ?: return null
        val coverage = candidate.length.toDouble() / unfenced.length.toDouble()
        return if (coverage > 0.8) candidate else null
    }

    /**
     * If [text] is a whole-message JSON answer envelope ({"speech": …} and
     * friends — the plan dialect leaking into a chat reply), returns the
     * extracted speech text. Null when the text is not an envelope.
     */
    fun extractJsonEnvelope(text: String): String? {
        val json = wholeMessageJsonCandidate(text) ?: return null
        return try {
            val obj = lenientJson.parseToJsonElement(json) as? JsonObject ?: return null
            val speech = ENVELOPE_SPEECH_KEYS
                .firstNotNullOfOrNull { key ->
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()
                }
            // Only a non-trivial string inside counts as the real answer.
            if (speech != null && speech.length > 3) speech else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Removes LEADING tool-syntax runs (`web_search {"query": …}` glued or on
     * separate lines) from an answer. Leading only: trailing/real content is
     * preserved verbatim — including content that shares a line with the LAST
     * syntax fragment (the field dump's final line was
     * `web_search {"query": …}I have gathered the following…`).
     */
    fun stripLeadingToolSyntax(text: String): String {
        val lines = text.lines()
        var i = 0
        var leadingStripped = ""
        var droppedAny = false
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // Blank lines continue the run only when more syntax follows.
                val nextNonBlank = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                if (nextNonBlank != null && TOOL_FRAGMENT.containsMatchIn(nextNonBlank)) {
                    i++
                    droppedAny = true
                    continue
                }
                break
            }
            // Strip syntax fragments from the START of the line, one by one.
            var rest = line.trimStart()
            var removedAny = false
            while (true) {
                val m = TOOL_FRAGMENT.find(rest)
                if (m != null && m.range.first == 0) {
                    rest = rest.substring(m.range.last + 1).trimStart()
                    removedAny = true
                } else break
            }
            if (removedAny && rest.isBlank()) {
                // Pure syntax line (or several glued fragments) — drop it.
                i++
                droppedAny = true
            } else if (removedAny && rest.isNotBlank()) {
                // Partial line: syntax prefix + real content on the same line.
                leadingStripped = rest
                i++
                break
            } else {
                break
            }
        }
        if (!droppedAny && leadingStripped.isEmpty()) return text
        val tail = if (leadingStripped.isEmpty()) lines.drop(i) else listOf(leadingStripped) + lines.drop(i)
        return tail.joinToString("\n").trimStart()
    }

    /**
     * v1.6.0 (field P2-4): reasoning models emit full-sentence segments that the
     * streaming collectors used to concatenate bare ("Let me start.Let me plan
     * this." - the field's 16-minute turn). Sentence-complete segment
     * boundaries join with a newline; mid-word deltas (no terminal
     * punctuation) still glue seamlessly.
     */
    fun joinThinkingSegments(acc: String, delta: String): String {
        if (acc.isEmpty()) return delta
        if (delta.isEmpty()) return acc
        val endsSentence = acc.lastOrNull()?.let { ".!?:".contains(it) } == true
        val startsNewSentence = delta.firstOrNull()?.isUpperCase() == true ||
            delta.startsWith(" ") || delta.startsWith("\n")
        return if (endsSentence && startsNewSentence) "$acc\n$delta" else acc + delta
    }

    /** True when the whole answer is just the harness round-stub sentence. */
    fun isStubShaped(text: String): Boolean =
        STUB_WHOLE_ANSWER.containsMatchIn(text.trim())

    /** True when the content carries unfilled `[placeholder]` brackets. */
    fun hasUnfilledPlaceholders(text: String): Boolean =
        PLACEHOLDER_BRACKET.containsMatchIn(text)

    /**
     * Collapses oversized fenced code blocks in a chat answer. The field
     * evidence (chat-033518 03:14): a file that failed to write was dumped
     * inline as a 45,368-char message opening "Here are both files, complete
     * and ready to use." — a false claim next to a wall of content. Files
     * belong on disk (with a card); a chat answer quotes at most
     * [FENCE_KEEP_CHARS] of any one fence.
     */
    const val FENCE_KEEP_CHARS = 1500
    const val FENCE_MAX_CHARS = 6000

    fun collapseOversizedFences(text: String): String {
        if (!text.contains("```")) return text
        val fence = Regex("(?s)```[a-zA-Z0-9]*\\n(.*?)```")
        var out = text
        for (m in fence.findAll(text).toList().asReversed()) {
            val body = m.groupValues[1]
            if (body.length > FENCE_MAX_CHARS) {
                val language = m.value.substringAfter("```").substringBefore("\n")
                val header = if (language.isBlank()) "```" else "```$language"
                val replacement = "$header\n" + body.take(FENCE_KEEP_CHARS) +
                    "\n…[content truncated — ask me to save the full version as a file and it will land in your folder with a card]\n```"
                out = out.replaceRange(m.range, replacement)
            }
        }
        return out
    }

    /**
     * THE gate. Runs on every final user-facing answer before save:
     *  1. a whole-message JSON envelope → the speech inside (field P0-6);
     *  2. leading tool-syntax runs → stripped (field P0-4);
     *  3. a leading stub sentence → removed; a stub-only answer → null so the
     *     caller runs its fallback ladder instead of saving it (field P0-5);
     *  4. oversized fenced file dumps → collapsed.
     *
     * Returns the sanitized text, or null when NOTHING user-facing remains
     * (caller must not save an empty/nauseating reply — it falls back).
     */
    fun sanitizeFinalAnswer(text: String): String? {
        var current = text
        // (1) JSON envelope — unwrapping replaces the whole message.
        extractJsonEnvelope(current)?.let { current = it }
        // (2) Leading tool-syntax runs.
        current = stripLeadingToolSyntax(current)
        // (3) Stub shapes.
        if (isStubShaped(current)) return null
        current = STUB_LEADING_SENTENCE.replace(current, "")
        current = current.trim()
        if (current.isEmpty()) return null
        // (4) Oversized fences.
        current = collapseOversizedFences(current)
        return current
    }
}
