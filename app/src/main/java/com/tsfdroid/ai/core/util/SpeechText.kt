package com.tsfdroid.ai.core.util

/**
 * v1.3.0 round 21 (the 2026-10-03 field evidence, screenshot 20:16): the
 * green "Speaking:" status line read "Speaking: **Taparia Tools Ltd (BSE:
 * 5056…" — literal markdown asterisks in the STATUS TEXT, and the phone's
 * TTS was SPEAKING them ("asterisk asterisk Taparia Tools Ltd…") because
 * [com.tsfdroid.ai.core.voice.TextToSpeechEngine] received the raw reply.
 *
 * One choke-point sanitizer: every text handed to speech (or to any
 * speech-shaped status surface) goes through [forSpeech] first. It keeps
 * every WORD of the reply — markdown is markup, not content — and makes
 * long URLs speakable-safe ("link") since hearing "h t t p s colon slash
 * slash…" is gibberish.
 *
 * Pure Kotlin, no Android dependencies — unit-testable on the JVM.
 */
object SpeechText {

    /** Fenced code blocks become a spoken placeholder, never their content. */
    private val FENCED_CODE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)

    /** [label](url) links keep only the label. */
    private val LINK = Regex("""\[([^\]]*)\]\((https?://[^)\s]+)\)""")

    /** Bare http(s) URLs (before other transforms, so they never half-strip). */
    private val BARE_URL = Regex("""https?://[^\s)\]}>"'<>]+""")

    /** ATX headings: `#### Title` → `Title`. */
    private val HEADING = Regex("""^ {0,3}#{1,4}[ \t]+""", RegexOption.MULTILINE)

    /** Bullet/numbered markers at line starts: `- ` `* ` `• ` `2. `. */
    private val LIST_MARKER = Regex("""^ {0,3}(?:[-*•–]|\d{1,3}\.)[ \t]+""", RegexOption.MULTILINE)

    /** Horizontal rules → a soft pause. */
    private val HR = Regex("""^ {0,3}(?:-{3,}|\*{3,}|_{3,})[ \t]*$""", RegexOption.MULTILINE)

    /** Bold/italic/inline-code markers (order matters: ** before *). */
    private val BOLD_ITALIC = Regex("""(\*\*|__|~~)`?""")

    /** Cap: absurdly long speech is cut at a sentence boundary near the cap. */
    private const val MAX_SPEECH_CHARS = 4_000

    /**
     * Converts a markdown-carrying agent reply into clean speakable text:
     * keeps all the words, drops all the markup, and replaces raw URLs with
     * the word "link" so they are never spelled out.
     */
    fun forSpeech(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw
        t = FENCED_CODE.replace(t) { " (code block) " }
        t = LINK.replace(t) { m -> m.groupValues[1].ifBlank { "link" } }
        t = BARE_URL.replace(t) { _ -> "link" }
        t = HR.replace(t) { _ -> " — " }
        t = HEADING.replace(t, "")
        t = LIST_MARKER.replace(t, "")
        t = BOLD_ITALIC.replace(t, "")
        t = t.replace("`", "")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        t = t.trim()
        if (t.length > MAX_SPEECH_CHARS) {
            val cut = t.take(MAX_SPEECH_CHARS)
            val lastStop = cut.lastIndexOf('.').let { dots ->
                maxOf(dots, cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            }
            t = if (lastStop > MAX_SPEECH_CHARS / 2) cut.take(lastStop + 1) else cut
        }
        return t
    }
}
