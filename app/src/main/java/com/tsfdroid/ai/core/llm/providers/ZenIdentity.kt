package com.tsfdroid.ai.core.llm.providers

import java.security.SecureRandom

/**
 * Client-provenance identity for the OpenCode Zen endpoint.
 *
 * Reverse-engineered from the open-source OpenCode client
 * (sst/opencode, packages/opencode/src/session/llm/request.ts and
 * packages/opencode/src/provider/provider.ts): every LLM request the
 * official client sends to opencode.ai/zen carries
 *
 *  - `Authorization: Bearer public` when the user has no Zen key
 *    (the endpoint treats the literal string "public" as anonymous),
 *  - `User-Agent: opencode/<channel>/<version>/<client>`,
 *  - `x-opencode-project`, `x-opencode-session`, `x-opencode-request`
 *    and `x-opencode-client` identity headers.
 *
 * Requests that lack this shape are rejected server-side with
 * `FreeTierError` ("OpenCode's free tier can only be used from within
 * OpenCode") — the failure users saw in v1.0.1, where our interceptor
 * stripped Authorization entirely and sent a generic User-Agent.
 *
 * Identifier format follows packages/schema/src/identifier.ts: a
 * 26-character string whose first 12 characters encode a timestamp
 * (six big-endian bytes of `~(ms * 0x1000 + counter)` for descending
 * sort), followed by 14 random base-62 characters. Prefixed variants
 * (`ses_...`, `msg_...`) wrap the same body.
 */
object ZenIdentity {

    /** Matches the official CLI's User-Agent shape (opencode/latest/<version>/cli). */
    const val CLIENT_CHANNEL = "latest"

    /**
     * Pinned to the official client version the endpoint was verified
     * against. The server may gate on known-good client versions, so this
     * tracks the CLI rather than the app version.
     */
    const val CLIENT_VERSION = "2.0.16"

    const val CLIENT_NAME = "cli"
    const val ANONYMOUS_KEY = "public"

    const val UA: String = "opencode/$CLIENT_CHANNEL/$CLIENT_VERSION/$CLIENT_NAME"

    private const val BODY_LENGTH = 26
    private const val HEAD_BYTES = 6
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    /** Timestamp+counter shared across IDs minted in the same millisecond. */
    private var lastTimestamp = 0L
    private var counter = 0L

    /**
     * The 26-character identifier body (`descending()` in OpenCode). The
     * inverted-timestamp head keeps lexicographic order newest-first, like
     * the upstream generator; correctness here matters because the endpoint
     * may sanity-check the shape.
     */
    @Synchronized
    fun descending(): String {
        val now = System.currentTimeMillis()
        if (now != lastTimestamp) {
            lastTimestamp = now
            counter = 0
        }
        counter++
        val current = now * 0x1000L + counter
        val inverted = current.inv()
        val head = buildString {
            for (i in 0 until HEAD_BYTES) {
                val shift = (HEAD_BYTES - 1 - i) * 8
                append(((inverted shr shift) and 0xFFL).toString(16).padStart(2, '0'))
            }
        }
        val tail = buildString {
            repeat(BODY_LENGTH - HEAD_BYTES * 2) {
                append(CHARS[random.nextInt(CHARS.length)])
            }
        }
        return head + tail
    }

    /** `ses_<descending>` — one per agent conversation/turn group. */
    fun sessionId(): String = "ses_" + descending()

    /** `msg_<descending>` — one per outbound request. */
    fun requestId(): String = "msg_" + descending()

    /**
     * Projects are opaque 26-character identifiers in the official client
     * (no prefix); one stable value per process is a reasonable stand-in for
     * the per-workspace id the CLI derives from its project directory.
     */
    fun projectId(): String = descending()

    /**
     * The exact header map the official client attaches to every Zen LLM
     * request. [project] is stable per process, [session] per conversation
     * group, [request] fresh per call.
     */
    fun identityHeaders(project: String, session: String, request: String): Map<String, String> = mapOf(
        "x-opencode-project" to project,
        "x-opencode-session" to session,
        "x-opencode-request" to request,
        "x-opencode-client" to CLIENT_NAME,
        "User-Agent" to UA,
    )
}
