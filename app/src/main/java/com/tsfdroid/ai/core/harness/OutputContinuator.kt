package com.tsfdroid.ai.core.harness

/**
 * v1.2.0: the OpenCode-style output-limit continuation policy.
 *
 * Every model has an output token ceiling. When the wire reports
 * `finish_reason: "length"` the answer was CLIPPED mid-generation — the
 * historical app just delivered the clipped text silently (the "the potential
 * is getting cut off" complaint). OpenCode instead calls the model again,
 * passing the partial answer forward, and stitches the continuation on. This
 * object is that policy, kept pure so it is unit-testable without a network.
 */
object OutputContinuator {

    /**
     * Trailed as a USER message after the partial assistant reply. Instruction
     * discipline matters: the model must not re-introduce, apologize, or
     * summarize — only continue the exact point it stopped.
     */
    const val CONTINUATION_PROMPT =
        "Your reply above was cut off by the output limit. Continue EXACTLY where it " +
            "stopped — same answer, same style, no repetition of any earlier text, no " +
            "apologies, no summary of what you already wrote. Resume mid-sentence if that " +
            "is where it stopped, and finish the complete answer."

    /**
     * True when the harness must issue a continuation call.
     *
     * @param finishReason the wire finish_reason of the last completion
     *                     ("length" = clipped by the output budget).
     * @param accumulated  the answer text accumulated so far (continuation
     *                     requires something to continue from).
     * @param continuationsSoFar how many continuation calls already ran for
     *                     this reply — bounded by [EffortLevel.maxContinuations]
     *                     so a pathological model can never loop forever.
     * @param maxContinuations the effort-level continuation allowance.
     */
    fun shouldContinue(
        finishReason: String?,
        accumulated: String,
        continuationsSoFar: Int,
        maxContinuations: Int
    ): Boolean {
        if (continuationsSoFar >= maxContinuations) return false
        if (finishReason == null) return false
        if (!finishReason.equals("length", ignoreCase = true)) return false
        return accumulated.isNotBlank()
    }

    /**
     * Models occasionally restart the tail of their previous answer when
     * continuing (the last sentence comes through twice). Detects the longest
     * prefix of [continuation] that duplicates the tail of [previous] — up to
     * [maxOverlap] characters — and returns the continuation with that overlap
     * trimmed, so the stitched answer never reads with a doubled passage.
     */
    fun trimRepeatedOverlap(previous: String, continuation: String, maxOverlap: Int = 240): String {
        if (previous.isEmpty() || continuation.isEmpty()) return continuation
        val prevTail = previous.takeLast(maxOverlap)
        var best = 0
        val limit = minOf(prevTail.length, continuation.length, maxOverlap)
        for (length in limit downTo 1) {
            if (prevTail.endsWith(continuation.take(length))) {
                best = length
                break
            }
        }
        return if (best > 0) continuation.substring(best) else continuation
    }
}
